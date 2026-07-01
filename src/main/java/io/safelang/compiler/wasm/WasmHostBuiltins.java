package io.safelang.compiler.wasm;

import static io.safelang.compiler.wasm.WasmOpcode.TYPE_F64;
import static io.safelang.compiler.wasm.WasmOpcode.TYPE_I32;
import static io.safelang.compiler.wasm.WasmOpcode.TYPE_I64;

/**
 * Declarative table of every C builtin imported from the {@code safe_wasm_builtins.wasm} preload
 * module.
 *
 * <p>Each entry is a {@link Signature} record describing the symbol's name and its WASM function
 * type. {@link WasmRuntimeBuilder} walks {@link #ALL} once during module setup and adds an import
 * for every entry, then exposes the resolved indices via {@link WasmCompilationState#builtins} and
 * {@link WasmRuntimeContext}.
 *
 * <p>This file is intentionally pure data — keeping it separate keeps the runtime builder focused
 * on the wrapper-emission logic and makes the WASM ABI surface easy to read at a glance.
 */
final class WasmHostBuiltins {

  // Convenience aliases for the WASM scalar types so the table below stays
  // skinny enough to scan in one column.
  private static final int I32 = TYPE_I32;
  private static final int I64 = TYPE_I64;
  private static final int F64 = TYPE_F64;
  static final Signature[] ALL = {
    // Heap / allocator
    new Signature("safe_set_heap", new int[] {I32}, new int[] {}),
    new Signature("safe_alloc", new int[] {I32}, new int[] {I32}),
    new Signature("safe_heap_report", new int[] {}, new int[] {}),

    // Refcounting
    new Signature("safe_rc_alloc", new int[] {I32, I32, I32}, new int[] {I32}),
    new Signature("safe_rc_retain_tagged", new int[] {I64}, new int[] {I64}),
    new Signature("safe_rc_release_tagged", new int[] {I64}, new int[] {}),
    new Signature("safe_rc_mark_immortal", new int[] {I32}, new int[] {}),
    new Signature("safe_collect_cycles", new int[] {}, new int[] {}),

    // Print
    new Signature("safe_print_str", new int[] {I32}, new int[] {}),
    new Signature("safe_print_tagged", new int[] {I64}, new int[] {}),
    new Signature("safe_println_tagged", new int[] {I64}, new int[] {}),

    // Contract failure trap (used by emitFunctionBody for requires/ensures/decreases)
    new Signature("safe_trap_with_message", new int[] {I32}, new int[] {}),

    // String runtime
    new Signature("safe_str_len", new int[] {I32}, new int[] {I32}),
    new Signature("safe_str_eq", new int[] {I32, I32}, new int[] {I32}),
    new Signature("safe_str_concat", new int[] {I32, I32}, new int[] {I32}),
    new Signature("safe_str_from_int", new int[] {I64}, new int[] {I32}),
    new Signature("safe_str_from_uint", new int[] {I64}, new int[] {I32}),
    new Signature("safe_str_from_bool", new int[] {I32}, new int[] {I32}),
    new Signature("safe_str_from_float", new int[] {F64}, new int[] {I32}),
    new Signature("safe_to_string", new int[] {I64}, new int[] {I32}),
    new Signature("safe_values_eq", new int[] {I64, I64}, new int[] {I32}),

    // String operations
    new Signature("safe_str_substring", new int[] {I32, I32, I32}, new int[] {I32}),
    new Signature("safe_str_indexof", new int[] {I32, I32}, new int[] {I32}),
    new Signature("safe_str_charat", new int[] {I32, I32}, new int[] {I32}),
    new Signature("safe_str_split", new int[] {I32, I32}, new int[] {I32}),
    new Signature("safe_str_chars", new int[] {I32}, new int[] {I32}),
    new Signature("safe_str_upper", new int[] {I32}, new int[] {I32}),
    new Signature("safe_str_lower", new int[] {I32}, new int[] {I32}),
    new Signature("safe_str_trim", new int[] {I32}, new int[] {I32}),
    new Signature("safe_str_replace", new int[] {I32, I32, I32}, new int[] {I32}),
    new Signature("safe_str_starts", new int[] {I32, I32}, new int[] {I32}),
    new Signature("safe_str_ends", new int[] {I32, I32}, new int[] {I32}),
    new Signature("safe_str_repeat", new int[] {I32, I32}, new int[] {I32}),
    new Signature("safe_str_reversed", new int[] {I32}, new int[] {I32}),
    new Signature("safe_str_to_int", new int[] {I32}, new int[] {I64}),
    new Signature("safe_str_to_float", new int[] {I32}, new int[] {F64}),

    // List runtime
    new Signature("safe_list_new", new int[] {I32}, new int[] {I32}),
    new Signature("safe_list_append", new int[] {I32, I64}, new int[] {I32}),
    new Signature("safe_list_get", new int[] {I32, I32}, new int[] {I64}),
    new Signature("safe_list_set", new int[] {I32, I32, I64}, new int[] {}),
    new Signature("safe_list_len", new int[] {I32}, new int[] {I32}),
    new Signature("safe_list_remove_at", new int[] {I32, I32}, new int[] {I32}),
    new Signature("safe_list_slice", new int[] {I32, I32, I32}, new int[] {I32}),
    new Signature("safe_list_concat", new int[] {I32, I32}, new int[] {I32}),
    new Signature("safe_list_reverse", new int[] {I32}, new int[] {I32}),
    new Signature("safe_list_contains", new int[] {I32, I64}, new int[] {I32}),
    new Signature("safe_list_sort", new int[] {I32}, new int[] {I32}),
    new Signature("safe_list_unique", new int[] {I32}, new int[] {I32}),

    // Map runtime
    new Signature("safe_map_new", new int[] {}, new int[] {I32}),
    new Signature("safe_map_put", new int[] {I32, I64, I64}, new int[] {I32}),
    new Signature("safe_map_get", new int[] {I32, I64}, new int[] {I64}),
    new Signature("safe_map_len", new int[] {I32}, new int[] {I32}),
    new Signature("safe_map_contains", new int[] {I32, I64}, new int[] {I32}),
    new Signature("safe_map_keys", new int[] {I32}, new int[] {I32}),
    new Signature("safe_map_values", new int[] {I32}, new int[] {I32}),
    new Signature("safe_map_remove", new int[] {I32, I64}, new int[] {I32}),

    // Set runtime
    new Signature("safe_set_add", new int[] {I32, I64}, new int[] {I32}),
    new Signature("safe_set_union", new int[] {I32, I32}, new int[] {I32}),
    new Signature("safe_set_intersect", new int[] {I32, I32}, new int[] {I32}),
    new Signature("safe_set_difference", new int[] {I32, I32}, new int[] {I32}),

    // Math
    new Signature("safe_sin", new int[] {F64}, new int[] {F64}),
    new Signature("safe_cos", new int[] {F64}, new int[] {F64}),
    new Signature("safe_tan", new int[] {F64}, new int[] {F64}),
    new Signature("safe_asin", new int[] {F64}, new int[] {F64}),
    new Signature("safe_acos", new int[] {F64}, new int[] {F64}),
    new Signature("safe_atan", new int[] {F64}, new int[] {F64}),
    new Signature("safe_atan2", new int[] {F64, F64}, new int[] {F64}),
    new Signature("safe_exp", new int[] {F64}, new int[] {F64}),
    new Signature("safe_log", new int[] {F64}, new int[] {F64}),
    new Signature("safe_log10", new int[] {F64}, new int[] {F64}),
    new Signature("safe_pow", new int[] {F64, F64}, new int[] {F64}),

    // Random / time
    new Signature("safe_seed", new int[] {I64}, new int[] {}),
    new Signature("safe_rand", new int[] {}, new int[] {F64}),
    new Signature("safe_randint", new int[] {I64, I64}, new int[] {I64}),
    new Signature("safe_time", new int[] {}, new int[] {I64}),

    // Hashing
    new Signature("safe_fnv", new int[] {I32}, new int[] {I64}),
    new Signature("safe_crc32", new int[] {I32}, new int[] {I64}),
    new Signature("safe_murmur", new int[] {I32}, new int[] {I64}),

    // Regex
    new Signature("safe_regex_matches", new int[] {I32, I32}, new int[] {I32}),
    new Signature("safe_regex_findall", new int[] {I32, I32}, new int[] {I32}),
    new Signature("safe_regex_replaceall", new int[] {I32, I32, I32}, new int[] {I32}),

    // Args / env / input
    new Signature("safe_args", new int[] {}, new int[] {I32}),
    new Signature("safe_input", new int[] {I32}, new int[] {I32}),
    new Signature("safe_getenv", new int[] {I32}, new int[] {I32}),

    // File ops (note: filesave/fileappend/fileclose return void)
    new Signature("safe_fileload", new int[] {I32}, new int[] {I32}),
    new Signature("safe_filesave", new int[] {I32, I32}, new int[] {}),
    new Signature("safe_fileappend", new int[] {I32, I32}, new int[] {}),
    new Signature("safe_filereadlines", new int[] {I32}, new int[] {I32}),
    new Signature("safe_fileexists", new int[] {I32}, new int[] {I32}),
    new Signature("safe_filedelete", new int[] {I32}, new int[] {I32}),
    new Signature("safe_fileopen", new int[] {I32, I32}, new int[] {I32}),
    new Signature("safe_fileclose", new int[] {I32}, new int[] {}),
    new Signature("safe_fileread", new int[] {I32}, new int[] {I32}),
    new Signature("safe_filewrite", new int[] {I32, I32}, new int[] {I32}),
    new Signature("safe_filevalid", new int[] {I32}, new int[] {I32}),
    new Signature("safe_mkdir", new int[] {I32}, new int[] {I32}),
    new Signature("safe_rmdir", new int[] {I32}, new int[] {I32}),
    new Signature("safe_isdir", new int[] {I32}, new int[] {I32}),
    new Signature("safe_listdir", new int[] {I32}, new int[] {I32}),

    // Binary / bytes
    new Signature("safe_balloc", new int[] {I32}, new int[] {I32}),
    new Signature("safe_blen", new int[] {I32}, new int[] {I32}),
    new Signature("safe_bget", new int[] {I32, I32}, new int[] {I32}),
    new Signature("safe_bset", new int[] {I32, I32, I32}, new int[] {I32}),
    new Signature("safe_bslice", new int[] {I32, I32, I32}, new int[] {I32}),
    new Signature("safe_bconcat", new int[] {I32, I32}, new int[] {I32}),
    new Signature("safe_bencode", new int[] {I32}, new int[] {I32}),
    new Signature("safe_bdecode", new int[] {I32}, new int[] {I32}),
    new Signature("safe_bpack", new int[] {I64, I32}, new int[] {I32}),
    new Signature("safe_bunpack", new int[] {I32, I32, I32}, new int[] {I64}),
    new Signature("safe_bhex", new int[] {I32}, new int[] {I32}),
    new Signature("safe_bcompare", new int[] {I32, I32}, new int[] {I32}),
    new Signature("safe_bpatch", new int[] {I32, I32, I32}, new int[] {I32}),

    // Binary file I/O
    new Signature("safe_bopen", new int[] {I32, I32}, new int[] {I32}),
    new Signature("safe_bclose", new int[] {I32}, new int[] {}),
    new Signature("safe_bread", new int[] {I32, I32}, new int[] {I32}),
    new Signature("safe_bwrite", new int[] {I32, I32}, new int[] {}),
    new Signature("safe_bseek", new int[] {I32, I64}, new int[] {}),
    new Signature("safe_bsize", new int[] {I32}, new int[] {I64}),
    new Signature("safe_bflush", new int[] {I32}, new int[] {}),

    // Char / ordinal
    new Signature("safe_ordinal", new int[] {I32}, new int[] {I32}),
    new Signature("safe_charcode", new int[] {I32}, new int[] {I32}),
  };

  private WasmHostBuiltins() {}

  /** Name + function type of one C builtin imported from {@code builtins.wasm}. */
  record Signature(String name, int[] params, int[] results) {}
}
