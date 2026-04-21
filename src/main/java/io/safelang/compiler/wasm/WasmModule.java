package io.safelang.compiler.wasm;

import static io.safelang.compiler.wasm.WasmOpcode.*;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Assembles a complete WebAssembly module from its constituent parts: types, imports, functions,
 * tables, memory, globals, exports, elements, code, and data.
 */
public final class WasmModule {

  private final List<FuncType> types = new ArrayList<>();
  private final List<Import> imports = new ArrayList<>();
  private final List<Integer> importedFunctionTypes = new ArrayList<>();
  // === Function section: type index for each function ===
  private final List<Integer> functions = new ArrayList<>();
  private final List<Global> globals = new ArrayList<>();
  private final List<Export> exports = new ArrayList<>();
  // === Element section (function table entries) ===
  private final List<Integer> elements = new ArrayList<>();
  // === Code section: encoded function bodies ===
  //
  // Bodies are stored keyed by the absolute function index they belong to,
  // not by emission order. This lets the compiler emit bodies in any order
  // (e.g. emitting the main entry point before the builtin stub bodies have
  // been filled in, or vice versa) while still producing a code section that
  // lines up with the function section as the WASM spec requires.
  private final Map<Integer, byte[]> codeByIndex = new LinkedHashMap<>();
  private final List<DataSegment> data = new ArrayList<>();
  private int imported = 0; // count of imported functions (offsets user function indices)
  // === Table section ===
  private int tableSize = 0;
  // === Memory section ===
  private int memoryMin = 1024; // 1024 pages = 64 MB
  private int memoryMax = -1; // -1 = no max
  private int elementBaseOffset = 0;
  // Table import support
  private String importTableModule;
  private String importTableName;
  private String importMemoryModule;
  private String importMemoryName;

  // ========== Type Section ==========

  private static boolean arraysEqual(final int[] a, final int[] b) {
    if (a.length != b.length) return false;
    for (var i = 0; i < a.length; i++) {
      if (a[i] != b[i]) return false;
    }
    return true;
  }

  /** Register a function type. Returns the type index. */
  public int addType(final int[] params, final int[] results) {
    // Deduplicate: check if an identical type already exists
    for (var i = 0; i < types.size(); i++) {
      final var existing = types.get(i);
      if (arraysEqual(existing.params(), params) && arraysEqual(existing.results(), results)) {
        return i;
      }
    }
    types.add(new FuncType(params, results));
    return types.size() - 1;
  }

  // ========== Import Section ==========

  /** Get the parameter count for a type by index. */
  public int paramCount(final int index) {
    return types.get(index).params().length;
  }

  /** Import a function. Returns the function index (imports come before local functions). */
  public int importFunction(final String module, final String name, final int type) {
    final var index = imported;
    imports.add(new Import(module, name, EXPORT_FUNC, type));
    importedFunctionTypes.add(type);
    imported++;
    return index;
  }

  // ========== Function Section ==========

  /** Number of imported functions (offsets local function indices). */
  public int imported() {
    return imported;
  }

  /** Add a local function with the given type. Returns its absolute function index. */
  public int addFunction(final int type) {
    functions.add(type);
    return imported + functions.size() - 1;
  }

  /** Get a function type by type index. */
  public FuncType type(final int index) {
    return types.get(index);
  }

  // ========== Table Section ==========

  /** Get a function signature by absolute function index. */
  public FuncType functionType(final int index) {
    if (index < imported) {
      return types.get(importedFunctionTypes.get(index));
    }
    return types.get(functions.get(index - imported));
  }

  /** Set the funcref table size. */
  public void setTableSize(final int size) {
    this.tableSize = size;
  }

  /** Add a funcref table of the given size. Alias for {@link #setTableSize(int)}. */
  public void addTable(final int size) {
    this.tableSize = size;
  }

  /** Add a function reference to the element section at a specific table index. */
  public void addElement(final int offset, final int function) {
    if (elements.isEmpty()) {
      elementBaseOffset = offset; // first element sets the base
    }
    final var relative = offset - elementBaseOffset;
    while (elements.size() <= relative) elements.add(0);
    elements.set(relative, function);
  }

  /** Import a function table from another module. */
  public void importTable(final String module, final String name) {
    this.importTableModule = module;
    this.importTableName = name;
  }

  /** Export the function table. */
  public void exportTable(final String name) {
    exports.add(new Export(name, EXPORT_TABLE, 0));
  }

  /** Import memory from another module instead of defining our own. */
  public void importMemory(final String module, final String name) {
    this.importMemoryModule = module;
    this.importMemoryName = name;
  }

  public void setMemory(final int min, final int max) {
    this.memoryMin = min;
    this.memoryMax = max;
  }

  // ========== Memory Section ==========

  /** Add a global variable. Returns its index. */
  public int addGlobal(final int type, final boolean mutable, final long initial) {
    globals.add(new Global(type, mutable, initial));
    return globals.size() - 1;
  }

