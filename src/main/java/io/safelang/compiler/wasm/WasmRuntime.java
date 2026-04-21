package io.safelang.compiler.wasm;

/**
 * Constants for the SAFE Wasm value representation and memory layout.
 *
 * <h2>Value Representation</h2>
 *
 * All SAFE values are represented as i64 "tagged values" on the Wasm stack:
 *
 * <ul>
 *   <li>Bits 0-3: type tag (0=int, 1=float, 2=bool, 3=string, 4=void, 5=list, 6=map, 7=set,
 *       8=tuple, 9=enum, 10=object, 11=closure, 12=bytes, 13=uint)
 *   <li>Bits 4-63: payload — either the value itself (ints, bools) or a 32-bit memory pointer
 *       (strings, collections, enums, etc.) left-shifted by 4
 * </ul>
 *
 * <h2>Memory Layout</h2>
 *
 * <pre>
 * 0x0000 - 0x00FF: Reserved (null guard)
 * 0x0100 - 0x01FF: WASI iovec scratch area
 * 0x0200 - 0x02FF: WASI nwritten / temp scratch
 * 0x0300 - 0x03FF: Print buffer (256 bytes for int/float→string conversion)
 * 0x0400 - data_end: Data section (string constants)
 * data_end+: Arena heap (bump-allocated, grows upward)
 * </pre>
 */
public final class WasmRuntime {

  // === Tag Constants ===
  public static final int TAG_INT = 0;
  public static final int TAG_FLOAT = 1;
  public static final int TAG_BOOL = 2;
  public static final int TAG_STRING = 3;
  public static final int TAG_VOID = 4;
  public static final int TAG_LIST = 5;
  public static final int TAG_MAP = 6;
  public static final int TAG_SET = 7;
  public static final int TAG_TUPLE = 8;
  public static final int TAG_ENUM = 9;
  public static final int TAG_OBJECT = 10;
  public static final int TAG_CLOSURE = 11;
  public static final int TAG_BYTES = 12;
  public static final int TAG_UINT = 13;
  public static final int TAG_BITS = 4;
  public static final long TAG_MASK = 0xFL;
  // Memory layout constants
  public static final int IOVEC_BASE = 0x0100;
  public static final int NWRITTEN_BASE = 0x0200;
  public static final int PRINT_BUF = 0x0300;
  public static final int PRINT_BUF_SIZE = 256;
  public static final int DATA_START = 0x0400;

  /** Header size (in bytes) for an enum variant: type id + variant idx + field count. */
  public static final int VARIANT_HEADER_SIZE = 12;

  // === Enum / struct heap layout ===
  //
  // Enum variants and structs share a common header layout when allocated on
  // the heap by the WASM backend:
  //
  //   offset 0  : i32 — type id (struct id for objects, enum id for variants)
  //   offset 4  : i32 — variant index (0 for structs)
  //   offset 8  : i32 — field count
  //   offset 12 : i64 — first field value
  //   offset 20 : i64 — second field value
  //   ...
  //
  // Struct objects use a slimmer header without the variant/field-count words.
  /** Offset of the first field within an enum variant. */
  public static final int VARIANT_FIELD_OFFSET = 12;

  /** Bytes occupied by a single enum / struct field on the heap. */
  public static final int FIELD_SLOT_SIZE = 8;

  /** Header size (in bytes) for a struct object: just the type-id word + padding. */
  public static final int STRUCT_HEADER_SIZE = 8;

  /** Offset of the first field within a struct object. */
  public static final int STRUCT_FIELD_OFFSET = 8;

  private WasmRuntime() {}
}
