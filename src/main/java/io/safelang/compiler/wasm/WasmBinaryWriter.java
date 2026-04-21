package io.safelang.compiler.wasm;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Low-level writer for WebAssembly binary format. Handles LEB128 encoding, sections, and raw bytes.
 */
public final class WasmBinaryWriter {

  private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();

  public void writeByte(final int value) {
    buffer.write(value & 0xFF);
  }

  public void writeBytes(final byte[] data) {
    buffer.write(data, 0, data.length);
  }

  public void writeBytes(final byte[] data, final int offset, final int length) {
    buffer.write(data, offset, length);
  }

  /** Write an unsigned LEB128-encoded integer. */
  public void writeULEB128(int value) {
    do {
      var b = value & 0x7F;
      value >>>= 7;
      if (value != 0) b |= 0x80;
      buffer.write(b);
    } while (value != 0);
  }

  /** Write a signed LEB128-encoded 32-bit integer. */
  public void writeSLEB128(int value) {
    var more = true;
    while (more) {
      var b = value & 0x7F;
      value >>= 7;
      if ((value == 0 && (b & 0x40) == 0) || (value == -1 && (b & 0x40) != 0)) {
        more = false;
      } else {
        b |= 0x80;
      }
      buffer.write(b);
    }
  }

  /** Write a signed LEB128-encoded 64-bit integer. */
  public void writeSLEB128(long value) {
    var more = true;
    while (more) {
      var b = (int) (value & 0x7F);
      value >>= 7;
      if ((value == 0 && (b & 0x40) == 0) || (value == -1 && (b & 0x40) != 0)) {
        more = false;
      } else {
        b |= 0x80;
      }
      buffer.write(b);
    }
  }

  /** Write a 64-bit float in little-endian IEEE 754 encoding. */
  public void writeF64(final double value) {
    final var bits = Double.doubleToRawLongBits(value);
    for (var i = 0; i < 8; i++) {
      buffer.write((int) ((bits >>> (i * 8)) & 0xFF));
    }
  }

  /** Write a UTF-8 string prefixed by its byte length (ULEB128). */
  public void writeName(final String name) {
    final var encoded = name.getBytes(StandardCharsets.UTF_8);
    writeULEB128(encoded.length);
    writeBytes(encoded);
  }

  /** Write the Wasm magic number and version. */
  public void writeHeader() {
    // Magic: \0asm
    writeByte(0x00);
    writeByte(0x61);
    writeByte(0x73);
    writeByte(0x6D);
    // Version: 1
    writeByte(0x01);
    writeByte(0x00);
    writeByte(0x00);
    writeByte(0x00);
  }

  /** Write a complete section: section ID + byte length + content bytes. */
  public void writeSection(final int id, final byte[] content) {
    writeByte(id);
    writeULEB128(content.length);
    writeBytes(content);
  }

  /** Write the content of another writer into this one (for sub-section assembly). */
  public void write(final WasmBinaryWriter other) {
    writeBytes(other.toByteArray());
  }

  /** Write a ULEB128-prefixed byte vector (used for function bodies, etc.). */
  public void writeVector(final byte[] data) {
    writeULEB128(data.length);
    writeBytes(data);
  }

  /** Return all accumulated bytes. */
  public byte[] toByteArray() {
    return buffer.toByteArray();
  }

  /** Current size in bytes. */
  public int size() {
    return buffer.size();
  }

  /** Reset the writer. */
  public void reset() {
    buffer.reset();
  }
}
