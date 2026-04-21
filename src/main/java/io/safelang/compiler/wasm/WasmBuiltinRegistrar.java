package io.safelang.compiler.wasm;

import io.safelang.ast.ProgramNode;
import io.safelang.runtime.BuiltinRegistry;
import java.util.*;

final class WasmBuiltinRegistrar {

  private final String name;
  private final boolean main;
  private final WasmModule module;
  private final WasmCompilationState state;
  private final ModuleSymbols symbols;
  private final WasmBuiltinCollector collector = new WasmBuiltinCollector();

  WasmBuiltinRegistrar(
      final String name,
      final boolean main,
      final WasmModule module,
      final WasmCompilationState state,
      final ModuleSymbols symbols) {
    this.name = name;
    this.main = main;
    this.module = module;
    this.state = state;
    this.symbols = symbols;
  }

  void register(final ProgramNode program, final WasmBuiltinBodyCompiler compiler) {
    final var builtins = new LinkedHashSet<String>();
    collector.collect(program, builtins);

    for (final var builtin : builtins) {
      if (state.stubs.containsKey(builtin)) {
        continue;
      }
      // In the main program, a user-declared function with the same name as a
      // builtin shadows the builtin and we don't need to allocate a stub for
      // the builtin at all. (Inside a module, builtins always win — they
      // shadow same-name user functions, so we still allocate.)
      if (main && symbols.hasFunction(builtin)) {
        continue;
      }
      if (state.moduleImports.containsKey(name + "$" + builtin)) {
        continue;
      }
      if (!BuiltinRegistry.isBuiltin(builtin)) {
        continue;
      }

      final var declaration = BuiltinRegistry.get(builtin);
      if (declaration == null) {
        continue;
      }

      final var arity = declaration.signature().parameters().size();
      final var parameters = new int[arity];
      Arrays.fill(parameters, WasmOpcode.TYPE_I64);
      final var type = module.addType(parameters, new int[] {WasmOpcode.TYPE_I64});
      final var index = module.addFunction(type);
      state.stubs.put(builtin, index);
      state.stubArities.put(builtin, arity);
      state.deferred.add(() -> compiler.compile(builtin, index, type, arity));
    }
  }
}
