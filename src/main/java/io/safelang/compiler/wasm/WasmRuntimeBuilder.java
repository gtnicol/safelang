package io.safelang.compiler.wasm;

import static io.safelang.compiler.wasm.WasmOpcode.*;

/**
 * Wires the host runtime — memory, allocator, WASI, C builtins, and the tag/untag/wrapper helper
 * functions — into a fresh {@link WasmModule}.
 *
 * <p>This class exists to keep {@link WasmCompiler} focused on AST visiting. It owns one big
 * concern: setting up everything that user code expects to be available before the first AST node
 * is compiled.
 *
 * <p>It is invoked exactly once per module compilation, from {@code WasmCompiler.compile()}, and
 * produces (1) a populated {@link WasmRuntimeContext} (2) a {@link WasmBuiltinSupport} record
 * holding the indices of the tagged-value wrapper functions (3) a populated {@code state.builtins}
 * map of {@code safe_*} C builtin name → function index for direct use by {@link
 * WasmBuiltinEmitter}.
 *
 * <h2>Calling convention</h2>
 *
 * The C runtime functions exported from {@code safe_wasm_builtins.wasm} take <em>untagged</em>
 * values (i32 pointers, i64 raw integers, f64 raw floats). SAFE values on the WASM stack are
 * <em>tagged</em> i64s. The wrapper layer built here adapts between the two:
 *
 * <pre>
 * tagged i64 ──[untag]──&gt; raw value ──[C builtin]──&gt; raw result ──[tag]──&gt; tagged i64
 * </pre>
 */
final class WasmRuntimeBuilder {

  // Convenience aliases used by the WASI / closure-import declarations below.
  private static final int I32 = TYPE_I32;
  private static final int I64 = TYPE_I64;
  private final WasmModule module;
  private final WasmCompilationState state;
  private final WasmRuntimeContext runtime;
  // Cached function-type indices for the wrapper signatures.
  private int typeI64ToI64;
  private int typeI64I64ToI64;
  private int typeI64I64I64ToI64;
  private int typeF64ToI64;
  private int typeI64ToF64;
  private int typeI64ToVoid;
  private int typeVoidToI64;

  WasmRuntimeBuilder(
      final WasmModule module, final WasmCompilationState state, final WasmRuntimeContext runtime) {
    this.module = module;
    this.state = state;
    this.runtime = runtime;
  }

  /**
   * Phase 1: import memory + every C builtin + WASI. Must be called <em>before</em> any local
   * function is allocated, so that the import index space is fixed before {@link
   * #emitHelpersAndWrappers()} starts handing out local function indices. {@link WasmCompiler} also
   * slots its cross-module imports (e.g. {@code io::println}) into this phase.
   */
  void importHosts() {
    module.importMemory("builtins", "memory");
    cacheTypes();
    importCBuiltins();
    importWasi();
    importClosures();
  }

  // ========== Type cache ==========

  /**
   * Import the shared closure table and the per-arity {@code __callN} trampolines from the {@code
   * __closures} preload module. Each module that uses lambdas reads from the same shared table so
   * that closure values can cross module boundaries.
   */
  private void importClosures() {
    module.importTable("__closures", "__table");
    for (var arity = 0; arity <= 8; arity++) {
      final var params = new int[1 + arity];
      for (var i = 0; i < params.length; i++) params[i] = I64;
      final var type = module.addType(params, new int[] {I64});
      state.callImports[arity] = module.importFunction("__closures", "__call" + arity, type);
    }
  }

  // ========== C builtin imports ==========

