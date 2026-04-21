package io.safelang;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class ArithmeticTests {

  @Test
  void intAddition() {
    final var output =
        TestHelper.run(
            """
                program test;
                import io;
                io:println(2 + 3);
                """);
    assertEquals("5", output);
  }

  @Test
  void intSubtraction() {
    final var output =
        TestHelper.run(
            """
                program test;
                import io;
                io:println(10 - 4);
                """);
    assertEquals("6", output);
  }

  @Test
  void intMultiplication() {
    final var output =
        TestHelper.run(
            """
                program test;
                import io;
                io:println(6 * 7);
                """);
    assertEquals("42", output);
  }

  @Test
  void intDivision() {
    final var output =
        TestHelper.run(
            """
                program test;
                import io;
                io:println(10 / 3);
                """);
    assertEquals("3", output);
  }

  @Test
  void intModulo() {
    final var output =
        TestHelper.run(
            """
                program test;
                import io;
                io:println(10 % 3);
                """);
    assertEquals("1", output);
  }

  @Test
  void floatAddition() {
    final var output =
        TestHelper.run(
            """
                program test;
                import io;
                io:println(1.0 + 2.5);
                """);
    assertEquals("3.5", output);
  }

  @Test
  void precedence() {
    final var output =
        TestHelper.run(
            """
                program test;
                import io;
                io:println(2 + 3 * 4);
                """);
    assertEquals("14", output);
  }

  @Test
  void unaryNegation() {
    final var output =
        TestHelper.run(
            """
                program test;
                import io;
                io:println(-5);
                """);
    assertEquals("-5", output);
  }

  @Test
  void comparisonOperators() {
    final var output =
        TestHelper.run(
            """
                program test;
                import io;
                io:println(5 > 3);
                io:println(1 > 10);
                io:println(3 == 3);
                io:println(3 != 4);
                io:println(2 <= 2);
                io:println(5 >= 6);
                """);
    assertEquals("true\nfalse\ntrue\ntrue\ntrue\nfalse", output);
  }

  @Test
  void booleanLogic() {
    final var output =
        TestHelper.run(
            """
                program test;
                import io;
                io:println(true && true);
                io:println(true && false);
                io:println(false || true);
                io:println(false || false);
                io:println(!true);
                io:println(!false);
                """);
    assertEquals("true\nfalse\ntrue\nfalse\nfalse\ntrue", output);
  }

  @Test
  void parenthesizedExpressions() {
    final var output =
        TestHelper.run(
            """
                program test;
                import io;
                io:println((2 + 3) * 4);
                """);
    assertEquals("20", output);
  }
}
