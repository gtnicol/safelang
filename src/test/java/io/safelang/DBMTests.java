package io.safelang;

import static org.junit.jupiter.api.Assertions.*;

import io.safelang.analyzer.SemanticAnalyzer;
import io.safelang.interpreter.Interpreter;
import io.safelang.parser.SAFEParser;
import java.io.*;
import java.nio.file.*;
import org.junit.jupiter.api.*;

class DBMTests {

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
    "hash",
    "page",
    "dbm"
  };

  private static final String PREAMBLE =
      """
            program test;
            import io;
            import binary;
            import dbm;
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

  // ========== Create and Close ==========

  @Test
  void createAndClose() throws Exception {
    final var path = Files.createTempFile("safe_dbm_test", ".db");
    try {
      run(
          PREAMBLE
              + """
                DBM store = dbm:create("%s");
                dbm:close(store);
                """
                  .formatted(path));
      assertTrue(Files.exists(path));
      assertTrue(Files.size(path) > 0);
    } finally {
      Files.deleteIfExists(path);
    }
  }

  // ========== Put and Get ==========

  @Test
  void putAndGet() throws Exception {
    final var path = Files.createTempFile("safe_dbm_test", ".db");
    try {
      final var result =
          run(
              PREAMBLE
                  + """
                DBM store = dbm:create("%s");
                store = dbm:put(store, "hello", binary:encode("world"));
                bytes v = dbm:get(store, "hello");
                io:println(binary:decode(v));
                dbm:close(store);
                """
                      .formatted(path));
      assertEquals("world", result);
    } finally {
      Files.deleteIfExists(path);
    }
  }

  @Test
  void putMultipleKeys() throws Exception {
    final var path = Files.createTempFile("safe_dbm_test", ".db");
    try {
      final var result =
          run(
              PREAMBLE
                  + """
                DBM store = dbm:create("%s");
                store = dbm:put(store, "a", binary:encode("alpha"));
                store = dbm:put(store, "b", binary:encode("bravo"));
                store = dbm:put(store, "c", binary:encode("charlie"));
                io:println(binary:decode(dbm:get(store, "a")));
                io:println(binary:decode(dbm:get(store, "b")));
                io:println(binary:decode(dbm:get(store, "c")));
                dbm:close(store);
                """
                      .formatted(path));
      assertEquals("alpha\nbravo\ncharlie", result);
    } finally {
      Files.deleteIfExists(path);
    }
  }

  // ========== Has ==========

  @Test
  void hasKey() throws Exception {
    final var path = Files.createTempFile("safe_dbm_test", ".db");
    try {
      final var result =
          run(
              PREAMBLE
                  + """
                DBM store = dbm:create("%s");
                store = dbm:put(store, "exists", binary:encode("yes"));
                io:println(dbm:has(store, "exists"));
                io:println(dbm:has(store, "missing"));
                dbm:close(store);
                """
                      .formatted(path));
      assertEquals("true\nfalse", result);
    } finally {
      Files.deleteIfExists(path);
    }
  }

  // ========== Update ==========

  @Test
  void updateExistingKey() throws Exception {
    final var path = Files.createTempFile("safe_dbm_test", ".db");
    try {
      final var result =
          run(
              PREAMBLE
                  + """
                DBM store = dbm:create("%s");
                store = dbm:put(store, "key", binary:encode("old"));
                store = dbm:put(store, "key", binary:encode("new"));
                io:println(binary:decode(dbm:get(store, "key")));
                dbm:close(store);
                """
                      .formatted(path));
      assertEquals("new", result);
    } finally {
      Files.deleteIfExists(path);
    }
  }

  // ========== Remove ==========

  @Test
  void removeKey() throws Exception {
    final var path = Files.createTempFile("safe_dbm_test", ".db");
    try {
      final var result =
          run(
              PREAMBLE
                  + """
                DBM store = dbm:create("%s");
                store = dbm:put(store, "key", binary:encode("value"));
                io:println(dbm:has(store, "key"));
                store = dbm:remove(store, "key");
                io:println(dbm:has(store, "key"));
                dbm:close(store);
                """
                      .formatted(path));
      assertEquals("true\nfalse", result);
    } finally {
      Files.deleteIfExists(path);
    }
  }

  @Test
  void removeMissing() throws Exception {
    final var path = Files.createTempFile("safe_dbm_test", ".db");
    try {
      final var result =
          run(
              PREAMBLE
                  + """
                DBM store = dbm:create("%s");
                store = dbm:put(store, "a", binary:encode("alpha"));
                store = dbm:remove(store, "missing");
                io:println(binary:decode(dbm:get(store, "a")));
                dbm:close(store);
                """
                      .formatted(path));
      assertEquals("alpha", result);
    } finally {
      Files.deleteIfExists(path);
    }
  }

  // ========== Missing Key ==========

  @Test
  void getMissingReturnsEmpty() throws Exception {
    final var path = Files.createTempFile("safe_dbm_test", ".db");
    try {
      final var result =
          run(
              PREAMBLE
                  + """
                DBM store = dbm:create("%s");
                bytes v = dbm:get(store, "nope");
                io:println(binary:length(v));
                dbm:close(store);
                """
                      .formatted(path));
      assertEquals("0", result);
    } finally {
      Files.deleteIfExists(path);
    }
  }

  // ========== Reopen ==========

  @Test
  void reopenAndRead() throws Exception {
    final var path = Files.createTempFile("safe_dbm_test", ".db");
    try {
      run(
          PREAMBLE
              + """
                DBM store = dbm:create("%s");
                store = dbm:put(store, "persist", binary:encode("data"));
                dbm:close(store);
                """
                  .formatted(path));
      final var result =
          run(
              PREAMBLE
                  + """
                DBM store = dbm:open("%s");
                io:println(binary:decode(dbm:get(store, "persist")));
                dbm:close(store);
                """
                      .formatted(path));
      assertEquals("data", result);
    } finally {
      Files.deleteIfExists(path);
    }
  }

  // ========== Multiple Entries in Same Bucket ==========

  @Test
  void collisionHandling() throws Exception {
    final var path = Files.createTempFile("safe_dbm_test", ".db");
    try {
      // Insert enough keys that some will share buckets
      final var result =
          run(
              PREAMBLE
                  + """
                DBM store = dbm:create("%s");
                store = dbm:put(store, "key1", binary:encode("val1"));
                store = dbm:put(store, "key2", binary:encode("val2"));
                store = dbm:put(store, "key3", binary:encode("val3"));
                store = dbm:put(store, "key4", binary:encode("val4"));
                store = dbm:put(store, "key5", binary:encode("val5"));
                io:println(binary:decode(dbm:get(store, "key1")));
                io:println(binary:decode(dbm:get(store, "key3")));
                io:println(binary:decode(dbm:get(store, "key5")));
                dbm:close(store);
                """
                      .formatted(path));
      assertEquals("val1\nval3\nval5", result);
    } finally {
      Files.deleteIfExists(path);
    }
  }

  // ========== Count Tracking ==========

  @Test
  void countTracked() throws Exception {
    final var path = Files.createTempFile("safe_dbm_test", ".db");
    try {
      final var result =
          run(
              PREAMBLE
                  + """
                DBM store = dbm:create("%s");
                store = dbm:put(store, "a", binary:encode("1"));
                store = dbm:put(store, "b", binary:encode("2"));
                store = dbm:put(store, "c", binary:encode("3"));
                io:println(store.count);
                store = dbm:remove(store, "b");
                io:println(store.count);
                dbm:close(store);
                """
                      .formatted(path));
      assertEquals("3\n2", result);
    } finally {
      Files.deleteIfExists(path);
    }
  }
}
