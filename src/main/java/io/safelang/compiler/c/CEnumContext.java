package io.safelang.compiler.c;

import java.util.Set;

/**
 * Minimal context exposed to {@link CEnumGenerator} so it can mutate the recursive-enum set and
 * translate field types via {@link CTypeMapper}.
 */
interface CEnumContext {

  /** Mark a SAFE enum as self-referential (used for forward-declared pointer layout). */
  void markRecursive(String name);

  /** Mutable view of the recursive-enum set (so the generator can also read). */
  Set<String> recursive();

  /** Translate a SAFE type string to its C type. */
  String translate(String type);

  /**
   * True when the type is a heap-RC kind (list/map/set/bytes/recursive enum). Used by the enum
   * dispose generator to decide which fields of each variant need a safe_release at teardown.
   */
  boolean isHeapRc(String type);
}
