package io.safelang.compiler.wasm;

/**
 * Typed key for all WASM backend symbol tables: (module, name) pair. Replaces bare string keys that
 * caused silent collisions when modules share enum variant names like Ok, Err, Some, None.
 */
public record SymbolKey(String module, String name) {

  /** Module name used for top-level program declarations. */
  static final String MAIN = "__main__";

  /** Module name used for builtin stubs. */
  static final String BUILTIN = "__builtin__";

  /** Canonical mangled name: module$name (matches existing name-mangling convention). */
  String canonical() {
    return module + "$" + name;
  }
}
