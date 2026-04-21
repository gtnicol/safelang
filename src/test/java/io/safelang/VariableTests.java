package io.safelang;

import static org.junit.jupiter.api.Assertions.*;

import io.safelang.analyzer.SemanticException;
import org.junit.jupiter.api.Test;

class VariableTests {

  @Test
  void declareAndInit() {
    final var output =
        TestHelper.run(
            """
                program test;
                import io;
                int x = 42;
                io:println(x);
                """);
    assertEquals("42", output);
  }

  @Test
  void declareWithoutInit() {
    final var output =
        TestHelper.run(
            """
                program test;
                import io;
                int x;
                io:println(x);
                """);
    assertEquals("void", output);
  }

  @Test
  void reassignment() {
    final var output =
        TestHelper.run(
            """
                program test;
                import io;
                int x = 1;
                x = 2;
                io:println(x);
                """);
    assertEquals("2", output);
  }

  @Test
  void constVariable() {
    final var output =
        TestHelper.run(
            """
                program test;
                import io;
                const int MAX = 100;
                io:println(MAX);
                """);
    assertEquals("100", output);
  }

  @Test
  void constReassignmentFails() {
    assertThrows(
        SemanticException.class,
        () ->
            TestHelper.run(
                """
                program test;
                const int MAX = 100;
                MAX = 200;
                """));
  }

  @Test
  void stringVariable() {
    final var output =
        TestHelper.run(
            """
                program test;
                import io;
                string name = "Alice";
                io:println(name);
                """);
    assertEquals("Alice", output);
  }

  @Test
  void booleanVariable() {
    final var output =
        TestHelper.run(
            """
                program test;
                import io;
                boolean flag = true;
                io:println(flag);
                """);
    assertEquals("true", output);
  }

  @Test
  void multipleVariables() {
    final var output =
        TestHelper.run(
            """
                program test;
                import io;
                int a = 10;
                int b = 20;
                io:println(a + b);
                """);
    assertEquals("30", output);
  }

  @Test
  void fieldAssignment() {
    final var output =
        TestHelper.run(
            """
                program test;
                import io;
                type Point { int x; int y; }
                Point p = Point { x: 3, y: 4 };
                p.x = 10;
                io:println(p.x);
                """);
    assertEquals("10", output);
  }
}
