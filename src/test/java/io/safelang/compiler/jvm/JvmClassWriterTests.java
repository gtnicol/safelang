package io.safelang.compiler.jvm;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import org.junit.jupiter.api.Test;

/**
 * Smoke tests for the hand-rolled class-file emitter. These build a tiny class, load it in-process,
 * and run it — which also proves the JVM verifier accepts our frame-less, version-50 bytecode.
 */
class JvmClassWriterTests {

  private static byte[] helloClass(final String internalName, final String message) {
    final var writer = new ClassWriter(internalName, "java/lang/Object");
    final var pool = writer.pool();
    final var out = pool.field("java/lang/System", "out", "Ljava/io/PrintStream;");
    final var println = pool.method("java/io/PrintStream", "println", "(Ljava/lang/String;)V");
    final var text = pool.string(message);

    final var emitter = new MethodEmitter(1); // String[] args in slot 0
    emitter.getStatic(out, 1);
    emitter.loadConstant(text);
    emitter.invokeVirtual(println, "(Ljava/lang/String;)V");
    emitter.returnVoid();

    writer.method(
        ClassWriter.ACC_PUBLIC | ClassWriter.ACC_STATIC, "main", "([Ljava/lang/String;)V", emitter);
    return writer.toBytes();
  }

  @Test
  void emitsRunnableHelloClass() throws Exception {
    final var bytes = helloClass("io/safelang/generated/Hello", "hello from jvm");
    final var loaded = new BytesLoader().define("io.safelang.generated.Hello", bytes);
    final var main = loaded.getMethod("main", String[].class);

    final var captured = new ByteArrayOutputStream();
    final var original = System.out;
    System.setOut(new PrintStream(captured));
    try {
      main.invoke(null, (Object) new String[0]);
    } finally {
      System.setOut(original);
    }
    assertEquals("hello from jvm", captured.toString().trim());
  }

  private static final class BytesLoader extends ClassLoader {
    Class<?> define(final String binaryName, final byte[] bytes) {
      return defineClass(binaryName, bytes, 0, bytes.length);
    }
  }
}