  /** Get a global variable type by global index. */
  public int globalType(final int index) {
    return globals.get(index).type();
  }

  /** Patch a global's initial value. */
  public void patchGlobal(final int index, final long value) {
    final var existing = globals.get(index);
    globals.set(index, new Global(existing.type(), existing.mutable(), value));
  }

  public void exportFunction(final String name, final int index) {
    exports.add(new Export(name, EXPORT_FUNC, index));
  }

  // ========== Global Section ==========

  public void exportMemory(final String name, final int index) {
    exports.add(new Export(name, EXPORT_MEMORY, index));
  }

  public void exportGlobal(final String name, final int index) {
    exports.add(new Export(name, EXPORT_GLOBAL, index));
  }

  /** Add a function reference to the element section (populates the table). */
  public void addElement(final int index) {
    elements.add(index);
  }

  // ========== Export Section ==========

  /**
   * Add an encoded function body (from {@link WasmFunction#encode}). The body is recorded against
   * the function's absolute index so the code section lines up with the function section regardless
   * of emission order.
   */
  public void addCode(final int functionIndex, final byte[] encoded) {
    codeByIndex.put(functionIndex, encoded);
  }

  /**
   * Legacy overload — retained temporarily for callers that pre-date the index-keyed model. Stores
   * the body keyed by its position in insertion order; callers using this form must still emit in
   * function-index order.
   */
  public void addCode(final byte[] encoded) {
    codeByIndex.put(imported + codeByIndex.size(), encoded);
  }

  /** Add a data segment at a fixed memory offset. Returns the offset. */
  public int addData(final int offset, final byte[] content) {
    data.add(new DataSegment(offset, content));
    return offset;
  }

  // ========== Element Section ==========

  /** Add a data segment at a fixed memory offset from a string. Returns the offset. */
  public int addData(final int offset, final String content) {
    return addData(offset, content.getBytes(StandardCharsets.UTF_8));
  }

  // ========== Code Section ==========

  /** Assemble the complete .wasm binary. */
  public byte[] assemble() {
    final var output = new WasmBinaryWriter();
    output.writeHeader();

    // Section 1: Type
    if (!types.isEmpty()) {
      output.writeSection(SECTION_TYPE, encodeTypeSection());
    }

    // Section 2: Import (includes memory import if present)
    if (!imports.isEmpty() || importMemoryModule != null) {
      output.writeSection(SECTION_IMPORT, encodeImportSection());
    }

    // Section 3: Function
    if (!functions.isEmpty()) {
      output.writeSection(SECTION_FUNCTION, encodeFunctionSection());
    }

    // Section 4: Table (skip if imported)
    if (tableSize > 0 && importTableModule == null) {
      output.writeSection(SECTION_TABLE, encodeTableSection());
    }

    // Section 5: Memory (skip if imported)
    if (importMemoryModule == null) {
      output.writeSection(SECTION_MEMORY, encodeMemorySection());
    }

    // Section 6: Global
    if (!globals.isEmpty()) {
      output.writeSection(SECTION_GLOBAL, encodeGlobalSection());
    }

    // Section 7: Export
    if (!exports.isEmpty()) {
      output.writeSection(SECTION_EXPORT, encodeExportSection());
    }

    // Section 9: Element
    if (!elements.isEmpty() && (tableSize > 0 || importTableModule != null)) {
      output.writeSection(SECTION_ELEMENT, encodeElementSection());
    }

    // Section 10: Code
    if (!functions.isEmpty()) {
      output.writeSection(SECTION_CODE, encodeCodeSection());
    }

    // Section 11: Data
    if (!data.isEmpty()) {
      output.writeSection(SECTION_DATA, encodeDataSection());
    }

    return output.toByteArray();
  }

  private byte[] encodeTypeSection() {
    final var writer = new WasmBinaryWriter();
    writer.writeULEB128(types.size());
    for (final var type : types) {
      writer.writeByte(0x60); // func type marker
      writer.writeULEB128(type.params().length);
      for (final var param : type.params()) {
        writer.writeByte(param);
      }
      writer.writeULEB128(type.results().length);
      for (final var result : type.results()) {
        writer.writeByte(result);
      }
    }
    return writer.toByteArray();
  }

  // ========== Data Section ==========

  private byte[] encodeImportSection() {
    final var writer = new WasmBinaryWriter();
    var count = imports.size();
    if (importMemoryModule != null) count++;
    if (importTableModule != null) count++;
    writer.writeULEB128(count);
    // Memory import first
    if (importMemoryModule != null) {
      writer.writeName(importMemoryModule);
      writer.writeName(importMemoryName);
      writer.writeByte(EXPORT_MEMORY);
      writer.writeByte(LIMITS_NO_MAX);
      writer.writeULEB128(memoryMin);
    }
    // Table import (funcref table)
    if (importTableModule != null) {
      writer.writeName(importTableModule);
      writer.writeName(importTableName);
      writer.writeByte(EXPORT_TABLE);
      writer.writeByte(TYPE_FUNCREF);
      writer.writeByte(LIMITS_NO_MAX);
      writer.writeULEB128(0); // min = 0
    }
    // Function imports
    for (final var entry : imports) {
      writer.writeName(entry.module());
      writer.writeName(entry.name());
      writer.writeByte(entry.kind());
      if (entry.kind() == EXPORT_GLOBAL) {
        // Global: val_type + mutability
        writer.writeByte(entry.index()); // reuse index field as val_type
        writer.writeByte(0x01); // mutable
      } else {
        writer.writeULEB128(entry.index()); // type index for functions
      }
    }
    return writer.toByteArray();
  }

