package io.safelang.compiler.wasm;

import static io.safelang.compiler.wasm.WasmOpcode.*;

import io.safelang.ast.ASTNode;
import io.safelang.ast.CaseBranchNode;
import io.safelang.ast.EnumPatternNode;
import io.safelang.ast.LiteralNode;
import io.safelang.ast.VariableReferenceNode;
import java.util.List;
import java.util.function.BiFunction;

final class WasmCaseCompiler {

  private final WasmRuntimeContext runtime;
  private final TypeRegistry types;
  private final WasmCaseContext context;
  private final BiFunction<SymbolKey, String, TypeRegistry.Variant> lookup;
  private final int equality;

  WasmCaseCompiler(
      final WasmRuntimeContext runtime,
      final TypeRegistry types,
      final WasmCaseContext context,
      final BiFunction<SymbolKey, String, TypeRegistry.Variant> lookup,
      final int equality) {
    this.runtime = runtime;
    this.types = types;
    this.context = context;
    this.lookup = lookup;
    this.equality = equality;
  }

  /**
   * Compile a case expression's branches and return the nominal type of the resulting value, or
   * {@code null} if no branch produced a nominal type.
   */
  SymbolKey compile(
      final WasmFunction function,
      final List<CaseBranchNode> branches,
      final int subject,
      final SymbolKey type,
      final ASTNode fallback) {
    return compileBranches(function, branches, 0, subject, type, fallback);
  }

  private SymbolKey compileBranches(
      final WasmFunction function,
      final List<CaseBranchNode> branches,
      final int index,
      final int subject,
      final SymbolKey type,
      final ASTNode fallback) {
    if (index >= branches.size()) {
      if (fallback != null) {
        return context.compile(fallback);
      }
      function.emitCall(runtime.tagVoid);
      return null;
    }

    final var branch = branches.get(index);
    final var pattern = branch.pattern();

    if (pattern == null) {
      if (branch.hasGuard()) {
        context.compile(branch.guard());
        function.emitCall(runtime.untagInt);
        function.emit(I32_WRAP_I64);
        function.emitIf(TYPE_I64);
        final var taken = context.compile(branch.result());
        function.emit(ELSE);
        compileBranches(function, branches, index + 1, subject, type, fallback);
        function.emit(END);
        return taken;
      }
      return context.compile(branch.result());
    }

    if (pattern instanceof EnumPatternNode enumeration) {
      final var match = lookup.apply(type, enumeration.variant());
      if (match != null) {
        function.emitLocalGet(subject);
        function.emitCall(runtime.untagPointer);
        final var pointer = function.addLocal(TYPE_I32);
        function.emitLocalSet(pointer);

        emitEnumCondition(function, pointer, match);

        if (branch.hasGuard()) {
          function.emitIf(TYPE_I32);
          context.push();
          for (var field = 0; field < enumeration.bindings().size(); field++) {
            final var name = enumeration.bindings().get(field);
            final var local =
                context.allocate(name, types.variantFieldType(match.owner(), match.index(), field));
            function.emitLocalGet(pointer);
            function.emitLoad(I64_LOAD, 3, 12 + field * 8);
            function.emitLocalSet(local);
          }
          context.compile(branch.guard());
          function.emitCall(runtime.untagInt);
          function.emit(I32_WRAP_I64);
          context.pop();
          function.emit(ELSE);
          function.emitI32Const(0);
          function.emit(END);
        }

        function.emitIf(TYPE_I64);
        context.push();
        for (var field = 0; field < enumeration.bindings().size(); field++) {
          final var name = enumeration.bindings().get(field);
          final var local =
              context.allocate(name, types.variantFieldType(match.owner(), match.index(), field));
          function.emitLocalGet(pointer);
          function.emitLoad(I64_LOAD, 3, 12 + field * 8);
          function.emitLocalSet(local);
        }
        final var taken = context.compile(branch.result());
        context.pop();
        function.emit(ELSE);
        compileBranches(function, branches, index + 1, subject, type, fallback);
        function.emit(END);
        return taken;
      }
    } else if (pattern instanceof LiteralNode literal) {
      function.emitLocalGet(subject);
      context.compile(literal);
      function.emitCall(equality);
      function.emitCall(runtime.untagInt);
      function.emit(I32_WRAP_I64);
      if (branch.hasGuard()) {
        function.emitIf(TYPE_I32);
        context.compile(branch.guard());
        function.emitCall(runtime.untagInt);
        function.emit(I32_WRAP_I64);
        function.emit(ELSE);
        function.emitI32Const(0);
        function.emit(END);
      }
      function.emitIf(TYPE_I64);
      final var taken = context.compile(branch.result());
      function.emit(ELSE);
      compileBranches(function, branches, index + 1, subject, type, fallback);
      function.emit(END);
      return taken;
    } else if (pattern instanceof VariableReferenceNode reference
        && reference.parts().size() == 1
        && reference.parts().getFirst().equals("_")) {
      if (branch.hasGuard()) {
        context.compile(branch.guard());
        function.emitCall(runtime.untagInt);
        function.emit(I32_WRAP_I64);
        function.emitIf(TYPE_I64);
        final var taken = context.compile(branch.result());
        function.emit(ELSE);
        compileBranches(function, branches, index + 1, subject, type, fallback);
        function.emit(END);
        return taken;
      }
      return context.compile(branch.result());
    } else if (pattern instanceof VariableReferenceNode reference
        && reference.parts().size() == 1) {
      final var name = reference.parts().getFirst();
      final var match = lookup.apply(type, name);
      if (match != null && match.arity() == 0) {
        function.emitLocalGet(subject);
        function.emitCall(runtime.untagPointer);
        final var pointer = function.addLocal(TYPE_I32);
        function.emitLocalSet(pointer);

        emitEnumCondition(function, pointer, match);

        if (branch.hasGuard()) {
          function.emitIf(TYPE_I32);
          context.compile(branch.guard());
          function.emitCall(runtime.untagInt);
          function.emit(I32_WRAP_I64);
          function.emit(ELSE);
          function.emitI32Const(0);
          function.emit(END);
        }

        function.emitIf(TYPE_I64);
        final var taken = context.compile(branch.result());
        function.emit(ELSE);
        compileBranches(function, branches, index + 1, subject, type, fallback);
        function.emit(END);
        return taken;
      }

      if (branch.hasGuard()) {
        context.push();
        final var local = context.allocate(name, type);
        function.emitLocalGet(subject);
        function.emitLocalSet(local);
        context.compile(branch.guard());
        function.emitCall(runtime.untagInt);
        function.emit(I32_WRAP_I64);
        function.emitIf(TYPE_I64);
        final var taken = context.compile(branch.result());
        context.pop();
        function.emit(ELSE);
        compileBranches(function, branches, index + 1, subject, type, fallback);
        function.emit(END);
        return taken;
      }

      context.push();
      final var local = context.allocate(name, type);
      function.emitLocalGet(subject);
      function.emitLocalSet(local);
      final var taken = context.compile(branch.result());
      context.pop();
      return taken;
    }

    return compileBranches(function, branches, index + 1, subject, type, fallback);
  }

  private void emitEnumCondition(
      final WasmFunction function, final int pointer, final TypeRegistry.Variant match) {
    function.emitLocalGet(pointer);
    function.emitLoad(I32_LOAD, 2, 0);
    function.emitI32Const(match.type());
    function.emit(I32_EQ);
    function.emitLocalGet(pointer);
    function.emitLoad(I32_LOAD, 2, 4);
    function.emitI32Const(match.index());
    function.emit(I32_EQ);
    function.emit(I32_AND);
  }
}
