package io.safelang;

import static org.junit.jupiter.api.Assertions.*;

import io.safelang.analyzer.SemanticAnalyzer;
import io.safelang.analyzer.SemanticException;
import io.safelang.parser.SAFEParser;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class TypeCheckTests {

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

  private static void analyze(final String source) {
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
    final var analyzer = new SemanticAnalyzer(registry);
    analyzer.analyze(program);
  }

  // --- Negative tests: type mismatches that should be rejected ---

  @Test
  void mapValueNestedMapInsteadOfInt() {
    assertThrows(
        SemanticException.class,
        () ->
            analyze(
                """
                program test;
                map<string, int> x = {"a": {"x": 1}};
                """));
  }

  @Test
  void listAssignedToInt() {
    assertThrows(
        SemanticException.class,
        () ->
            analyze(
                """
                program test;
                int x = [1, 2, 3];
                """));
  }

  @Test
  void mapValueListInsteadOfInt() {
    assertThrows(
        SemanticException.class,
        () ->
            analyze(
                """
                program test;
                map<string, int> x = {"a": [1, 2]};
                """));
  }

  @Test
  void mapAssignedToList() {
    assertThrows(
        SemanticException.class,
        () ->
            analyze(
                """
                program test;
                list<int> x = {"a": 1};
                """));
  }

  @Test
  void intLiteralAssignedToString() {
    assertThrows(
        SemanticException.class,
        () ->
            analyze(
                """
                program test;
                string x = 42;
                """));
  }

  @Test
  void stringLiteralAssignedToInt() {
    assertThrows(
        SemanticException.class,
        () ->
            analyze(
                """
                program test;
                int x = "hello";
                """));
  }

  @Test
  void mixedElementTypesInList() {
    assertThrows(
        SemanticException.class,
        () ->
            analyze(
                """
                program test;
                list<int> x = [1, "two", 3];
                """));
  }

  // --- Positive tests: valid declarations should parse successfully ---

  @Test
  void correctNestedMapType() {
    assertDoesNotThrow(
        () ->
            analyze(
                """
                program test;
                map<string, map<string, int>> x = {"a": {"x": 1}};
                """));
  }

  @Test
  void correctNestedListType() {
    assertDoesNotThrow(
        () ->
            analyze(
                """
                program test;
                list<list<int>> x = [[1, 2], [3, 4]];
                """));
  }

  @Test
  void correctMapOfListType() {
    assertDoesNotThrow(
        () ->
            analyze(
                """
                program test;
                map<string, list<int>> x = {"a": [1, 2, 3]};
                """));
  }

  @Test
  void emptyListAllowed() {
    assertDoesNotThrow(
        () ->
            analyze(
                """
                program test;
                list<int> x = [];
                """));
  }

  @Test
  void emptyMapAllowed() {
    assertDoesNotThrow(
        () ->
            analyze(
                """
                program test;
                map<string, int> x = {};
                """));
  }
}
