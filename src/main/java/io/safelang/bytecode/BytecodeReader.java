package io.safelang.bytecode;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.CRC32;

/** Deserializes a .safeb binary file into a BytecodeModule. */
public class BytecodeReader {

  /** Read a BytecodeModule from an input stream. */
  public BytecodeModule read(final InputStream in) throws IOException {
    final var raw = in.readAllBytes();
    final var stream = new DataInputStream(new ByteArrayInputStream(raw));
    final var module = new BytecodeModule();
    final var pool = module.pool();

    // Read Header (36 bytes)
    final var magic = new byte[4];
    stream.readFully(magic);
    if (magic[0] != 0x53 || magic[1] != 0x41 || magic[2] != 0x46 || magic[3] != 0x45) {
      throw new BytecodeException("Invalid magic number — not a .safeb file");
    }

    final var version = stream.readUnsignedShort();
    if (version != BytecodeModule.VERSION) {
      throw new BytecodeException("Unsupported bytecode version: " + version);
    }

    final var slots = stream.readUnsignedShort(); // main local slot count (0 = legacy default 256)
    final var constants = stream.readInt();
    final var types = stream.readInt();
    final var enums = stream.readInt();
    final var functions = stream.readInt();
    final var globals = stream.readInt();
    final var body = stream.readInt();
    final var expected = stream.readInt(); // CRC32 checksum

    // Verify checksum over the body (everything after the 36-byte header)
    final var header = 36;
    if (raw.length > header) {
      final var checksum = new CRC32();
      checksum.update(raw, header, raw.length - header);
      final var actual = (int) checksum.getValue();
      if (actual != expected) {
        throw new BytecodeException(
            "Bytecode checksum mismatch — file may be corrupted (expected "
                + Integer.toHexString(expected)
                + ", got "
                + Integer.toHexString(actual)
                + ")");
      }
    }

    // Read Constant Pool
    for (int i = 0; i < constants; i++) {
      final var tag = stream.readUnsignedByte();
      switch (tag) {
        case ConstantPool.TAG_INT:
          pool.addInt(stream.readLong());
          break;
        case ConstantPool.TAG_FLOAT:
          pool.addFloat(stream.readDouble());
          break;
        case ConstantPool.TAG_STRING:
          {
            final var len = stream.readUnsignedShort();
            final var bytes = new byte[len];
            stream.readFully(bytes);
            pool.addString(new String(bytes, StandardCharsets.UTF_8));
            break;
          }
        case ConstantPool.TAG_NAME:
          {
            final var len = stream.readUnsignedShort();
            final var bytes = new byte[len];
            stream.readFully(bytes);
            pool.addName(new String(bytes, StandardCharsets.UTF_8));
            break;
          }
        default:
          throw new BytecodeException("Unknown constant pool tag: " + tag);
      }
    }

    // Read Type Definitions
    for (int i = 0; i < types; i++) {
      final var index = stream.readUnsignedShort();
      final var name = pool.getString(index);
      final var fields = new ArrayList<TypeDefinition.FieldInfo>();
      final var width = stream.readUnsignedShort();
      for (int j = 0; j < width; j++) {
        final var slot = stream.readUnsignedShort();
        final var tag = stream.readUnsignedByte();
        final var field = pool.getString(slot);
        fields.add(new TypeDefinition.FieldInfo(field, slot, tag));
      }
      module.add(new TypeDefinition(name, index, fields));
    }

    // Read Enum Definitions
    for (int i = 0; i < enums; i++) {
      final var index = stream.readUnsignedShort();
      final var name = pool.getString(index);
      final var variants = new ArrayList<EnumInfo.VariantInfo>();
      final var count = stream.readUnsignedShort();
      for (int j = 0; j < count; j++) {
        final var slot = stream.readUnsignedShort();
        final var variant = pool.getString(slot);
        final var width = stream.readUnsignedByte();
        final var tags = new ArrayList<Integer>();
        for (int k = 0; k < width; k++) {
          tags.add(stream.readUnsignedByte());
        }
        variants.add(new EnumInfo.VariantInfo(variant, slot, tags));
      }
      module.add(new EnumInfo(name, index, variants));
    }

    // Read Function Table
    for (int i = 0; i < functions; i++) {
      final var index = stream.readUnsignedShort();
      final var name = pool.getString(index);
      final var parameters = stream.readUnsignedShort();
      final var locals = stream.readUnsignedShort();

      byte[] code = null;
      final var size = stream.readInt();
      if (size > 0) {
        code = new byte[size];
        stream.readFully(code);
      }

      // Requires contract
      byte[] requires = null;
      if (stream.readUnsignedByte() == 1) {
        final var length = stream.readInt();
        requires = new byte[length];
        stream.readFully(requires);
      }

      // Ensures contract
      byte[] ensures = null;
      if (stream.readUnsignedByte() == 1) {
        final var length = stream.readInt();
        ensures = new byte[length];
        stream.readFully(ensures);
      }

      // Decreases clause
      byte[] decreases = null;
      if (stream.readUnsignedByte() == 1) {
        final var length = stream.readInt();
        decreases = new byte[length];
        stream.readFully(decreases);
      }

      module.add(
          new FunctionDefinition(
              name, index, parameters, locals, code, requires, ensures, decreases));
    }

    // Read Global Variables
    for (int i = 0; i < globals; i++) {
      final var index = stream.readUnsignedShort();
      final var name = pool.getString(index);
      final var constant = stream.readUnsignedByte() == 1;
      module.add(new BytecodeModule.GlobalVarInfo(name, index, constant));
    }

    // Read Main Bytecode
    final var main = new byte[body];
    stream.readFully(main);
    module.setMain(main);
    module.setLocals(slots > 0 ? slots : 256);

    return module;
  }

  /** Read a BytecodeModule from a file. */
  public BytecodeModule load(final String filename) throws IOException {
    try (var stream = new FileInputStream(filename);
        var buffered = new BufferedInputStream(stream)) {
      return read(buffered);
    }
  }
}