  /**
   * Phase 2: emit the tag/untag helpers and the tagged-value wrappers. Returns the support record
   * holding the wrapper indices for {@link WasmBuiltinEmitter}. Must only be called after all
   * imports (host and user-module) have been added, since the helpers are local functions whose
   * indices depend on the final import count.
   */
  WasmBuiltinSupport emitHelpersAndWrappers() {
    emitTagHelpers();
    emitPrintHelpers();

    // Wrap safe_values_eq into a tagged-bool helper so callers (including
    // the case compiler's equality check) can use it uniformly.
    final var rawValuesEq = state.builtins.get("safe_values_eq");
    runtime.valuesEqual =
        emitHelper(
            typeI64I64ToI64,
            fn -> {
              fn.emitLocalGet(0);
              fn.emitLocalGet(1);
              fn.emitCall(rawValuesEq);
              WasmEmit.retagBool(fn);
            });

    final var support =
        new WasmBuiltinSupport(
            // stringify: (tagged) -> tagged_string. safe_to_string takes the tagged
            // value directly, no untag step.
            emitTaggedInPtrOut(runtime.toString, WasmRuntime.TAG_STRING),
            emitPtrInIntOut(state.builtins.get("safe_str_len")),
            emitStringConcatWrap(),
            emitListNewWrap(),
            emitPtrInIntOut(state.builtins.get("safe_list_len")),
            emitListGetWrap(),
            emitListAppendWrap(),
            emitListRemoveAtWrap(),
            emitListSliceWrap(),
            emitPtrInPtrOut(state.builtins.get("safe_list_reverse"), WasmRuntime.TAG_LIST),
            emitPtrInIntOut(state.builtins.get("safe_map_len")),
            emitMapContainsWrap(),
            emitPtrInPtrOut(state.builtins.get("safe_map_keys"), WasmRuntime.TAG_LIST),
            emitPtrInPtrOut(state.builtins.get("safe_map_values"), WasmRuntime.TAG_LIST));

    // The map ops live on the runtime context (called directly by
    // WasmMapSupport without a separate wrapper layer).
    runtime.mapNew = emitMapNewWrap();
    runtime.mapPut = emitMapPutWrap();
    runtime.mapGet =
        state.builtins.get(
            "safe_map_get"); // already (i32, i64) → i64; the visitor untags map ptr at the call
    // site

    return support;
  }

  private void cacheTypes() {
    typeI64ToI64 = module.addType(new int[] {TYPE_I64}, new int[] {TYPE_I64});
    typeI64I64ToI64 = module.addType(new int[] {TYPE_I64, TYPE_I64}, new int[] {TYPE_I64});
    typeI64I64I64ToI64 =
        module.addType(new int[] {TYPE_I64, TYPE_I64, TYPE_I64}, new int[] {TYPE_I64});
    typeF64ToI64 = module.addType(new int[] {TYPE_F64}, new int[] {TYPE_I64});
    typeI64ToF64 = module.addType(new int[] {TYPE_I64}, new int[] {TYPE_F64});
    typeI64ToVoid = module.addType(new int[] {TYPE_I64}, new int[] {});
    typeVoidToI64 = module.addType(new int[] {}, new int[] {TYPE_I64});
  }

  private void importCBuiltins() {
    for (final var builtin : WasmHostBuiltins.ALL) {
      final var type = module.addType(builtin.params(), builtin.results());
      final var index = module.importFunction("builtins", builtin.name(), type);
      state.builtins.put(builtin.name(), index);
    }

    runtime.alloc = state.builtins.get("safe_alloc");
    runtime.rcAlloc = state.builtins.get("safe_rc_alloc");
    runtime.retainTagged = state.builtins.get("safe_rc_retain_tagged");
    runtime.releaseTagged = state.builtins.get("safe_rc_release_tagged");
    runtime.printRaw = state.builtins.get("safe_print_str");
    runtime.printTagged = state.builtins.get("safe_print_tagged");
    runtime.printlnTagged = state.builtins.get("safe_println_tagged");
    runtime.trapWithMessage = state.builtins.get("safe_trap_with_message");
    runtime.toString = state.builtins.get("safe_to_string");
    // valuesEqual is left as the raw C function here and replaced with a
    // tagged wrapper in emitHelpersAndWrappers() below.
    runtime.strConcat = state.builtins.get("safe_str_concat");
    runtime.strEqual = state.builtins.get("safe_str_eq");
    runtime.strLength = state.builtins.get("safe_str_len");
    runtime.strFromInt = state.builtins.get("safe_str_from_int");
    runtime.strFromFloat = state.builtins.get("safe_str_from_float");
    runtime.strFromBool = state.builtins.get("safe_str_from_bool");
    runtime.listNew = state.builtins.get("safe_list_new");
    runtime.listAppend = state.builtins.get("safe_list_append");
    runtime.listGet = state.builtins.get("safe_list_get");
    runtime.listSet = state.builtins.get("safe_list_set");
    runtime.listLength = state.builtins.get("safe_list_len");
    runtime.listRemoveAt = state.builtins.get("safe_list_remove_at");
    runtime.listSlice = state.builtins.get("safe_list_slice");
    runtime.listConcat = state.builtins.get("safe_list_concat");
    runtime.listReverse = state.builtins.get("safe_list_reverse");
    runtime.mapLength = state.builtins.get("safe_map_len");
    runtime.mapContains = state.builtins.get("safe_map_contains");
    runtime.mapKeys = state.builtins.get("safe_map_keys");
    runtime.mapValues = state.builtins.get("safe_map_values");
    runtime.mapRemove = state.builtins.get("safe_map_remove");
  }

