package io.safelang;

import static org.junit.jupiter.api.Assertions.*;

import io.safelang.analyzer.SemanticAnalyzer;
import io.safelang.bytecode.*;
import io.safelang.compiler.bytecode.*;
import io.safelang.interpreter.Interpreter;
import io.safelang.parser.SAFEParser;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;

class BinaryTests {

  private static final String[] STDLIB_MODULES = {
    "io",
    "std",
    "math",
    "strings",
    "file",
    "collections",
    "option",
    "result",
    "stack",
    "queue",
    "sorting",
    "tree",
    "functional",
    "binary",
    "hash"
  };

  private static final String PREAMBLE =
      """
            program test;
            import io;
            import std;
            import binary;
            """;

  private static final String HASH_PREAMBLE =
      """
            program test;
            import io;
            import std;
            import binary;
            import hash;
            """;

  private String run(final String source) {
    final var program = SAFEParser.parse(source);
    final var loader = new ModuleLoader(Path.of("stdlib/io.safe"));
    final var registry = new ModuleRegistry();
    for (final var name : STDLIB_MODULES) {
      try {
        final var module = loader.load(name);
        registry.register(name, module);
      } catch (Exception ignored) {
      }
    }
    for (final var imported : program.imports()) {
      if (!registry.has(imported.module())) {
        try {
          final var module = loader.load(imported.module());
          registry.register(imported.module(), module);
        } catch (Exception ignored) {
        }
      }
    }
    final var analyzer = new SemanticAnalyzer(registry);
    analyzer.analyze(program);
    final var interpreter = new Interpreter();
    interpreter.setRegistry(registry);

    final var old = System.out;
    final var buffer = new ByteArrayOutputStream();
    System.setOut(new PrintStream(buffer));
    try {
      interpreter.interpret(program);
    } finally {
      System.setOut(old);
    }
    return buffer.toString().trim();
  }

  private String runBytecode(final String source) {
    final var program = SAFEParser.parse(source);
    final var loader = new ModuleLoader(Path.of("stdlib/io.safe"));
    final var registry = new ModuleRegistry();
    for (final var name : STDLIB_MODULES) {
      try {
        final var module = loader.load(name);
        registry.register(name, module);
      } catch (Exception ignored) {
      }
    }
    for (final var imported : program.imports()) {
      if (!registry.has(imported.module())) {
        try {
          final var module = loader.load(imported.module());
          registry.register(imported.module(), module);
        } catch (Exception ignored) {
        }
      }
    }
    final var analyzer = new SemanticAnalyzer(registry);
    analyzer.analyze(program);
    final var compiler = new BytecodeCompiler();
    compiler.setRegistry(registry);
    final var module = compiler.compile(program);

    final var old = System.out;
    final var buffer = new ByteArrayOutputStream();
    System.setOut(new PrintStream(buffer));
    try {
      final var vm = new BytecodeVM(module);
      vm.execute();
    } finally {
      System.setOut(old);
    }
    return buffer.toString().trim();
  }

  // ========== Bytes Type ==========

  @Test
  void allocCreatesZeroBytes() {
    assertEquals(
        "0000000000",
        run(
            PREAMBLE
                + """
            bytes b = binary:alloc(5);
            io:println(binary:hex(b));
            """));
  }

  @Test
  void encodeAndDecode() {
    assertEquals(
        "hello",
        run(
            PREAMBLE
                + """
            bytes b = binary:encode("hello");
            io:println(binary:decode(b));
            """));
  }

  @Test
  void getAndSet() {
    assertEquals(
        "65\n66",
        run(
            PREAMBLE
                + """
            bytes b = binary:encode("AB");
            io:println(binary:get(b, 0));
            io:println(binary:get(b, 1));
            """));
  }

  @Test
  void setReturnsNewBytes() {
    assertEquals(
        "88",
        run(
            PREAMBLE
                + """
            bytes b = binary:alloc(3);
            bytes c = binary:put(b, 1, 88);
            io:println(binary:get(c, 1));
            """));
  }

  @Test
  void sliceBytes() {
    assertEquals(
        "ll",
        run(
            PREAMBLE
                + """
            bytes b = binary:encode("hello");
            bytes s = binary:slice(b, 2, 4);
            io:println(binary:decode(s));
            """));
  }

