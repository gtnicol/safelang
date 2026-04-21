package io.safelang.compiler.wasm;

import io.safelang.compiler.CompilerException;
import java.util.ArrayList;
import java.util.List;

/**
 * Builds a single Wasm function body: declares locals and accumulates instructions. The encoded
 * body is written to a WasmBinaryWriter for inclusion in the code section.
 */
public final class WasmFunction {

  private final int index;
  private final int type;
  private final int params;
  private final List<Local> locals = new ArrayList<>();
  // The instruction stream is the single source of truth for the function
  // body; bytes are derived from it lazily by encode().
  private final List<WasmInstruction> instructions = new ArrayList<>();

  public WasmFunction(final int index, final int type) {
    this(index, type, 0);
  }

  public WasmFunction(final int index, final int type, final int params) {
    this.index = index;
    this.type = type;
    this.params = params;
  }

  private static void encodeInstruction(
      final WasmBinaryWriter writer, final WasmInstruction instruction) {
    switch (instruction) {
      case WasmInstruction.Simple op -> writer.writeByte(op.opcode());
      case WasmInstruction.IntConst op -> {
        writer.writeByte(WasmOpcode.I32_CONST);
        writer.writeSLEB128(op.value());
      }
      case WasmInstruction.LongConst op -> {
        writer.writeByte(WasmOpcode.I64_CONST);
        writer.writeSLEB128(op.value());
      }
      case WasmInstruction.FloatConst op -> {
        writer.writeByte(WasmOpcode.F64_CONST);
        writer.writeF64(op.value());
      }
      case WasmInstruction.Indexed op -> {
        writer.writeByte(op.opcode());
        writer.writeULEB128(op.index());
      }
      case WasmInstruction.Block op -> {
        writer.writeByte(op.opcode());
        writer.writeByte(op.type());
      }
      case WasmInstruction.Memory op -> {
        writer.writeByte(op.opcode());
        if (op.opcode() == WasmOpcode.MEMORY_SIZE || op.opcode() == WasmOpcode.MEMORY_GROW) {
          writer.writeByte(0x00);
        } else {
          writer.writeULEB128(op.align());
          writer.writeULEB128(op.offset());
        }
      }
      case WasmInstruction.CallIndirect op -> {
        writer.writeByte(WasmOpcode.CALL_INDIRECT);
        writer.writeULEB128(op.type());
        writer.writeULEB128(op.table());
      }
    }
  }

  public int index() {
    return index;
  }

  public int type() {
    return type;
  }

  /**
   * Add a block of locals with the same Wasm type. Returns the starting local index
   * (params-adjusted).
   */
  public int addLocals(final int count, final int type) {
    final var start = params + localCount();
    locals.add(new Local(count, type));
    return start;
  }

  /** Add a single local. Returns its index (params-adjusted). */
  public int addLocal(final int type) {
    final var index = params + localCount();
    locals.add(new Local(1, type));
    return index;
  }

  /** Total number of locals declared so far (not counting params). */
  private int localCount() {
    var total = 0;
    for (final var local : locals) {
      total += local.count();
    }
    return total;
  }

  // === Instruction emission ===
  //
  // Each method records a typed WasmInstruction; the bytes are produced
  // later by encode() walking the same list.

  public void emit(final int opcode) {
    instructions.add(new WasmInstruction.Simple(opcode));
  }

  public void emitI32Const(final int value) {
    instructions.add(new WasmInstruction.IntConst(value));
  }

  public void emitI64Const(final long value) {
    instructions.add(new WasmInstruction.LongConst(value));
  }

  public void emitF64Const(final double value) {
    instructions.add(new WasmInstruction.FloatConst(value));
  }

  public void emitLocalGet(final int index) {
    instructions.add(new WasmInstruction.Indexed(WasmOpcode.LOCAL_GET, index));
  }

  public void emitLocalSet(final int index) {
    instructions.add(new WasmInstruction.Indexed(WasmOpcode.LOCAL_SET, index));
  }

