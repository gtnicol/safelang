package io.safelang.bytecode;

import java.util.Arrays;

/** Growable byte array for emitting bytecode instructions. */
public class BytecodeChunk {

  private byte[] data;
  private int size;

  public BytecodeChunk() {
    this.data = new byte[256];
    this.size = 0;
  }

  /** Current write position (next byte offset) */
  public int position() {
    return size;
  }

  /** Emit a single byte */
  public void emitByte(final int b) {
    ensure(1);
    data[size++] = (byte) b;
  }

  /** Emit an opcode (1 byte) */
  public void emitOpcode(final OpCode op) {
    emitByte(op.code());
  }

  /** Emit a 2-byte unsigned short (big-endian) */
  public void emitShort(final int value) {
    ensure(2);
    data[size++] = (byte) ((value >> 8) & 0xFF);
    data[size++] = (byte) (value & 0xFF);
  }

  /** Emit opcode + 2-byte operand */
  public void emitOpShort(final OpCode op, final int operand) {
    emitOpcode(op);
    emitShort(operand);
  }

  /** Emit a placeholder for a 2-byte jump offset, returns the offset position for patching */
  public int emitJumpPlaceholder(final OpCode opcode) {
    emitOpcode(opcode);
    final var position = size;
    emitShort(0); // placeholder
    return position;
  }

  /** Patch a 2-byte value at the given position (for jump targets) */
  public void patch(final int position, final int value) {
    data[position] = (byte) ((value >> 8) & 0xFF);
    data[position + 1] = (byte) (value & 0xFF);
  }

  /** Get the compiled bytecode as a byte array */
  public byte[] bytes() {
    return Arrays.copyOf(data, size);
  }

  /** Get size */
  public int size() {
    return size;
  }

  /** Append raw bytes from another chunk */
  public void append(final byte[] bytes) {
    ensure(bytes.length);
    System.arraycopy(bytes, 0, data, size, bytes.length);
    size += bytes.length;
  }

  private void ensure(final int additional) {
    if (size + additional > data.length) {
      data = Arrays.copyOf(data, Math.max(data.length * 2, size + additional));
    }
  }
}
