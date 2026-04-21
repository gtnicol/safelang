package io.safelang.compiler.wasm;

import io.safelang.ast.ASTNode;

interface WasmCaseContext {

  /**
   * Compile the given node into the active WASM function and return the nominal type of the value
   * it left on the stack, or {@code null} if the value has no static nominal type.
   */
  SymbolKey compile(ASTNode node);

  void push();

  void pop();

  int allocate(String name, SymbolKey type);
}
