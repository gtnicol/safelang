package io.safelang.compiler.wasm;

import io.safelang.ast.ASTNode;

/**
 * Callback surface that {@link WasmBinaryEmitter} needs back into {@link WasmCompiler} — the active
 * function being emitted into and a way to recursively emit a sub-expression.
 */
interface WasmBinaryContext {

  /** The function currently being emitted into. */
  WasmFunction current();

  /** Recursively emit the WASM byte sequence for {@code node} into {@link #current()}. */
  void emit(ASTNode node);
}
