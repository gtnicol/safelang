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
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for the module system: loading, qualified calls, visibility, cycle detection, and parity
 * between interpreter and bytecode VM.
 */
class ModuleTests {

  @TempDir Path directory;

  private void writeModule(final String name, final String source) throws IOException {
    Files.writeString(directory.resolve(name + ".safe"), source);
  }

  private String interpret(final String name) throws IOException {
    final var file = directory.resolve(name + ".safe");
    final var source = Files.readString(file);
    final var program = SAFEParser.parse(source);

    final var loader = new ModuleLoader(file);
    final var registry = new ModuleRegistry();
    for (final var imported : program.imports()) {
      final var module = loader.load(imported.module());
      registry.register(imported.module(), module);
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

  private String compile(final String name) throws IOException {
    final var file = directory.resolve(name + ".safe");
    final var source = Files.readString(file);
    final var program = SAFEParser.parse(source);

    final var loader = new ModuleLoader(file);
    final var registry = new ModuleRegistry();
    for (final var imported : program.imports()) {
      final var module = loader.load(imported.module());
      registry.register(imported.module(), module);
    }

    final var analyzer = new SemanticAnalyzer(registry);
    analyzer.analyze(program);

    final var compiler = new BytecodeCompiler();
    compiler.setRegistry(registry);
    final var module = compiler.compile(program);
    final var vm = new BytecodeVM(module);

    final var old = System.out;
    final var buffer = new ByteArrayOutputStream();
    System.setOut(new PrintStream(buffer));
    try {
      vm.execute();
    } finally {
      System.setOut(old);
    }
    return buffer.toString().trim();
  }

  // ========== Basic Function Import ==========

  @Test
  void testQualifiedFunctionCall() throws IOException {
    writeModule(
        "helper",
        """
            module helper;
            public int twice(int n) {
                return n * 2;
            }
            """);
    writeModule(
        "main",
        """
            program main;
            import io;
            import helper;
            io:println(helper:twice(5));
            """);
    assertEquals("10", interpret("main"));
  }

  @Test
  void testQualifiedFunctionCallBytecode() throws IOException {
    writeModule(
        "helper",
        """
            module helper;
            public int twice(int n) {
                return n * 2;
            }
            """);
    writeModule(
        "main",
        """
            program main;
            import io;
            import helper;
            io:println(helper:twice(5));
            """);
    assertEquals("10", compile("main"));
  }

  // ========== Multiple Functions ==========

  @Test
  void testMultipleFunctions() throws IOException {
    writeModule(
        "ops",
        """
            module ops;
            public int add(int a, int b) {
                return a + b;
            }
            public int mul(int a, int b) {
                return a * b;
            }
            """);
    writeModule(
        "main",
        """
            program main;
            import io;
            import ops;
            io:println(ops:add(3, 4));
            io:println(ops:mul(3, 4));
            """);
    assertEquals("7\n12", interpret("main"));
  }

  // ========== Module Constants ==========

  @Test
  void testModuleConstants() throws IOException {
    writeModule(
        "constants",
        """
            module constants;
            public int double(int n) {
                return n * 2;
            }
            public const float PI = 3.14;
            """);
    writeModule(
        "main",
        """
            program main;
            import io;
            import constants;
            io:println(constants.PI);
            """);
    assertEquals("3.14", interpret("main"));
  }

  @Test
  void testModuleConstantsBytecode() throws IOException {
    writeModule(
        "constants",
        """
            module constants;
            public int double(int n) {
                return n * 2;
            }
            public const float PI = 3.14;
            """);
    writeModule(
        "main",
        """
            program main;
            import io;
            import constants;
            io:println(constants.PI);
            """);
    assertEquals("3.14", compile("main"));
  }

  @Test
  void testSelectiveConstantImportInterpreter() throws IOException {
    writeModule(
        "constants",
        """
            module constants;
            public const float PI = 3.14;
            """);
    writeModule(
        "main",
        """
            program main;
            import io;
            import constants { PI };
            io:println(constants.PI);
            """);
    assertEquals("3.14", interpret("main"));
  }

  @Test
  void testSelectiveConstantImportBytecode() throws IOException {
    writeModule(
        "constants",
        """
            module constants;
            public const float PI = 3.14;
            """);
    writeModule(
        "main",
        """
            program main;
            import io;
            import constants { PI };
            io:println(constants.PI);
            """);
    assertEquals("3.14", compile("main"));
  }

  @Test
  void testPrivateConstantSelectiveImportRejected() throws IOException {
    writeModule(
        "secret",
        """
            module secret;
            const int CODE = 7;
            """);
    writeModule(
        "main",
        """
            program main;
            import io;
            import secret { CODE };
            io:println(CODE);
            """);
    assertThrows(RuntimeException.class, () -> interpret("main"));
  }

  @Test
  void testPrivateConstantQualifiedAccessRejected() throws IOException {
    writeModule(
        "secret",
        """
            module secret;
            const int CODE = 7;
            """);
    writeModule(
        "main",
        """
            program main;
            import io;
            import secret;
            io:println(secret.CODE);
            """);
    assertThrows(RuntimeException.class, () -> interpret("main"));
  }

  // ========== Enum Export ==========

  @Test
  void testEnumExport() throws IOException {
    writeModule(
        "types",
        """
            module types;
            public enum Color { Red, Green, Blue }
            public int code(Color c) {
                return case c of {
                    Red: 1;
                    Green: 2;
                    Blue: 3;
                };
            }
            """);
    writeModule(
        "main",
        """
            program main;
            import io;
            import types;
            Color c = Red;
            io:println(types:code(c));
            """);
    assertEquals("1", interpret("main"));
  }

  @Test
  void testEnumWithDataExport() throws IOException {
    writeModule(
        "opt",
        """
            module opt;
            public enum Option { Some(int), None }
            public int unwrap(Option o) {
                return case o of {
                    Some(v): v;
                    None: 0;
                };
            }
            """);
    writeModule(
        "main",
        """
            program main;
            import io;
            import opt;
            Option a = Some(42);
            Option b = None;
            io:println(opt:unwrap(a));
            io:println(opt:unwrap(b));
            """);
    assertEquals("42\n0", interpret("main"));
  }

  @Test
  void testEnumWithDataBytecode() throws IOException {
    writeModule(
        "opt",
        """
            module opt;
            public enum Option { Some(int), None }
            public int unwrap(Option o) {
                return case o of {
                    Some(v): v;
                    None: 0;
                };
            }
            """);
    writeModule(
        "main",
        """
            program main;
            import io;
            import opt;
            Option a = Some(42);
            Option b = None;
            io:println(opt:unwrap(a));
            io:println(opt:unwrap(b));
            """);
    assertEquals("42\n0", compile("main"));
  }

  // ========== Intra-Module Calls ==========

  @Test
  void testIntraModuleCall() throws IOException {
    writeModule(
        "calc",
        """
            module calc;
            public int square(int n) {
                return n * n;
            }
            public int quadruple(int n) {
                return square(n) + square(n);
            }
            """);
    writeModule(
        "main",
        """
            program main;
            import io;
            import calc;
            io:println(calc:quadruple(3));
            """);
    assertEquals("18", interpret("main"));
  }

  @Test
  void testIntraModuleCallBytecode() throws IOException {
    writeModule(
        "calc",
        """
            module calc;
            public int square(int n) {
                return n * n;
            }
            public int quadruple(int n) {
                return square(n) + square(n);
            }
            """);
    writeModule(
        "main",
        """
            program main;
            import io;
            import calc;
            io:println(calc:quadruple(3));
            """);
    assertEquals("18", compile("main"));
  }

  // ========== Multiple Modules ==========

  @Test
  void testMultipleModules() throws IOException {
    writeModule(
        "alpha",
        """
            module alpha;
            public int value() {
                return 10;
            }
            """);
    writeModule(
        "beta",
        """
            module beta;
            public int value() {
                return 20;
            }
            """);
    writeModule(
        "main",
        """
            program main;
            import io;
            import alpha;
            import beta;
            io:println(alpha:value() + beta:value());
            """);
    assertEquals("30", interpret("main"));
  }

  // ========== Cycle Detection ==========

  @Test
  void testCycleDetection() throws IOException {
    writeModule(
        "a",
        """
            module a;
            import b;
            public int x() { return 1; }
            """);
    writeModule(
        "b",
        """
            module b;
            import a;
            public int y() { return 2; }
            """);
    writeModule(
        "main",
        """
            program main;
            import io;
            import a;
            io:println(a:x());
            """);
    assertThrows(RuntimeException.class, () -> interpret("main"));
  }

  // ========== Module Not Found ==========

  @Test
  void testModuleNotFound() throws IOException {
    writeModule(
        "main",
        """
            program main;
            import io;
            import nonexistent;
            io:println("hi");
            """);
    assertThrows(RuntimeException.class, () -> interpret("main"));
  }

  // ========== Non-Module File ==========

  @Test
  void testNonModuleFile() throws IOException {
    writeModule(
        "notmodule",
        """
            program notmodule;
            import io;
            io:println("I am a program");
            """);
    writeModule(
        "main",
        """
            program main;
            import io;
            import notmodule;
            io:println("hi");
            """);
    assertThrows(RuntimeException.class, () -> interpret("main"));
  }

  // ========== Parity: Full Module System ==========

  @Test
  void testFullModuleParity() throws IOException {
    writeModule(
        "ops",
        """
            module ops;
            public int add(int a, int b) {
                return a + b;
            }
            public int mul(int a, int b) {
                return a * b;
            }
            public const int MAGIC = 42;
            """);
    writeModule(
        "main",
        """
            program main;
            import io;
            import ops;
            io:println(ops:add(3, 4));
            io:println(ops:mul(5, 6));
            io:println(ops.MAGIC);
            """);
    final var interpreted = interpret("main");
    final var compiled = compile("main");
    assertEquals(interpreted, compiled);
  }
}
