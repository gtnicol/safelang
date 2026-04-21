package io.safelang;

import static org.junit.jupiter.api.Assertions.*;

import io.safelang.bytecode.BytecodeException;
import io.safelang.interpreter.InterpreterException;
import org.junit.jupiter.api.Test;

class FunctionTests {

  @Test
  void basic() {
    final var output =
        TestHelper.run(
            """
                program test;
                import io;
                int double(int x) {
                    return x * 2;
                }
                io:println(double(21));
                """);
    assertEquals("42", output);
  }

  @Test
  void multipleParams() {
    final var output =
        TestHelper.run(
            """
                program test;
                import io;
                int add(int a, int b) {
                    return a + b;
                }
                io:println(add(3, 4));
                """);
    assertEquals("7", output);
  }

  @Test
  void returnValue() {
    final var output =
        TestHelper.run(
            """
                program test;
                import io;
                string greet(string name) {
                    return `Hello, ${name}!`;
                }
                io:println(greet("World"));
                """);
    assertEquals("Hello, World!", output);
  }

  @Test
  void requiresContractPass() {
    final var output =
        TestHelper.run(
            """
                program test;
                import io;
                int abs(int x)
                    requires x > -100
                {
                    int result = if (x >= 0) then x else 0 - x;
                    return result;
                }
                io:println(abs(5));
                io:println(abs(-3));
                """);
    assertEquals("5\n3", output);
  }

  @Test
  void requiresContractFail() {
    assertThrows(
        InterpreterException.class,
        () ->
            TestHelper.run(
                """
                program test;
                import io;
                int positive(int x)
                    requires x >= 0
                {
                    return x * 2;
                }
                io:println(positive(-1));
                """));
  }

  @Test
  void ensuresContract() {
    final var output =
        TestHelper.run(
            """
                program test;
                import io;
                int double(int x)
                    ensures result > 0
                {
                    return x * 2;
                }
                io:println(double(5));
                """);
    assertEquals("10", output);
  }

  @Test
  void assertPass() {
    final var output =
        TestHelper.run(
            """
                program test;
                import io;
                assert 1 + 1 == 2;
                assert true;
                io:println("ok");
                """);
    assertEquals("ok", output);
  }

  @Test
  void assertFail() {
    assertThrows(
        InterpreterException.class,
        () ->
            TestHelper.run(
                """
                program test;
                assert false;
                """));
  }

  @Test
  void fibonacci() {
    final var output =
        TestHelper.run(
            """
                program test;
                import io;
                import std;
                int fib(int n) {
                    int a = 0;
                    int b = 1;
                    int result = 0;
                    for i in std:range(n) {
                        result = a;
                        int temp = a + b;
                        a = b;
                        b = temp;
                    }
                    return result;
                }
                io:println(fib(0));
                io:println(fib(1));
                io:println(fib(5));
                io:println(fib(10));
                """);
    assertEquals("0\n0\n3\n34", output);
  }

  @Test
  void constParameter() {
    final var output =
        TestHelper.run(
            """
                program test;
                import io;
                int compute(const int factor) {
                    return factor * 2;
                }
                io:println(compute(21));
                """);
    assertEquals("42", output);
  }

  @Test
  void multipleCallsSameFunction() {
    final var output =
        TestHelper.run(
            """
                program test;
                import io;
                int square(int x) { return x * x; }
                io:println(square(3));
                io:println(square(5));
                io:println(square(0));
                """);
    assertEquals("9\n25\n0", output);
  }

  // ======================== Early return (Finding #4) ========================

  @Test
  void earlyReturnWithTrailingCode() {
    final var output =
        TestHelper.run(
            """
                program test;
                import io;
                int early(int x) {
                    return x * 2;
                    int dead = 0;
                }
                io:println(early(21));
                """);
    assertEquals("42", output);
  }

  @Test
  void measuresStackRecoversAfterEnsuresFailure() {
    final var source =
        """
                program test;
                import io;
                int negate(int x) { return 0 - x; }
                int countdown(int n)
                    ensures result >= 0
                    decreases(n)
                {
                    return if (n <= 0) then negate(1) else countdown(n - 1);
                }
                io:println(countdown(3));
                """;
    assertThrows(InterpreterException.class, () -> TestHelper.run(source));
  }

  @Test
  void measuresStackRecoversAfterEnsuresFailureBytecode() {
    final var source =
        """
                program test;
                import io;
                int negate(int x) { return 0 - x; }
                int countdown(int n)
                    ensures result >= 0
                    decreases(n)
                {
                    return if (n <= 0) then negate(1) else countdown(n - 1);
                }
                io:println(countdown(3));
                """;
    assertThrows(BytecodeException.class, () -> TestHelper.bytecode(source));
  }
}