  @Test
  void concatBytes() {
    assertEquals(
        "helloworld",
        run(
            PREAMBLE
                + """
            bytes a = binary:encode("hello");
            bytes b = binary:encode("world");
            bytes c = binary:concat(a, b);
            io:println(binary:decode(c));
            """));
  }

  @Test
  void packAndUnpack() {
    assertEquals(
        "12345",
        run(
            PREAMBLE
                + """
            bytes b = binary:pack(12345, 8);
            int v = binary:unpack(b, 0, 8);
            io:println(v);
            """));
  }

  @Test
  void packWidth4() {
    assertEquals(
        "256",
        run(
            PREAMBLE
                + """
            bytes b = binary:pack(256, 4);
            io:println(binary:unpack(b, 0, 4));
            """));
  }

  @Test
  void packWidth2() {
    assertEquals(
        "1000",
        run(
            PREAMBLE
                + """
            bytes b = binary:pack(1000, 2);
            io:println(binary:unpack(b, 0, 2));
            """));
  }

  @Test
  void packWidth1() {
    assertEquals(
        "42",
        run(
            PREAMBLE
                + """
            bytes b = binary:pack(42, 1);
            io:println(binary:unpack(b, 0, 1));
            """));
  }

  @Test
  void patchBytes() {
    assertEquals(
        "heXXo",
        run(
            PREAMBLE
                + """
            bytes b = binary:encode("hello");
            bytes p = binary:encode("XX");
            bytes r = binary:patch(b, 2, p);
            io:println(binary:decode(r));
            """));
  }

  @Test
  void compareBytes() {
    assertEquals(
        "-1\n0\n1",
        run(
            PREAMBLE
                + """
            bytes a = binary:encode("abc");
            bytes b = binary:encode("abd");
            bytes c = binary:encode("abc");
            io:println(binary:compare(a, b));
            io:println(binary:compare(a, c));
            io:println(binary:compare(b, a));
            """));
  }

  @Test
  void hexEncode() {
    assertEquals(
        "48656c6c6f",
        run(
            PREAMBLE
                + """
            bytes b = binary:encode("Hello");
            io:println(binary:hex(b));
            """));
  }

  @Test
  void lenOfBytes() {
    assertEquals(
        "5",
        run(
            PREAMBLE
                + """
            bytes b = binary:encode("hello");
            io:println(binary:length(b));
            """));
  }

  @Test
  void strOfBytes() {
    assertEquals(
        "48656c6c6f",
        run(
            PREAMBLE
                + """
            bytes b = binary:encode("Hello");
            io:println(binary:tostr(b));
            """));
  }

  // ========== Bytecode Backend ==========

  @Test
  void bytecodeAllocAndHex() {
    assertEquals(
        "0000000000",
        runBytecode(
            PREAMBLE
                + """
            bytes b = binary:alloc(5);
            io:println(binary:hex(b));
            """));
  }

  @Test
  void bytecodeEncodeAndDecode() {
    assertEquals(
        "hello",
        runBytecode(
            PREAMBLE
                + """
            bytes b = binary:encode("hello");
            io:println(binary:decode(b));
            """));
  }

  @Test
  void bytecodePackAndUnpack() {
    assertEquals(
        "12345",
        runBytecode(
            PREAMBLE
                + """
            bytes b = binary:pack(12345, 8);
            io:println(binary:unpack(b, 0, 8));
            """));
  }

  @Test
  void bytecodeGetAndSet() {
    assertEquals(
        "65\n0\n99",
        runBytecode(
            PREAMBLE
                + """
            bytes b = binary:encode("A");
            io:println(binary:get(b, 0));
            bytes c = binary:alloc(2);
            io:println(binary:get(c, 0));
            c = binary:put(c, 1, 99);
            io:println(binary:get(c, 1));
            """));
  }

  @Test
  void bytecodeSlice() {
    assertEquals(
        "ll",
        runBytecode(
            PREAMBLE
                + """
            bytes b = binary:encode("hello");
            io:println(binary:decode(binary:slice(b, 2, 4)));
            """));
  }

  @Test
  void bytecodePatch() {
    assertEquals(
        "heXXo",
        runBytecode(
            PREAMBLE
                + """
            bytes b = binary:encode("hello");
            bytes r = binary:patch(b, 2, binary:encode("XX"));
            io:println(binary:decode(r));
            """));
  }

  // ========== Binary File I/O ==========

