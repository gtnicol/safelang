package io.safelang.compiler.c;

import io.safelang.ast.*;
import java.util.stream.Collectors;

/**
 * Recursive SAFE type inference for the C backend.
 *
 * <p>Pure read-only walker over an {@link ASTNode} that returns the approximate SAFE type string of
 * the node's value (e.g. {@code "int"}, {@code "list<string>"}, {@code "map<int, Point>"}, or
 * {@code null} when the type cannot be determined). Used by the C code generator to drive
 * type-aware code emission (collection element boxing, format specifiers, checked arithmetic
 * dispatch).
 *
 * <p>Stateless — depends only on the {@link CInferContext} passed in at construction.
 */
final class CTypeInferer {

  private final CInferContext context;

  CTypeInferer(final CInferContext context) {
    this.context = context;
  }

  /** Best-effort SAFE type of {@code node}, or {@code null} if unknown. */
  String infer(final ASTNode node) {
    if (node == null) return null;
    return switch (node) {
      case LiteralNode lit ->
          switch (lit) {
            case LiteralNode.IntLiteral ignored -> "int";
            case LiteralNode.UintLiteral ignored -> "uint";
            case LiteralNode.FloatLiteral ignored -> "float";
            case LiteralNode.StringLiteral ignored -> "string";
            case LiteralNode.BoolLiteral ignored -> "boolean";
          };
      case VariableReferenceNode ref -> {
        // Check module variable via dot syntax
        final var parts = ref.parts();
        if (parts.size() >= 2 && context.modules().contains(parts.getFirst())) {
          final var mangled = context.mangle(parts.getFirst(), parts.get(1));
          final var type = context.variables().get(mangled);
          if (type != null) yield type;
        }
        if (parts.size() == 1) {
          final var type = context.variables().get(parts.getFirst());
          if (type != null) yield type;
        }
        // Struct field access: p.data where p is a struct type
        if (parts.size() == 2 && !context.modules().contains(parts.getFirst())) {
          final var receiver = context.variables().get(parts.getFirst());
          if (receiver != null) {
            final var declaration = context.structs().get(receiver);
            if (declaration != null) {
              for (final var field : declaration.fields()) {
                if (field.name().equals(parts.get(1))) {
                  yield field.type().fullName();
                }
              }
            }
          }
        }
        if (ref.hasPrefix() && context.modules().contains(ref.prefix())) {
          final var mangled = context.mangle(ref.prefix(), parts.getFirst());
          final var type = context.variables().get(mangled);
          if (type != null) yield type;
        }
        // Check if identifier is a bare enum variant (no args, like Null, Empty)
        if (parts.size() == 1) {
          for (final var entry : context.enumerations().entrySet()) {
            for (final var variant : entry.getValue().variants()) {
              if (variant.name().equals(parts.getFirst()) && variant.fields().isEmpty()) {
                yield entry.getKey();
              }
            }
          }
        }
        yield null;
      }
      case FunctionCallNode call -> {
        // Check if it's a module function with known return type
        if (call.hasPrefix() && context.registry() != null) {
          final var function = context.registry().function(call.prefix(), call.name());
          if (function != null) yield function.returns().fullName();
        }
        // Check intra-module function calls (no prefix, but inside a module)
        if (!call.hasPrefix() && context.currentModule() != null) {
          final var mangled = context.mangle(context.currentModule(), call.name());
          final var function = context.functions().get(mangled);
          if (function != null && function.returns() != null) yield function.returns().fullName();
        }
        // Check user-defined functions in the current compilation unit
        if (!call.hasPrefix()) {
          final var function = context.functions().get(call.name());
          if (function != null && function.returns() != null) yield function.returns().fullName();
        }
        // Check builtin return types
        final var fname = call.name();
        if ("str".equals(fname)
            || "substring".equals(fname)
            || "trim".equals(fname)
            || "upper".equals(fname)
            || "lower".equals(fname)
            || "replace".equals(fname)
            || "charAt".equals(fname)
            || "input".equals(fname)) yield "string";
        if ("len".equals(fname)
            || "int".equals(fname)
            || "integer".equals(fname)
            || "size".equals(fname)
            || "indexOf".equals(fname)) yield "int";
        if ("float".equals(fname)
            || "decimal".equals(fname)
            || "sqrt".equals(fname)
            || "pow".equals(fname)
            || "sin".equals(fname)
            || "cos".equals(fname)
            || "tan".equals(fname)
            || "asin".equals(fname)
            || "acos".equals(fname)
            || "atan".equals(fname)
            || "atan2".equals(fname)
            || "exp".equals(fname)
            || "log10".equals(fname)
            || "rand".equals(fname)
            || "log".equals(fname)) yield "float";
        if ("starts".equals(fname) || "ends".equals(fname)) yield "boolean";
        if ("range".equals(fname)) yield "list<int>";
        if ("chars".equals(fname) || "split".equals(fname)) yield "list<string>";
        if ("keys".equals(fname) && !call.arguments().isEmpty()) {
          final var type = infer(call.arguments().getFirst());
          if (type != null && type.startsWith("map<")) {
            yield "list<" + context.keyed(type) + ">";
          }
        }
        if ("values".equals(fname) && !call.arguments().isEmpty()) {
          final var type = infer(call.arguments().getFirst());
          if (type != null && type.startsWith("map<")) {
            yield "list<" + context.valued(type) + ">";
          }
        }
        if ("append".equals(fname) && !call.arguments().isEmpty()) {
          yield infer(call.arguments().getFirst());
        }
        // Check if function name is an enum variant constructor
        for (final var entry : context.enumerations().entrySet()) {
          for (final var variant : entry.getValue().variants()) {
            if (variant.name().equals(fname)) {
              yield entry.getKey();
            }
          }
        }
        // Check if calling a closure variable — infer return from fn<..., RetType>
        if (!call.hasPrefix()) {
          final var closure = context.variables().get(fname);
          if (closure != null && closure.startsWith("fn<")) {
            final var parameters = context.params(closure);
            if (!parameters.isEmpty()) {
              yield parameters.getLast();
            }
          }
        }
        yield null;
      }
      case IfExpressionNode conditional -> {
        if (conditional.hasOtherwise()) {
          yield infer(conditional.then());
        }
        yield "void";
      }
      case IndexAccessNode access -> {
        String type = null;
        if (access.container() instanceof VariableReferenceNode ref && ref.parts().size() == 1) {
          type = context.variables().get(ref.parts().getFirst());
        }
        if (type == null) {
          type = infer(access.container());
        }
        if (type != null) {
          if (type.startsWith("tuple<")) {
            yield context.tuple(type, access.index());
          }
          if (type.startsWith("list<")) {
            yield context.inner(type);
          }
          if (type.startsWith("map<")) {
            yield context.valued(type);
          }
        }
        yield null;
      }
      case ListLiteralNode list -> {
        if (!list.elements().isEmpty()) {
          yield "list<" + infer(list.elements().getFirst()) + ">";
        }
        yield "list";
      }
      case MapLiteralNode map -> {
        if (!map.entries().isEmpty()) {
          final var entry = map.entries().getFirst();
          yield "map<" + infer(entry.key()) + ", " + infer(entry.value()) + ">";
        }
        yield "map";
      }
      case SetLiteralNode set -> {
        if (!set.elements().isEmpty()) {
          yield "set<" + infer(set.elements().getFirst()) + ">";
        }
        yield "set";
      }
      case StringInterpolationNode ignored -> "string";
      case UnaryExpressionNode unary -> {
        if ("!".equals(unary.operator())) yield "boolean";
        yield infer(unary.operand());
      }
      case TupleLiteralNode tuple -> {
        final var types =
            tuple.elements().stream().map(this::infer).collect(Collectors.joining(", "));
        yield "tuple<" + types + ">";
      }
      case LambdaNode lambda -> {
        final var types = new StringBuilder("fn<");
        for (int i = 0; i < lambda.parameters().size(); i++) {
          if (i > 0) types.append(", ");
          final var param = lambda.parameters().get(i);
          types.append(param.type() != null ? param.type().fullName() : "int");
        }
        if (!lambda.parameters().isEmpty()) types.append(", ");
        final var result = infer(lambda.body());
        types.append(result != null ? result : "void");
        types.append(">");
        yield types.toString();
      }
      case RangeNode ignored -> "list<int>";
      case CaseExpressionNode cases -> {
        if (!cases.branches().isEmpty()) {
          yield infer(cases.branches().getFirst().result());
        }
        yield null;
      }
      case DoExpressionNode block -> infer(block.expression());
      case ObjectCreationNode creation -> creation.type();
      case FieldAccessNode access -> {
        final var receiver = infer(access.receiver());
        final var declaration = context.structs().get(receiver);
        if (declaration != null) {
          for (final var field : declaration.fields()) {
            if (field.name().equals(access.field())) {
              yield field.type().fullName();
            }
          }
        }
        yield null;
      }
      case BinaryExpressionNode binary -> {
        final var op = binary.operator();
        if ("==".equals(op)
            || "!=".equals(op)
            || "<".equals(op)
            || ">".equals(op)
            || "<=".equals(op)
            || ">=".equals(op)
            || "&&".equals(op)
            || "||".equals(op)) {
          yield "boolean";
        }
        if ("+".equals(op)) {
          if (isStringLike(binary.left()) || isStringLike(binary.right())) yield "string";
        }
        yield infer(binary.left());
      }
      default -> null;
    };
  }

  /** True when {@code node} is statically known to evaluate to a string. */
  boolean isStringLike(final ASTNode node) {
    if (node instanceof LiteralNode.StringLiteral) {
      return true;
    }
    if (node instanceof StringInterpolationNode) {
      return true;
    }
    if (node instanceof FunctionCallNode call) {
      final var name = call.name();
      if ("str".equals(name)
          || "substring".equals(name)
          || "trim".equals(name)
          || "upper".equals(name)
          || "lower".equals(name)
          || "replace".equals(name)
          || "charAt".equals(name)) {
        return true;
      }
    }
    if (node instanceof VariableReferenceNode ref && ref.parts().size() == 1) {
      final var type = context.variables().get(ref.parts().getFirst());
      return "string".equals(type);
    }
    if (node instanceof BinaryExpressionNode bin && "+".equals(bin.operator())) {
      return isStringLike(bin.left()) || isStringLike(bin.right());
    }
    return false;
  }
}
