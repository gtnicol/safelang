package io.safelang.compiler.wasm;

import static io.safelang.compiler.wasm.WasmOpcode.*;

/**
 * Small static helpers shared by every code emitter in the WASM backend.
 *
 * <p>These exist to remove the most-duplicated emit sequences from the compiler, builtin emitters,
 * and runtime builder. There is no shared mutable state — each helper is a pure operation on a
 * {@link WasmFunction} stack.
 */
final class WasmEmit {

  private WasmEmit() {}

  /**
   * Convert an {@code i32} pointer on top of the stack into a tagged {@code i64} of the given type
   * tag. The pattern is:
   *
   * <pre>
   *   i64.extend_i32_u
   *   i64.const TAG_BITS
   *   i64.shl
   *   i64.const tag    ;; (omitted if tag == 0)
   *   i64.or           ;; (omitted if tag == 0)
   * </pre>
   *
   * Used everywhere a C builtin returns a raw pointer or count and the caller wants to push it back
   * onto the SAFE value stack.
   */
  static void retagPointer(final WasmFunction function, final int tag) {
    function.emit(I64_EXTEND_I32_U);
    function.emitI64Const(WasmRuntime.TAG_BITS);
    function.emit(I64_SHL);
    if (tag != 0) {
      function.emitI64Const(tag);
      function.emit(I64_OR);
    }
  }

  /**
   * Tag an {@code i32} bool result (0/1) on top of the stack as a tagged SAFE bool value.
   * Convenience wrapper for the very common "compare → tag" pattern.
   */
  static void retagBool(final WasmFunction function) {
    retagPointer(function, WasmRuntime.TAG_BOOL);
  }

  /**
   * Allocate an enum variant on the heap and populate its header ({@code typeId}, {@code
   * variantIndex}, {@code fieldCount}). The values for the fields must already have been emitted
   * into the supplied {@code valueLocals}; this helper just stores them at the canonical offsets
   * and leaves a tagged enum pointer on the stack.
   *
   * @param function the function to emit into
   * @param runtime runtime context (for {@code alloc})
   * @param typeId the enum's globally unique type id
   * @param variantIndex the variant index within the enum
   * @param valueLocals the locals holding each tagged field value, in order
   */
  static void emitVariant(
      final WasmFunction function,
      final WasmRuntimeContext runtime,
      final int typeId,
      final int variantIndex,
      final int[] valueLocals) {
    final var fieldCount = valueLocals.length;
    final var size = WasmRuntime.VARIANT_HEADER_SIZE + fieldCount * WasmRuntime.FIELD_SLOT_SIZE;

    // Allocate with SAFE_KIND_ENUM and a conservative "all slots heap"
    // bitmap — dispose calls safe_rc_release_tagged on each slot, which is
    // a cheap tag-dispatch no-op for scalar fields. The caller retains
    // heap-tagged payload values (in visitFunctionCall etc.) so the
    // refcount balance stays correct.
    final var limit = fieldCount < 8 ? fieldCount : 8;
    final var meta = limit == 0 ? 0 : (1 << limit) - 1;
    function.emitI32Const(size);
    function.emitI32Const(6 /* SAFE_KIND_ENUM */);
    function.emitI32Const(meta);
    function.emitCall(runtime.rcAlloc);
    final var pointer = function.addLocal(WasmOpcode.TYPE_I32);
    function.emitLocalSet(pointer);

    // Header: type id, variant index, field count.
    function.emitLocalGet(pointer);
    function.emitI32Const(typeId);
    function.emitStore(WasmOpcode.I32_STORE, 2, 0);
    function.emitLocalGet(pointer);
    function.emitI32Const(variantIndex);
    function.emitStore(WasmOpcode.I32_STORE, 2, 4);
    function.emitLocalGet(pointer);
    function.emitI32Const(fieldCount);
    function.emitStore(WasmOpcode.I32_STORE, 2, 8);

    for (var i = 0; i < fieldCount; i++) {
      function.emitLocalGet(pointer);
      function.emitLocalGet(valueLocals[i]);
      function.emitStore(
          WasmOpcode.I64_STORE,
          3,
          WasmRuntime.VARIANT_FIELD_OFFSET + i * WasmRuntime.FIELD_SLOT_SIZE);
    }

    function.emitLocalGet(pointer);
    retagPointer(function, WasmRuntime.TAG_ENUM);
  }
}
