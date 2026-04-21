package io.safelang.compiler.wasm;

import java.util.Map;
import java.util.function.ToIntFunction;

/**
 * Shared dependencies for the per-category builtin emitters. Bundling them into one record keeps
 * each {@code emit*} helper from having to take seven parameters and lets {@link
 * WasmBuiltinEmitter} stay a thin dispatcher.
 */
record WasmBuiltinContext(
    WasmModule module,
    WasmRuntimeContext runtime,
    TypeRegistry types,
    Map<String, Integer> builtins,
    WasmBuiltinSupport support,
    ToIntFunction<String> interner) {

  /** Look up a C builtin index, throwing if it was never imported. */
  int requireBuiltin(final String name) {
    final var target = builtins.get(name);
    if (target == null) {
      throw new io.safelang.compiler.CompilerException(
          "WASM backend: required C builtin '" + name + "' was not imported");
    }
    return target;
  }
}
