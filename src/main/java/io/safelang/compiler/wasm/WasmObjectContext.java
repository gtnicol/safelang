package io.safelang.compiler.wasm;

import io.safelang.ast.ASTNode;

interface WasmObjectContext {

  /**
   * Compile the given node into the active WASM function and return the nominal type of the value
   * it left on the stack (or {@code null} if the value has no static nominal type — e.g. a
   * primitive int, list, or void).
   */
  SymbolKey compile(ASTNode node);

  int local(String name);

  Integer global(String name);

  SymbolKey value(String name);

  void tag(int tag);

  /**
   * True if {@code node} is a fresh owning producer — its refs=1 allocation transfers without a
   * retain. Delegates to {@link io.safelang.compiler.refcount.RefcountPolicy#isFreshProducer}.
   */
  boolean isFreshProducer(ASTNode node);
}
