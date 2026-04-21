package io.safelang.bytecode;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.zip.CRC32;

/** Serializes a BytecodeModule to the .safeb binary file format. */
public class BytecodeWriter {

  /** Write a BytecodeModule to an output stream. */
  public void write(final BytecodeModule module, final OutputStream out) throws IOException {
    // Write body to buffer, then compute checksum
    final var body = new ByteArrayOutputStream();
    body(module, new DataOutputStream(body));
    final var bytes = body.toByteArray();

    final var checksum = new CRC32();
    checksum.update(bytes);

    final var stream = new DataOutputStream(out);
    // Header (36 bytes = 32 + 4 for checksum)
    stream.write(BytecodeModule.MAGIC); // 4 bytes
    stream.writeShort(BytecodeModule.VERSION); // 2 bytes
    stream.writeShort(module.locals()); // 2 bytes
    stream.writeInt(module.pool().size()); // 4 bytes
    stream.writeInt(module.types().size()); // 4 bytes
    stream.writeInt(module.enums().size()); // 4 bytes
    stream.writeInt(module.functions().size()); // 4 bytes
    stream.writeInt(module.globals().size()); // 4 bytes
    stream.writeInt(module.main().length); // 4 bytes
    stream.writeInt((int) checksum.getValue()); // 4 bytes — CRC32

    stream.write(bytes);
    stream.flush();
  }

  private void body(final BytecodeModule module, final DataOutputStream stream) throws IOException {
    final var pool = module.pool();
    final var types = module.types();
    final var enums = module.enums();
    final var functions = module.functions();
    final var globals = module.globals();
    final var main = module.main();

    // Constant Pool
    for (final var entry : pool.entries()) {
      stream.writeByte(entry.tag());
      if (entry instanceof ConstantPool.IntEntry(long value)) {
        stream.writeLong(value);
      } else if (entry instanceof ConstantPool.FloatEntry(double value)) {
        stream.writeDouble(value);
      } else if (entry instanceof ConstantPool.StringEntry(String value)) {
        final var bytes = value.getBytes(StandardCharsets.UTF_8);
        stream.writeShort(bytes.length);
        stream.write(bytes);
      } else if (entry instanceof ConstantPool.NameEntry(String value)) {
        final var bytes = value.getBytes(StandardCharsets.UTF_8);
        stream.writeShort(bytes.length);
        stream.write(bytes);
      }
    }

    // Type Definitions
    for (final var type : types) {
      stream.writeShort(type.index());
      stream.writeShort(type.fields().size());
      for (final var field : type.fields()) {
        stream.writeShort(field.index());
        stream.writeByte(field.tag());
      }
    }

    // Enum Definitions
    for (final var entry : enums) {
      stream.writeShort(entry.index());
      stream.writeShort(entry.variants().size());
      for (final var variant : entry.variants()) {
        stream.writeShort(variant.index());
        stream.writeByte(variant.tags().size());
        for (final var tag : variant.tags()) {
          stream.writeByte(tag);
        }
      }
    }

    // Function Table
    for (final var function : functions) {
      stream.writeShort(function.index());
      stream.writeShort(function.parameters());
      stream.writeShort(function.locals());
      final var bytecode = function.bytecode();
      stream.writeInt(bytecode != null ? bytecode.length : 0);
      if (bytecode != null) {
        stream.write(bytecode);
      }

      // Requires contract
      stream.writeByte(function.hasRequires() ? 1 : 0);
      if (function.hasRequires()) {
        final var requires = function.requires();
        stream.writeInt(requires.length);
        stream.write(requires);
      }

      // Ensures contract
      stream.writeByte(function.hasEnsures() ? 1 : 0);
      if (function.hasEnsures()) {
        final var ensures = function.ensures();
        stream.writeInt(ensures.length);
        stream.write(ensures);
      }

      // Decreases clause
      stream.writeByte(function.hasDecreases() ? 1 : 0);
      if (function.hasDecreases()) {
        final var decreases = function.decreases();
        stream.writeInt(decreases.length);
        stream.write(decreases);
      }
    }

    // Global Variables
    for (final var global : globals) {
      stream.writeShort(global.index());
      stream.writeByte(global.isConst() ? 1 : 0);
    }

    // Main Bytecode
    stream.write(main);
  }

  /** Write a BytecodeModule to a file. */
  public void save(final BytecodeModule module, final String filename) throws IOException {
    try (var stream = new FileOutputStream(filename);
        var buffered = new BufferedOutputStream(stream)) {
      write(module, buffered);
    }
  }
}
