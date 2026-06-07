package io.safelang.compiler.jvm;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Hand-rolled JVM class-file serializer. Assembles a constant pool plus a list of static methods
 * into the binary {@code .class} format.
 *
 * <p>The class-file major version is deliberately pinned to 50 (Java 6). For major version 50 the
 * JVM falls back to the legacy type-inference verifier when a method has no {@code StackMapTable}
 * attribute, so generated methods need none. Do not raise this to 51+ without also emitting stack
 * map frames — the split verifier those versions mandate rejects frame-less code.
 */
final class ClassWriter {

  static final int ACC_PUBLIC = 0x0001;
  static final int ACC_STATIC = 0x0008;
  static final int ACC_SUPER = 0x0020;
  static final int ACC_FINAL = 0x0010;

  private static final int MAGIC = 0xCAFEBABE;
  private static final int MAJOR_VERSION = 50;
  private static final int MINOR_VERSION = 0;

  private final ConstantPool pool = new ConstantPool();
  private final int clazz;
  private final int superclass;
  private final int code;
  private final List<Method> methods = new ArrayList<>();

  ClassWriter(final String internalName, final String superInternal) {
    this.clazz = pool.classDefinition(internalName);
    this.superclass = pool.classDefinition(superInternal);
    this.code = pool.utf8("Code");
  }

  ConstantPool pool() {
    return pool;
  }

  void method(
      final int access, final String name, final String descriptor, final MethodEmitter emitter) {
    methods.add(
        new Method(
            access,
            pool.utf8(name),
            pool.utf8(descriptor),
            emitter.maxStack(),
            emitter.maxLocals(),
            emitter.code()));
  }

  byte[] toBytes() {
    final var bytes = new ByteArrayOutputStream();
    final var out = new DataOutputStream(bytes);
    try {
      out.writeInt(MAGIC);
      out.writeShort(MINOR_VERSION);
      out.writeShort(MAJOR_VERSION);
      pool.writeTo(out);
      out.writeShort(ACC_PUBLIC | ACC_SUPER | ACC_FINAL);
      out.writeShort(clazz);
      out.writeShort(superclass);
      out.writeShort(0); // interfaces_count
      out.writeShort(0); // fields_count
      out.writeShort(methods.size());
      for (final var method : methods) {
        writeMethod(out, method);
      }
      out.writeShort(0); // class attributes_count
    } catch (final IOException exception) {
      throw new UncheckedIOException(exception);
    }
    return bytes.toByteArray();
  }

  private void writeMethod(final DataOutputStream out, final Method method) throws IOException {
    out.writeShort(method.access);
    out.writeShort(method.name);
    out.writeShort(method.descriptor);
    out.writeShort(1); // one attribute: Code
    out.writeShort(code);
    out.writeInt(12 + method.code.length); // max_stack+max_locals+code_length+tables
    out.writeShort(method.maxStack);
    out.writeShort(method.maxLocals);
    out.writeInt(method.code.length);
    out.write(method.code);
    out.writeShort(0); // exception_table_length
    out.writeShort(0); // method attributes_count
  }

  private record Method(
      int access, int name, int descriptor, int maxStack, int maxLocals, byte[] code) {}
}