  @Test
  void binaryFileWriteAndRead() throws Exception {
    final var path = Files.createTempFile("safe_binary_test", ".bin");
    try {
      final var result =
          run(
              PREAMBLE
                  + """
                int h = binary:open("%s", "w");
                binary:write(h, binary:encode("SAFE"));
                binary:write(h, binary:pack(42, 8));
                binary:flush(h);
                binary:close(h);
                int r = binary:open("%s", "r");
                bytes header = binary:read(r, 4);
                bytes number = binary:read(r, 8);
                io:println(binary:decode(header));
                io:println(binary:unpack(number, 0, 8));
                binary:close(r);
                """
                      .formatted(path, path));
      assertEquals("SAFE\n42", result);
    } finally {
      Files.deleteIfExists(path);
    }
  }

  @Test
  void binaryFileSeek() throws Exception {
    final var path = Files.createTempFile("safe_seek_test", ".bin");
    try {
      final var result =
          run(
              PREAMBLE
                  + """
                int h = binary:open("%s", "w");
                binary:write(h, binary:encode("ABCDEFGH"));
                binary:flush(h);
                binary:close(h);
                int r = binary:open("%s", "r");
                binary:seek(r, 4);
                bytes data = binary:read(r, 4);
                io:println(binary:decode(data));
                binary:close(r);
                """
                      .formatted(path, path));
      assertEquals("EFGH", result);
    } finally {
      Files.deleteIfExists(path);
    }
  }

  @Test
  void binaryFileSize() throws Exception {
    final var path = Files.createTempFile("safe_size_test", ".bin");
    try {
      Files.write(path, new byte[] {1, 2, 3, 4, 5});
      final var result =
          run(
              PREAMBLE
                  + """
                io:println(binary:size("%s"));
                """
                      .formatted(path));
      assertEquals("5", result);
    } finally {
      Files.deleteIfExists(path);
    }
  }

  @Test
  void bytecodeFileWriteAndRead() throws Exception {
    final var path = Files.createTempFile("safe_bc_binary_test", ".bin");
    try {
      final var result =
          runBytecode(
              PREAMBLE
                  + """
                int h = binary:open("%s", "w");
                binary:write(h, binary:encode("TEST"));
                binary:flush(h);
                binary:close(h);
                int r = binary:open("%s", "r");
                bytes data = binary:read(r, 4);
                io:println(binary:decode(data));
                binary:close(r);
                """
                      .formatted(path, path));
      assertEquals("TEST", result);
    } finally {
      Files.deleteIfExists(path);
    }
  }

  // ========== Hash Functions ==========

  @Test
  void fnvHash() {
    assertEquals(
        "true",
        run(
            HASH_PREAMBLE
                + """
            bytes b = binary:encode("hello");
            int h = hash:fnv(b);
            io:println(h != 0);
            """));
  }

  @Test
  void fnvDifferentInputsDifferentHashes() {
    assertEquals(
        "true",
        run(
            HASH_PREAMBLE
                + """
            int a = hash:fnv(binary:encode("hello"));
            int b = hash:fnv(binary:encode("world"));
            io:println(a != b);
            """));
  }

  @Test
  void fnvSameInputSameHash() {
    assertEquals(
        "true",
        run(
            HASH_PREAMBLE
                + """
            int a = hash:fnv(binary:encode("test"));
            int b = hash:fnv(binary:encode("test"));
            io:println(a == b);
            """));
  }

  @Test
  void crcHash() {
    assertEquals(
        "true",
        run(
            HASH_PREAMBLE
                + """
            bytes b = binary:encode("hello");
            int h = hash:crc(b);
            io:println(h != 0);
            """));
  }

  @Test
  void crcKnownValue() {
    assertEquals(
        "907060870",
        run(
            HASH_PREAMBLE
                + """
            io:println(hash:crc(binary:encode("hello")));
            """));
  }

  @Test
  void murmurHash() {
    assertEquals(
        "true",
        run(
            HASH_PREAMBLE
                + """
            int a = hash:murmur(binary:encode("hello"));
            int b = hash:murmur(binary:encode("world"));
            io:println(a != b);
            """));
  }

  @Test
  void murmurDeterministic() {
    assertEquals(
        "true",
        run(
            HASH_PREAMBLE
                + """
            int a = hash:murmur(binary:encode("test"));
            int b = hash:murmur(binary:encode("test"));
            io:println(a == b);
            """));
  }

