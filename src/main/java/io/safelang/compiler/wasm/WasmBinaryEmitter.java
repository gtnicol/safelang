package io.safelang.compiler.wasm;

import static io.safelang.compiler.wasm.WasmOpcode.*;

import io.safelang.ast.ASTNode;
import io.safelang.ast.BinaryExpressionNode;
import io.safelang.ast.LiteralNode;
import io.safelang.ast.StringInterpolationNode;
import io.safelang.ast.VariableReferenceNode;
import io.safelang.compiler.CompilerException;

/**
 * Emits the WASM byte sequence for a binary expression. Five strategies, in order:
 *
 * <ol>
 *   <li>Short-circuit logical ({@code &&}, {@code ||}) — branches on the left operand without
 *       evaluating the right.
 *   <li>String concatenation ({@code +} where either side is statically a string).
 *   <li>String equality ({@code ==}, {@code !=} where either side is statically a string).
 *   <li>Membership ({@code in}) — runtime dispatch on the container's tag.
 *   <li>Numeric / bitwise / comparison — float dispatch when either operand is statically float,
 *       otherwise tagged-int dispatch.
 * </ol>
 */
final class WasmBinaryEmitter {

  private final WasmRuntimeContext runtime;
  private final WasmBuiltinSupport support;
  private final WasmCompilationState state;
  private final WasmBinaryContext context;

  WasmBinaryEmitter(
      final WasmRuntimeContext runtime,
      final WasmBuiltinSupport support,
      final WasmCompilationState state,
      final WasmBinaryContext context) {
    this.runtime = runtime;
    this.support = support;
    this.state = state;
    this.context = context;
  }

  void emit(final BinaryExpressionNode node) {
    final var op = node.operator();
    if ("&&".equals(op) || "||".equals(op)) {
      shortCircuit(op, node);
      return;
    }
    if ("+".equals(op) && (isString(node.left()) || isString(node.right()))) {
      stringConcat(node);
      return;
    }
    if (("==".equals(op) || "!=".equals(op)) && (isString(node.left()) || isString(node.right()))) {
      stringEquality(op, node);
      return;
    }
    if ("in".equals(op)) {
      includes(node);
      return;
    }
    if ("float".equals(typeOf(node.left())) || "float".equals(typeOf(node.right()))) {
      floatOperation(op, node);
      return;
    }
    integerOperation(op, node);
  }

  private void shortCircuit(final String op, final BinaryExpressionNode node) {
    final var current = context.current();
    context.emit(node.left());
    current.emitCall(runtime.untagInt);
    current.emit(I32_WRAP_I64);
    current.emitIf(WasmOpcode.TYPE_I64);
    if ("&&".equals(op)) {
      context.emit(node.right());
      current.emit(ELSE);
      current.emitI64Const((0L << WasmRuntime.TAG_BITS) | WasmRuntime.TAG_BOOL);
    } else {
      current.emitI64Const((1L << WasmRuntime.TAG_BITS) | WasmRuntime.TAG_BOOL);
      current.emit(ELSE);
      context.emit(node.right());
    }
    current.emit(END);
  }

  private void stringConcat(final BinaryExpressionNode node) {
    final var current = context.current();
    context.emit(node.left());
    context.emit(node.right());
    current.emitCall(support.stringConcat());
  }

  private void stringEquality(final String op, final BinaryExpressionNode node) {
    final var current = context.current();
    context.emit(node.left());
    current.emitCall(runtime.untagPointer);
    context.emit(node.right());
    current.emitCall(runtime.untagPointer);
    current.emitCall(runtime.strEqual);
    if ("!=".equals(op)) {
      current.emit(I32_EQZ);
    }
    WasmEmit.retagBool(current);
  }

  private void includes(final BinaryExpressionNode node) {
    final var current = context.current();
    context.emit(node.right());
    final var container = current.addLocal(WasmOpcode.TYPE_I64);
    current.emitLocalSet(container);
    current.emitLocalGet(container);
    current.emitCall(runtime.tag);
    current.emitI32Const(WasmRuntime.TAG_MAP);
    current.emit(I32_EQ);
    current.emitIf(WasmOpcode.TYPE_I64);
    current.emitLocalGet(container);
    context.emit(node.left());
    current.emitCall(support.mapContains());
    current.emit(ELSE);
    current.emitLocalGet(container);
    current.emitCall(runtime.tag);
    current.emitI32Const(WasmRuntime.TAG_STRING);
    current.emit(I32_EQ);
    current.emitIf(WasmOpcode.TYPE_I64);
    // String substring containment via safe_str_indexof != -1.
    current.emitLocalGet(container);
    current.emitCall(runtime.untagPointer);
    context.emit(node.left());
    current.emitCall(runtime.untagPointer);
    current.emitCall(state.builtins.get("safe_str_indexof"));
    current.emitI32Const(-1);
    current.emit(I32_NE);
    WasmEmit.retagBool(current);
    current.emit(ELSE);
    // List / set
    current.emitLocalGet(container);
    current.emitCall(runtime.untagPointer);
    context.emit(node.left());
    current.emitCall(state.builtins.get("safe_list_contains"));
    WasmEmit.retagBool(current);
    current.emit(END);
    current.emit(END);
  }