  public void emitLocalTee(final int index) {
    instructions.add(new WasmInstruction.Indexed(WasmOpcode.LOCAL_TEE, index));
  }

  public void emitGlobalGet(final int index) {
    instructions.add(new WasmInstruction.Indexed(WasmOpcode.GLOBAL_GET, index));
  }

  public void emitGlobalSet(final int index) {
    instructions.add(new WasmInstruction.Indexed(WasmOpcode.GLOBAL_SET, index));
  }

  public void emitCall(final int index) {
    instructions.add(new WasmInstruction.Indexed(WasmOpcode.CALL, index));
  }

  public void emitCallIndirect(final int type, final int table) {
    instructions.add(new WasmInstruction.CallIndirect(type, table));
  }

  public void emitBlock(final int type) {
    instructions.add(new WasmInstruction.Block(WasmOpcode.BLOCK, type));
  }

  public void emitLoop(final int type) {
    instructions.add(new WasmInstruction.Block(WasmOpcode.LOOP, type));
  }

  public void emitIf(final int type) {
    instructions.add(new WasmInstruction.Block(WasmOpcode.IF, type));
  }

  public void emitBr(final int depth) {
    instructions.add(new WasmInstruction.Indexed(WasmOpcode.BR, depth));
  }

  public void emitBrIf(final int depth) {
    instructions.add(new WasmInstruction.Indexed(WasmOpcode.BR_IF, depth));
  }

  /** Emit a memory load: opcode + alignment + offset. */
  public void emitLoad(final int opcode, final int align, final int offset) {
    instructions.add(new WasmInstruction.Memory(opcode, align, offset));
  }

  /** Emit a memory store: opcode + alignment + offset. */
  public void emitStore(final int opcode, final int align, final int offset) {
    instructions.add(new WasmInstruction.Memory(opcode, align, offset));
  }

  /** Emit memory.size (memory index 0). */
  public void emitMemorySize() {
    instructions.add(new WasmInstruction.Simple(WasmOpcode.MEMORY_SIZE));
  }

  /** Emit memory.grow (memory index 0). */
  public void emitMemoryGrow() {
    instructions.add(new WasmInstruction.Simple(WasmOpcode.MEMORY_GROW));
  }

  /**
   * Encode the complete function body for the code section. Format: ULEB128(body_size) +
   * ULEB128(local_decl_count) + local_decls + instructions + END
   */
  public byte[] encode(final WasmModule module) {
    validate(module);

    final var encoded = new WasmBinaryWriter();

    // Local declarations
    encoded.writeULEB128(locals.size());
    for (final var local : locals) {
      encoded.writeULEB128(local.count());
      encoded.writeByte(local.type());
    }

    // Instructions — bytes are derived from the typed list at encode time
    // so the in-memory representation has a single source of truth.
    for (final var instruction : instructions) {
      encodeInstruction(encoded, instruction);
    }

    // END opcode
    encoded.writeByte(WasmOpcode.END);

    // Wrap with byte length prefix
    final var result = new WasmBinaryWriter();
    result.writeVector(encoded.toByteArray());
    return result.toByteArray();
  }

  /**
   * Type-check this function body before encoding. Delegates to {@link WasmStackValidator}; the
   * only logic kept here is the param-count sanity check, since that's what {@link WasmFunction}
   * owns.
   */
  private void validate(final WasmModule module) {
    final var signature = module.type(type);
    if (signature.params().length != params) {
      throw new CompilerException(
          "WASM stack validation failed in function "
              + index
              + ": "
              + "function signature declares "
              + signature.params().length
              + " params, emitter registered "
              + params);
    }

    final var localTypes = new int[params + localCount()];
    System.arraycopy(signature.params(), 0, localTypes, 0, signature.params().length);
    var cursor = params;
    for (final var local : locals) {
      for (var i = 0; i < local.count(); i++) {
        localTypes[cursor++] = local.type();
      }
    }

    new WasmStackValidator(module, index, signature.results(), localTypes).validate(instructions);
  }

  /** A local variable declaration: count of locals with the same type. */
  public record Local(int count, int type) {}
}
