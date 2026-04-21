package io.safelang.compiler.wasm;

@FunctionalInterface
interface WasmBuiltinBodyCompiler {

  void compile(String builtin, int index, int type, int arity);
}
