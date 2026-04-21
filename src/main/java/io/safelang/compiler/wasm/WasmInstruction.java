package io.safelang.compiler.wasm;

/**
 * In-memory representation of a single WASM instruction recorded by {@link WasmFunction} during
 * emission.
 *
 * <p>The shape carries enough information for {@link WasmStackValidator} to type-check the
 * instruction stream without re-decoding raw bytes. Each subtype matches one operand layout the
 * WASM binary spec defines.
 */
sealed interface WasmInstruction
    permits WasmInstruction.Simple,
        WasmInstruction.IntConst,
        WasmInstruction.LongConst,
        WasmInstruction.FloatConst,
        WasmInstruction.Indexed,
        WasmInstruction.Block,
        WasmInstruction.Memory,
        WasmInstruction.CallIndirect {

  /** Operations whose entire encoding is just the opcode byte. */
  record Simple(int opcode) implements WasmInstruction {}

  /** {@code i32.const} — opcode + signed LEB128 value. */
  record IntConst(int value) implements WasmInstruction {}

  /** {@code i64.const} — opcode + signed LEB128 value. */
  record LongConst(long value) implements WasmInstruction {}

  /** {@code f64.const} — opcode + raw 8-byte little-endian double. */
  record FloatConst(double value) implements WasmInstruction {}

  /** Locals/globals/branches and direct calls — opcode + ULEB128 index. */
  record Indexed(int opcode, int index) implements WasmInstruction {}

  /** {@code block}, {@code loop}, {@code if} — opcode + 1-byte block type. */
  record Block(int opcode, int type) implements WasmInstruction {}

  /** Loads and stores — opcode + ULEB128 alignment + ULEB128 offset. */
  record Memory(int opcode, int align, int offset) implements WasmInstruction {}

  /** {@code call_indirect} — opcode + type index + table index. */
  record CallIndirect(int type, int table) implements WasmInstruction {}
}
