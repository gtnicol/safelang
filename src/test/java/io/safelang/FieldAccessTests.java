package io.safelang;

import static org.junit.jupiter.api.Assertions.*;

import io.safelang.analyzer.SemanticAnalyzer;
import io.safelang.ast.ProgramNode;
import io.safelang.bytecode.BytecodeVM;
import io.safelang.compiler.bytecode.BytecodeCompiler;
import io.safelang.interpreter.Interpreter;
import io.safelang.parser.SAFEParser;
import java.io.*;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** Tests for FieldAccessNode — chained field access including after index access. */
class FieldAccessTests {

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
    "functional"
  };

  private ModuleRegistry registry;

  private ProgramNode parse(final String source) {
    final var program = SAFEParser.parse(source);
    final var loader = new ModuleLoader(Path.of("stdlib/io.safe"));
    registry = new ModuleRegistry();
    for (final var name : STDLIB_MODULES) {
      try {
        final var module = loader.load(name);
        registry.register(name, module);
      } catch (Exception ignored) {
      }
    }
    final var analyzer = new SemanticAnalyzer(registry);
    analyzer.analyze(program);
    return program;
  }

  private String interpret(final String source) {
    final var program = parse(source);
    final var saved = System.out;
    final var capture = new ByteArrayOutputStream();
    System.setOut(new PrintStream(capture));
    try {
      final var interpreter = new Interpreter();
      interpreter.setRegistry(registry);
      interpreter.interpret(program);
    } finally {
      System.setOut(saved);
    }
    return capture.toString().trim();
  }

  private String bytecode(final String source) {
    final var program = parse(source);
    final var compiler = new BytecodeCompiler();
    compiler.setRegistry(registry);
    final var module = compiler.compile(program);
    final var saved = System.out;
    final var capture = new ByteArrayOutputStream();
    System.setOut(new PrintStream(capture));
    try {
      final var vm = new BytecodeVM(module);
      vm.execute();
    } finally {
      System.setOut(saved);
    }
    return capture.toString().trim();
  }

  @Test
  void simpleFieldAccess() {
    final var source =
        """
                program test;
                import io;
                type Point { int x; int y; }
                Point p = Point { x: 10, y: 20 };
                io:println(p.x);
                io:println(p.y);
                """;
    assertEquals("10\n20", interpret(source));
  }

  @Test
  void fieldAccessParity() {
    final var source =
        """
                program test;
                import io;
                type Point { int x; int y; }
                Point p = Point { x: 10, y: 20 };
                io:println(p.x);
                io:println(p.y);
                """;
    assertEquals(interpret(source), bytecode(source));
  }

  @Test
  void fieldAccessAfterIndex() {
    final var source =
        """
                program test;
                import io;
                type Item { string name; int value; }
                list<Item> items = [Item { name: "first", value: 1 }, Item { name: "second", value: 2 }];
                io:println(items[0].name);
                io:println(items[1].value);
                """;
    assertEquals("first\n2", interpret(source));
  }

  @Test
  void fieldAccessAfterIndexParity() {
    final var source =
        """
                program test;
                import io;
                type Item { string name; int value; }
                list<Item> items = [Item { name: "first", value: 1 }, Item { name: "second", value: 2 }];
                io:println(items[0].name);
                io:println(items[1].value);
                """;
    assertEquals(interpret(source), bytecode(source));
  }
}