  // ========== WASI imports ==========

  private void importWasi() {
    final var fdWriteType = module.addType(new int[] {I32, I32, I32, I32}, new int[] {I32});
    runtime.wasiWrite = module.importFunction("wasi_snapshot_preview1", "fd_write", fdWriteType);

    final var fdReadType = module.addType(new int[] {I32, I32, I32, I32}, new int[] {I32});
    runtime.wasiRead = module.importFunction("wasi_snapshot_preview1", "fd_read", fdReadType);

    final var procExitType = module.addType(new int[] {I32}, new int[] {});
    runtime.wasiExit = module.importFunction("wasi_snapshot_preview1", "proc_exit", procExitType);

    final var clockType = module.addType(new int[] {I32, I64, I32}, new int[] {I32});
    runtime.wasiClockTimeGet =
        module.importFunction("wasi_snapshot_preview1", "clock_time_get", clockType);

    final var randomType = module.addType(new int[] {I32, I32}, new int[] {I32});
    runtime.wasiRandomGet =
        module.importFunction("wasi_snapshot_preview1", "random_get", randomType);

    final var argsType = module.addType(new int[] {I32, I32}, new int[] {I32});
    runtime.wasiArgsGet = module.importFunction("wasi_snapshot_preview1", "args_get", argsType);
    runtime.wasiArgsSizesGet =
        module.importFunction("wasi_snapshot_preview1", "args_sizes_get", argsType);
    runtime.wasiEnvironGet =
        module.importFunction("wasi_snapshot_preview1", "environ_get", argsType);
    runtime.wasiEnvironSizesGet =
        module.importFunction("wasi_snapshot_preview1", "environ_sizes_get", argsType);
  }

  // ========== Tag / untag helpers ==========

  /**
   * Each helper is emitted as a tiny local function so the existing {@link WasmBuiltinEmitter} can
   * call them by index without changes.
   */
  private void emitTagHelpers() {
    runtime.tagInt =
        emitHelper(
            typeI64ToI64,
            fn -> {
              // value << TAG_BITS  (TAG_INT == 0, no OR needed)
              fn.emitLocalGet(0);
              fn.emitI64Const(WasmRuntime.TAG_BITS);
              fn.emit(I64_SHL);
            });

    runtime.untagInt =
        emitHelper(
            typeI64ToI64,
            fn -> {
              fn.emitLocalGet(0);
              fn.emitI64Const(WasmRuntime.TAG_BITS);
              fn.emit(I64_SHR_S);
            });

    runtime.tagFloat =
        emitHelper(
            typeF64ToI64,
            fn -> {
              // bits = reinterpret(f64); (bits & ~0xF) | TAG_FLOAT
              fn.emitLocalGet(0);
              fn.emit(I64_REINTERPRET_F64);
              fn.emitI64Const(~WasmRuntime.TAG_MASK);
              fn.emit(I64_AND);
              fn.emitI64Const(WasmRuntime.TAG_FLOAT);
              fn.emit(I64_OR);
            });

    runtime.untagFloat =
        emitHelper(
            typeI64ToF64,
            fn -> {
              fn.emitLocalGet(0);
              fn.emitI64Const(~WasmRuntime.TAG_MASK);
              fn.emit(I64_AND);
              fn.emit(F64_REINTERPRET_I64);
            });

    // tagBool: emitter pushes a raw i32 (the C function's result) before the
    // call, so the param is i32 not i64.
    final var i32ToI64 = module.addType(new int[] {I32}, new int[] {I64});
    runtime.tagBool =
        emitHelper(
            i32ToI64,
            fn -> {
              fn.emitLocalGet(0);
              WasmEmit.retagBool(fn);
            });

    // tagString: same — emitter pushes a raw i32 string pointer.
    runtime.tagString =
        emitHelper(
            i32ToI64,
            fn -> {
              fn.emitLocalGet(0);
              WasmEmit.retagPointer(fn, WasmRuntime.TAG_STRING);
            });

    runtime.untagPointer =
        emitHelper(
            module.addType(new int[] {I64}, new int[] {I32}),
            fn -> {
              fn.emitLocalGet(0);
              fn.emitI64Const(WasmRuntime.TAG_BITS);
              fn.emit(I64_SHR_U);
              fn.emit(I32_WRAP_I64);
            });

    runtime.tagVoid = emitHelper(typeVoidToI64, fn -> fn.emitI64Const(WasmRuntime.TAG_VOID));

    // Extract the tag bits from a tagged value: (i64 tagged) -> i32 tag.
    // Used by builtins like `typeof` and `range` to branch on the tag.
    final var i64ToI32 = module.addType(new int[] {I64}, new int[] {I32});
    runtime.tag =
        emitHelper(
            i64ToI32,
            fn -> {
              fn.emitLocalGet(0);
              fn.emitI64Const(WasmRuntime.TAG_MASK);
              fn.emit(I64_AND);
              fn.emit(I32_WRAP_I64);
            });
  }