  private byte[] encodeFunctionSection() {
    final var writer = new WasmBinaryWriter();
    writer.writeULEB128(functions.size());
    for (final var type : functions) {
      writer.writeULEB128(type);
    }
    return writer.toByteArray();
  }

  // ========== Module Assembly ==========

  private byte[] encodeTableSection() {
    final var writer = new WasmBinaryWriter();
    writer.writeULEB128(1); // 1 table
    writer.writeByte(TYPE_FUNCREF);
    writer.writeByte(LIMITS_NO_MAX);
    writer.writeULEB128(tableSize);
    return writer.toByteArray();
  }

  // ========== Section Encoders ==========

  private byte[] encodeMemorySection() {
    final var writer = new WasmBinaryWriter();
    writer.writeULEB128(1); // 1 memory
    if (memoryMax < 0) {
      writer.writeByte(LIMITS_NO_MAX);
      writer.writeULEB128(memoryMin);
    } else {
      writer.writeByte(LIMITS_WITH_MAX);
      writer.writeULEB128(memoryMin);
      writer.writeULEB128(memoryMax);
    }
    return writer.toByteArray();
  }

  private byte[] encodeGlobalSection() {
    final var writer = new WasmBinaryWriter();
    writer.writeULEB128(globals.size());
    for (final var global : globals) {
      writer.writeByte(global.type());
      writer.writeByte(global.mutable() ? GLOBAL_MUT : GLOBAL_CONST);
      // Init expression
      switch (global.type()) {
        case TYPE_I32 -> {
          writer.writeByte(I32_CONST);
          writer.writeSLEB128((int) global.initial());
        }
        case TYPE_I64 -> {
          writer.writeByte(I64_CONST);
          writer.writeSLEB128(global.initial());
        }
        case TYPE_F64 -> {
          writer.writeByte(F64_CONST);
          writer.writeF64(Double.longBitsToDouble(global.initial()));
        }
        default -> {
          writer.writeByte(I32_CONST);
          writer.writeSLEB128((int) global.initial());
        }
      }
      writer.writeByte(END);
    }
    return writer.toByteArray();
  }

  private byte[] encodeExportSection() {
    final var writer = new WasmBinaryWriter();
    writer.writeULEB128(exports.size());
    for (final var export : exports) {
      writer.writeName(export.name());
      writer.writeByte(export.kind());
      writer.writeULEB128(export.index());
    }
    return writer.toByteArray();
  }

  private byte[] encodeElementSection() {
    final var writer = new WasmBinaryWriter();
    writer.writeULEB128(1); // 1 element segment
    // Active element segment for table 0 at base offset
    writer.writeULEB128(0); // flags: active, table 0, elem kind funcref
    // Offset expression: i32.const elementBaseOffset
    writer.writeByte(I32_CONST);
    writer.writeSLEB128(elementBaseOffset);
    writer.writeByte(END);
    // Function indices
    writer.writeULEB128(elements.size());
    for (final var index : elements) {
      writer.writeULEB128(index);
    }
    return writer.toByteArray();
  }

  private byte[] encodeCodeSection() {
    final var writer = new WasmBinaryWriter();
    writer.writeULEB128(functions.size());
    for (var i = 0; i < functions.size(); i++) {
      final var index = imported + i;
      final var encoded = codeByIndex.get(index);
      if (encoded == null) {
        throw new IllegalStateException("WASM: missing body for local function " + index);
      }
      writer.writeBytes(encoded);
    }
    return writer.toByteArray();
  }

  private byte[] encodeDataSection() {
    final var writer = new WasmBinaryWriter();
    writer.writeULEB128(data.size());
    for (final var segment : data) {
      writer.writeULEB128(0); // flags: active, memory 0
      // Offset expression
      writer.writeByte(I32_CONST);
      writer.writeSLEB128(segment.offset());
      writer.writeByte(END);
      // Data bytes
      writer.writeVector(segment.data());
    }
    return writer.toByteArray();
  }

  // === Type section entries ===
  public record FuncType(int[] params, int[] results) {}

  // === Import section entries ===
  public record Import(String module, String name, int kind, int index) {}

  // === Global section ===
  public record Global(int type, boolean mutable, long initial) {}

  // === Export section ===
  public record Export(String name, int kind, int index) {}

  // === Data section ===
  public record DataSegment(int offset, byte[] data) {}
}
