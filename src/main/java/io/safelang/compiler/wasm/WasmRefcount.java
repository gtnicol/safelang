package io.safelang.compiler.wasm;

import static io.safelang.compiler.wasm.WasmOpcode.END;
import static io.safelang.compiler.wasm.WasmOpcode.I64_NE;
import static io.safelang.compiler.wasm.WasmOpcode.TYPE_I64;
import static io.safelang.compiler.wasm.WasmOpcode.TYPE_VOID;

import java.util.List;

/**
 * Emission helpers for the WASM backend's refcount discipline. Each method assumes a specific stack
 * shape (documented per method) and emits the canonical retain / release / allocation sequence.
 *
 * <p>The helper intentionally centralises every call to {@link WasmRuntimeContext#retainTagged} /
 * {@link WasmRuntimeContext#releaseTagged} so that changes to the retain/release signatures (for
 * example swapping to an inlined version) touch one file.
 */
final class WasmRefcount {

  private final WasmRuntimeContext runtime;

  WasmRefcount(final WasmRuntimeContext runtime) {
    this.runtime = runtime;
  }

  /**
   * Stack: [… tagged] → [… tagged].
   *
   * <p>{@code safe_rc_retain_tagged} takes a tagged i64 and returns it unchanged, so the call
   * leaves the input on the stack after bumping its refcount (or no-op for non-heap tags). Use
   * before a container insert, an aliased-RHS store, or any site that creates a new owning slot.
   */
  void retain(final WasmFunction function) {
    function.emitCall(runtime.retainTagged);
  }

  /**
   * Stack: [… tagged] → […].
   *
   * <p>Consumes the tagged i64 on top of the stack. Use for overwrites, scope exits, and function
   * returns of heap locals that transfer no further.
   */
  void release(final WasmFunction function) {
    function.emitCall(runtime.releaseTagged);
  }

  /**
   * Release a named local (tagged i64). Equivalent to {@code local.get slot; call
   * $safe_rc_release_tagged}. Convenience for scope- exit emission where the slot is already known.
   */
  void releaseLocal(final WasmFunction function, final int slot) {
    function.emitLocalGet(slot);
    function.emitCall(runtime.releaseTagged);
  }

  /**
   * Release {@code slot} only when it differs from a temporary holding the freshly-produced
   * replacement value. Emits:
   *
   * <pre>
   * local.get tmp
   * local.get slot
   * i64.ne
   * if
   *   local.get slot
   *   call $safe_rc_release_tagged
   * end
   * </pre>
   *
   * Needed when the RHS is a fresh owning producer (function call, constructor) that might return
   * the same pointer the slot already holds — e.g. {@code list = safe_list_append(list, v)} when
   * append mutated in place. Without the guard the caller would free the block that the RHS is
   * still referring to.
   */
  void releaseIfChanged(final WasmFunction function, final int slot, final int tmp) {
    function.emitLocalGet(tmp);
    function.emitLocalGet(slot);
    function.emit(I64_NE);
    function.emitIf(TYPE_VOID);
    function.emitLocalGet(slot);
    function.emitCall(runtime.releaseTagged);
    function.emit(END);
  }

  /**
   * Emit a size-class {@code safe_rc_alloc(size, kind, meta)} call. Leaves the body pointer (i32)
   * on the stack. Currently the WASM backend reaches safe_rc_alloc via the exported host builtin,
   * so this is a convenience for emission sites that today call {@code runtime.alloc} (the
   * non-headered variant).
   */
  int allocCallIndex(final WasmCompilationState state) {
    return state.builtins.get("safe_rc_alloc");
  }

  /**
   * Short-lived scratch local for the "evaluate rhs into tmp, do refcount bookkeeping, write back"
   * pattern.
   */
  static int allocTaggedScratch(final WasmFunction function) {
    return function.addLocal(TYPE_I64);
  }

  /**
   * Is this SAFE type heap-tagged in the WASM backend? WASM pointer-boxes everything that isn't a
   * primitive scalar, so the answer is "not a primitive name". This is the WASM complement to
   * {@code RefcountPolicy.isHeap} (which is for the C backend, where structs and enums are value
   * types). String types return true here, but STRING tag isn't in {@code safe_tag_is_heap} yet
   * (Phase 6), so retain/ release on them is a no-op at runtime — the bit in the bitmap is
   * harmlessly set.
   */
  static boolean isHeapType(final String type) {
    if (type == null) return false;
    return switch (type) {
      case "int", "uint", "float", "bool", "boolean", "void" -> false;
      default -> true;
    };
  }

  /**
   * Compute the 8-bit heap-field bitmap for a list of field declarations. Bit N is set iff field N
   * is heap-tagged. Fields beyond index 7 are silently dropped — bitmap-overflow is a documented
   * limit in safe_refcount.h.
   */
  static int bitmapOverFields(final List<io.safelang.ast.FieldDeclarationNode> fields) {
    int bits = 0;
    final int limit = Math.min(fields.size(), 8);
    for (int i = 0; i < limit; i++) {
      if (isHeapType(fields.get(i).type().fullName())) {
        bits |= 1 << i;
      }
    }
    return bits;
  }
}
