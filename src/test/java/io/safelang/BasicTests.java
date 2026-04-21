package io.safelang;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class BasicTests {

  @Test
  void helloWorld() {
    final var output =
        TestHelper.run(
            """
                program test;
                import io;
                io:println("Hello, World!");
                """);
    assertEquals("Hello, World!", output);
  }

  @Test
  void multiplePrints() {
    final var output =
        TestHelper.run(
            """
                program test;
                import io;
                io:println("first");
                io:println("second");
                io:println("third");
                """);
    assertEquals("first\nsecond\nthird", output);
  }

  @Test
  void printVsPrintln() {
    final var output =
        TestHelper.run(
            """
                program test;
                import io;
                io:print("hello");
                io:print(" ");
                io:println("world");
                """);
    assertEquals("hello world", output);
  }

  @Test
  void printInteger() {
    final var output =
        TestHelper.run(
            """
                program test;
                import io;
                io:println(42);
                """);
    assertEquals("42", output);
  }

  @Test
  void printBoolean() {
    final var output =
        TestHelper.run(
            """
                program test;
                import io;
                io:println(true);
                io:println(false);
                """);
    assertEquals("true\nfalse", output);
  }

  @Test
  void printFloat() {
    final var output =
        TestHelper.run(
            """
                program test;
                import io;
                io:println(3.14);
                """);
    assertEquals("3.14", output);
  }

  @Test
  void emptyProgram() {
    final var output =
        TestHelper.run(
            """
                program test;
                """);
    assertEquals("", output);
  }
}
