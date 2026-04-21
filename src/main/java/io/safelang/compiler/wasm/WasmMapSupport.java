package io.safelang.compiler.wasm;

import io.safelang.ast.AbstractASTVisitor;
import io.safelang.ast.MapEntryNode;
import io.safelang.ast.MapLiteralNode;

/**
 * Emits the WASM instruction sequence for a map literal.
 *
 * <p>Holds no per-compilation state — the active {@link WasmFunction} and the visitor used to
 * recursively compile keys/values are passed in at the call site, mirroring the shape of {@link
 * WasmCaseCompiler} and {@link WasmObjectCompiler}.
 */
final class WasmMapSupport {

  private static final boolean TRACE = Boolean.getBoolean("safe.wasm.map.trace");

  private final WasmRuntimeContext runtime;

  WasmMapSupport(final WasmRuntimeContext runtime) {
    this.runtime = runtime;
  }

  void compileLiteral(
      final WasmFunction function,
      final MapLiteralNode node,
      final AbstractASTVisitor<SymbolKey> visitor) {
    function.emitCall(runtime.mapNew);
    trace("map literal begin");
    for (final var entry : node.entries()) {
      entry.key().accept(visitor);
      entry.value().accept(visitor);
      function.emitCall(runtime.mapPut);
      traceEntry("map literal put", entry);
    }
    trace("map literal end");
  }

  private void trace(final String stage) {
    if (!TRACE) {
      return;
    }
    System.err.println("[wasm map] " + stage);
  }

  private void traceEntry(final String stage, final MapEntryNode entry) {
    if (!TRACE) {
      return;
    }
    System.err.println("[wasm map] " + stage + ": key=" + entry.key() + " value=" + entry.value());
  }
}
