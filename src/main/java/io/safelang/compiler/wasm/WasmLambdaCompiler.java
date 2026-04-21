package io.safelang.compiler.wasm;

import io.safelang.ast.LambdaNode;
import io.safelang.ast.TypeNode;

final class WasmLambdaCompiler {

  void compile(
      final WasmLambdaCompilerContext context,
      final LambdaNode node,
      final int index,
      final Runnable body) {
    final var params = 1 + node.parameters().size();
    final var types = new int[params];
    types[0] = WasmOpcode.TYPE_I32;
    for (var i = 1; i < types.length; i++) {
      types[i] = WasmOpcode.TYPE_I64;
    }
    final var type = context.module().addType(types, new int[] {WasmOpcode.TYPE_I64});

    final var function = new WasmFunction(index, type, types.length);
    context.setCurrent(function);

    context.pushScope();
    for (var i = 0; i < node.parameters().size(); i++) {
      final var parameter = node.parameters().get(i);
      context.scope().put(parameter.name(), i + 1);
      final var resolved = resolveNominal(context, parameter.type());
      if (resolved != null) {
        context.typeScope().put(parameter.name(), resolved);
      }
    }

    final var captured = context.captures(index);
    final var captureTypes = context.captureTypes(index);
    if (captured != null) {
      for (var i = 0; i < captured.size(); i++) {
        final var local = function.addLocal(WasmOpcode.TYPE_I64);
        context.scope().put(captured.get(i), local);
        if (captureTypes != null && i < captureTypes.size() && captureTypes.get(i) != null) {
          context.typeScope().put(captured.get(i), captureTypes.get(i));
        }
        function.emitLocalGet(0);
        function.emitLoad(WasmOpcode.I64_LOAD, 3, 8 + i * 8);
        function.emitLocalSet(local);
      }
    }

    context.setStateInFunction(true);
    context.setInFunction(true);
    body.run();
    context.setStateInFunction(false);
    context.setInFunction(false);

    context.popScope();
    context.addCode(index, function.encode(context.module()));
    context.setCurrent(null);
  }

  private SymbolKey resolveNominal(final WasmLambdaCompilerContext context, final TypeNode type) {
    return type == null ? null : context.resolveNominalType(type);
  }
}