  // ========== Print helpers ==========

  /**
   * The C runtime already exports {@code safe_print_tagged} and {@code safe_println_tagged}, so the
   * print "helpers" here are nothing more than the imported indices captured during {@link
   * #importCBuiltins()}. The method is kept as a hook so future tracing or buffering can be
   * inserted without touching call sites.
   */
  private void emitPrintHelpers() {
    // intentionally empty — runtime.printTagged etc. are already populated
  }

  // ========== Wrapper emission ==========

  /**
   * Wrapper for {@code safe_*(i64 tagged) -> i32 pointer}: passes the tagged input straight
   * through, then re-tags the i32 result.
   */
  private int emitTaggedInPtrOut(final int target, final int resultTag) {
    return emitHelper(
        typeI64ToI64,
        fn -> {
          fn.emitLocalGet(0);
          fn.emitCall(target);
          WasmEmit.retagPointer(fn, resultTag);
        });
  }

  /**
   * Wrapper for {@code safe_*(i32 pointer) -> i32 pointer}: untags the incoming tagged pointer,
   * calls the C function, then re-tags the result.
   */
  private int emitPtrInPtrOut(final int target, final int resultTag) {
    return emitHelper(
        typeI64ToI64,
        fn -> {
          fn.emitLocalGet(0);
          fn.emitCall(runtime.untagPointer);
          fn.emitCall(target);
          WasmEmit.retagPointer(fn, resultTag);
        });
  }

  /**
   * Wrapper for {@code safe_*(i32 pointer) -> i32 length}: untags the input, calls the C function,
   * then tags the i32 result as an int (TAG_INT == 0, so the {@code retagPointer} call below
   * contributes only the shift).
   */
  private int emitPtrInIntOut(final int target) {
    return emitHelper(
        typeI64ToI64,
        fn -> {
          fn.emitLocalGet(0);
          fn.emitCall(runtime.untagPointer);
          fn.emitCall(target);
          WasmEmit.retagPointer(fn, WasmRuntime.TAG_INT);
        });
  }

  /** Wrapper for safe_str_concat: (tagged_str, tagged_str) -> tagged_str. */
  private int emitStringConcatWrap() {
    return emitHelper(
        typeI64I64ToI64,
        fn -> {
          fn.emitLocalGet(0);
          fn.emitCall(runtime.untagPointer);
          fn.emitLocalGet(1);
          fn.emitCall(runtime.untagPointer);
          fn.emitCall(runtime.strConcat);
          WasmEmit.retagPointer(fn, WasmRuntime.TAG_STRING);
        });
  }

  /** Wrapper for safe_list_new: () -> tagged_list (always starts empty). */
  private int emitListNewWrap() {
    return emitHelper(
        typeVoidToI64,
        fn -> {
          fn.emitI32Const(0); // initial capacity
          fn.emitCall(runtime.listNew);
          WasmEmit.retagPointer(fn, WasmRuntime.TAG_LIST);
        });
  }

