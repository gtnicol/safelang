package io.safelang.compiler.wasm;

import io.safelang.ast.LambdaNode;

interface WasmLambdaHooks {

  int local(String name);

  boolean global(String name);

  SymbolKey value(String name);

  void schedule(String name, LambdaNode node, int index);
}