  @Test
  void hashTextConvenience() {
    assertEquals(
        "true",
        run(
            """
            program test;
            import io;
            import hash;
            int h = hash:text("hello");
            io:println(h != 0);
            """));
  }

  // ========== Bytecode Hash ==========

  @Test
  void bytecodeFnvHash() {
    assertEquals(
        "true",
        runBytecode(
            HASH_PREAMBLE
                + """
            int a = hash:fnv(binary:encode("hello"));
            int b = hash:fnv(binary:encode("hello"));
            io:println(a == b);
            """));
  }

  @Test
  void bytecodeCrcHash() {
    assertEquals(
        "907060870",
        runBytecode(
            HASH_PREAMBLE
                + """
            io:println(hash:crc(binary:encode("hello")));
            """));
  }

  // ========== Edge Cases ==========

  @Test
  void emptyBytes() {
    assertEquals(
        "0",
        run(
            PREAMBLE
                + """
            bytes b = binary:alloc(0);
            io:println(binary:length(b));
            io:println(binary:hex(b));
            """));
  }

  @Test
  void typeofBytes() {
    assertEquals(
        "bytes",
        run(
            PREAMBLE
                + """
            bytes b = binary:alloc(1);
            io:println(binary:kind(b));
            """));
  }

  @Test
  void bytesEquality() {
    assertEquals(
        "true\nfalse",
        run(
            PREAMBLE
                + """
            bytes a = binary:encode("hello");
            bytes b = binary:encode("hello");
            bytes c = binary:encode("world");
            io:println(a == b);
            io:println(a == c);
            """));
  }

  @Test
  void bytesConcatOperator() {
    assertEquals(
        "helloworld",
        run(
            PREAMBLE
                + """
            bytes a = binary:encode("hello");
            bytes b = binary:encode("world");
            bytes c = binary:concat(a, b);
            io:println(binary:decode(c));
            """));
  }

  // ========== Phase 2 (fourth-round audit): "w" mode truncates ==========

  @Test
  void wModeTruncatesExistingFile() throws Exception {
    final var path = Files.createTempFile("safe_w_truncate_", ".bin");
    try {
      final var source =
          PREAMBLE
              + """
              int seed = binary:open("%s", "w");
              binary:write(seed, binary:alloc(8));
              binary:close(seed);
              int h = binary:open("%s", "w");
              binary:write(h, binary:alloc(1));
              binary:close(h);
              int size = binary:size("%s");
              io:println(`${size}`);
              """
                  .formatted(path, path, path);
      assertEquals("1", run(source));
    } finally {
      Files.deleteIfExists(path);
    }
  }

  @Test
  void wModeCreatesNewFile() throws Exception {
    final var path = Files.createTempFile("safe_w_create_", ".bin");
    Files.deleteIfExists(path);
    try {
      final var source =
          PREAMBLE
              + """
              int h = binary:open("%s", "w");
              binary:write(h, binary:alloc(3));
              binary:close(h);
              int size = binary:size("%s");
              io:println(`${size}`);
              """
                  .formatted(path, path);
      assertEquals("3", run(source));
    } finally {
      Files.deleteIfExists(path);
    }
  }

  @Test
  void rwModePreservesContent() throws Exception {
    final var path = Files.createTempFile("safe_rw_preserve_", ".bin");
    try {
      final var source =
          PREAMBLE
              + """
              int seed = binary:open("%s", "w");
              binary:write(seed, binary:alloc(8));
              binary:close(seed);
              int h = binary:open("%s", "rw");
              binary:write(h, binary:alloc(1));
              binary:close(h);
              int size = binary:size("%s");
              io:println(`${size}`);
              """
                  .formatted(path, path, path);
      assertEquals("8", run(source));
    } finally {
      Files.deleteIfExists(path);
    }
  }

  @Test
  void rModeDoesNotModify() throws Exception {
    final var path = Files.createTempFile("safe_r_readonly_", ".bin");
    try {
      final var source =
          PREAMBLE
              + """
              int seed = binary:open("%s", "w");
              binary:write(seed, binary:alloc(5));
              binary:close(seed);
              int h = binary:open("%s", "r");
              bytes data = binary:read(h, 5);
              binary:close(h);
              int size = binary:size("%s");
              io:println(`${size}`);
              """
                  .formatted(path, path, path);
      assertEquals("5", run(source));
    } finally {
      Files.deleteIfExists(path);
    }
  }
}