  private void floatOperation(final String op, final BinaryExpressionNode node) {
    final var current = context.current();
    context.emit(node.left());
    current.emitCall(runtime.untagFloat);
    context.emit(node.right());
    current.emitCall(runtime.untagFloat);
    switch (op) {
      case "+" -> current.emit(F64_ADD);
      case "-" -> current.emit(F64_SUB);
      case "*" -> current.emit(F64_MUL);
      case "/" -> current.emit(F64_DIV);
      case "==" -> {
        current.emit(F64_EQ);
        WasmEmit.retagBool(current);
        return;
      }
      case "!=" -> {
        current.emit(F64_NE);
        WasmEmit.retagBool(current);
        return;
      }
      case "<" -> {
        current.emit(F64_LT);
        WasmEmit.retagBool(current);
        return;
      }
      case ">" -> {
        current.emit(F64_GT);
        WasmEmit.retagBool(current);
        return;
      }
      case "<=" -> {
        current.emit(F64_LE);
        WasmEmit.retagBool(current);
        return;
      }
      case ">=" -> {
        current.emit(F64_GE);
        WasmEmit.retagBool(current);
        return;
      }
      default ->
          throw new CompilerException("WASM backend: float operator '" + op + "' not supported");
    }
    current.emitCall(runtime.tagFloat);
  }

  private void integerOperation(final String op, final BinaryExpressionNode node) {
    final var current = context.current();
    context.emit(node.left());
    current.emitCall(runtime.untagInt);
    context.emit(node.right());
    current.emitCall(runtime.untagInt);
    switch (op) {
      case "+" -> current.emit(I64_ADD);
      case "-" -> current.emit(I64_SUB);
      case "*" -> current.emit(I64_MUL);
      case "/" -> current.emit(I64_DIV_S);
      case "%" -> current.emit(I64_REM_S);
      case "&" -> current.emit(I64_AND);
      case "|" -> current.emit(I64_OR);
      case "^" -> current.emit(I64_XOR);
      case "<<" -> current.emit(I64_SHL);
      case ">>" -> current.emit(I64_SHR_S);
      case "==" -> {
        current.emit(I64_EQ);
        WasmEmit.retagBool(current);
        return;
      }
      case "!=" -> {
        current.emit(I64_NE);
        WasmEmit.retagBool(current);
        return;
      }
      case "<" -> {
        current.emit(I64_LT_S);
        WasmEmit.retagBool(current);
        return;
      }
      case ">" -> {
        current.emit(I64_GT_S);
        WasmEmit.retagBool(current);
        return;
      }
      case "<=" -> {
        current.emit(I64_LE_S);
        WasmEmit.retagBool(current);
        return;
      }
      case ">=" -> {
        current.emit(I64_GE_S);
        WasmEmit.retagBool(current);
        return;
      }
      default ->
          throw new CompilerException("WASM backend: unsupported binary operator '" + op + "'");
    }
    current.emitCall(runtime.tagInt);
  }

  private boolean isString(final ASTNode node) {
    return "string".equals(typeOf(node));
  }

  /** Best-effort static type lookup, for primitive operator dispatch only. */
  private String typeOf(final ASTNode node) {
    if (node instanceof LiteralNode literal) {
      return switch (literal) {
        case LiteralNode.IntLiteral ignored -> "int";
        case LiteralNode.UintLiteral ignored -> "uint";
        case LiteralNode.FloatLiteral ignored -> "float";
        case LiteralNode.StringLiteral ignored -> "string";
        case LiteralNode.BoolLiteral ignored -> "bool";
      };
    }
    if (node instanceof StringInterpolationNode) {
      return "string";
    }
    if (node instanceof VariableReferenceNode reference && reference.parts().size() == 1) {
      return state.resolvePrimitive(reference.parts().getFirst());
    }
    if (node instanceof BinaryExpressionNode binary) {
      // String + anything is a string under SAFE's implicit-stringify rule.
      if ("+".equals(binary.operator())
          && ("string".equals(typeOf(binary.left())) || "string".equals(typeOf(binary.right())))) {
        return "string";
      }
    }
    return null;
  }
}