  /** Wrapper for safe_list_get: (tagged_list, tagged_int) -> tagged_value. */
  private int emitListGetWrap() {
    return emitHelper(
        typeI64I64ToI64,
        fn -> {
          fn.emitLocalGet(0);
          fn.emitCall(runtime.untagPointer);
          fn.emitLocalGet(1);
          fn.emitCall(runtime.untagInt);
          fn.emit(I32_WRAP_I64);
          fn.emitCall(runtime.listGet);
          // result already tagged
        });
  }

  /** Wrapper for safe_list_append: (tagged_list, tagged_value) -> tagged_list. */
  private int emitListAppendWrap() {
    return emitHelper(
        typeI64I64ToI64,
        fn -> {
          fn.emitLocalGet(0);
          fn.emitCall(runtime.untagPointer);
          fn.emitLocalGet(1);
          fn.emitCall(runtime.listAppend);
          WasmEmit.retagPointer(fn, WasmRuntime.TAG_LIST);
        });
  }

  /** Wrapper for safe_list_remove_at: (tagged_list, tagged_int) -> tagged_list. */
  private int emitListRemoveAtWrap() {
    return emitHelper(
        typeI64I64ToI64,
        fn -> {
          fn.emitLocalGet(0);
          fn.emitCall(runtime.untagPointer);
          fn.emitLocalGet(1);
          fn.emitCall(runtime.untagInt);
          fn.emit(I32_WRAP_I64);
          fn.emitCall(runtime.listRemoveAt);
          WasmEmit.retagPointer(fn, WasmRuntime.TAG_LIST);
        });
  }

  /** Wrapper for safe_list_slice: (tagged_list, tagged_int, tagged_int) -> tagged_list. */
  private int emitListSliceWrap() {
    return emitHelper(
        typeI64I64I64ToI64,
        fn -> {
          fn.emitLocalGet(0);
          fn.emitCall(runtime.untagPointer);
          fn.emitLocalGet(1);
          fn.emitCall(runtime.untagInt);
          fn.emit(I32_WRAP_I64);
          fn.emitLocalGet(2);
          fn.emitCall(runtime.untagInt);
          fn.emit(I32_WRAP_I64);
          fn.emitCall(runtime.listSlice);
          WasmEmit.retagPointer(fn, WasmRuntime.TAG_LIST);
        });
  }

  /** Wrapper for safe_map_contains: (tagged_map, tagged_key) -> tagged_bool. */
  private int emitMapContainsWrap() {
    return emitHelper(
        typeI64I64ToI64,
        fn -> {
          fn.emitLocalGet(0);
          fn.emitCall(runtime.untagPointer);
          fn.emitLocalGet(1);
          fn.emitCall(runtime.mapContains);
          WasmEmit.retagBool(fn);
        });
  }

  /** Wrapper for safe_map_new: () -> tagged_map. */
  private int emitMapNewWrap() {
    return emitHelper(
        typeVoidToI64,
        fn -> {
          fn.emitCall(state.builtins.get("safe_map_new"));
          WasmEmit.retagPointer(fn, WasmRuntime.TAG_MAP);
        });
  }

  /** Wrapper for safe_map_put: (tagged_map, tagged_key, tagged_val) -> tagged_map. */
  private int emitMapPutWrap() {
    return emitHelper(
        typeI64I64I64ToI64,
        fn -> {
          fn.emitLocalGet(0);
          fn.emitCall(runtime.untagPointer);
          fn.emitLocalGet(1);
          fn.emitLocalGet(2);
          fn.emitCall(state.builtins.get("safe_map_put"));
          WasmEmit.retagPointer(fn, WasmRuntime.TAG_MAP);
        });
  }

  // ========== Helper-emission plumbing ==========

  private int emitHelper(final int type, final BodyEmitter body) {
    final var index = module.addFunction(type);
    final var function = new WasmFunction(index, type, paramCount(type));
    body.emit(function);
    module.addCode(index, function.encode(module));
    return index;
  }

  private int paramCount(final int type) {
    return module.type(type).params().length;
  }

  @FunctionalInterface
  private interface BodyEmitter {
    void emit(WasmFunction function);
  }
}
