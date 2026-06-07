package io.safelang.compiler.jvm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.safelang.SafeRuntime;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Parity tests for the JVM backend: compile a SAFE program to bytecode, run it in-process, and
 * assert the output matches the tree-walking interpreter.
 */
class JvmCodeGeneratorTests {

  @Test
  void helloWorldMatchesInterpreter() {
    final var source =
        """
        program hello;
        import io;

        io:println("Hello, World!");
        io:println("SAFE Language v1.0");
        """;
    assertEquals(interpret(source), compileAndRun(source));
  }

  @Test
  void fibonacciMatchesInterpreter() {
    final var source =
        """
        program fibonacci;
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

        for i in std:range(15) {
            io:println(fib(i));
        }
        """;
    assertEquals(interpret(source), compileAndRun(source));
  }

  @Test
  void arithmeticAndConditionalsMatchInterpreter() {
    final var source =
        """
        program math;
        import io;

        int classify(int n) {
            return if (n < 0) then 0 - 1 else if (n == 0) then 0 else 1;
        }

        io:println(classify(0 - 7));
        io:println(classify(0));
        io:println(classify(42));
        io:println((3 + 4) * 2 - 1);
        io:println(10 % 3);
        """;
    assertEquals(interpret(source), compileAndRun(source));
  }

  @Test
  void enumsAndPatternMatchingMatchInterpreter() {
    final var source =
        """
        program shapes;
        import io;
        import std;

        enum Shape {
            Circle(int),
            Square(int),
            Unit
        }

        int area(Shape s) {
            return case s of {
                Circle(r): r * r * 3;
                Square(side): side * side;
                Unit: 1;
            };
        }

        io:println(std:str(area(Circle(10))));
        io:println(std:str(area(Square(4))));
        io:println(std:str(area(Unit)));
        """;
    assertEquals(interpret(source), compileAndRun(source));
  }

  @Test
  void whileLoopAndCollectionsMatchInterpreter() {
    final var source =
        """
        program loops;
        import io;
        import std;

        int sumTo(int n) {
            int total = 0;
            int i = 1;
            while (i <= n) bound (1000) {
                total = total + i;
                i = i + 1;
            }
            return total;
        }

        list<int> xs = [3, 1, 2];
        map<string, int> counts = {"a": 1, "b": 2};
        counts["a"] = 10;
        io:println(std:str(sumTo(100)));
        io:println(std:str(xs[0] + xs[2]));
        io:println(std:str(counts["a"] + counts["b"]));
        """;
    assertEquals(interpret(source), compileAndRun(source));
  }

  @Test
  void moduleFunctionsMatchInterpreter() {
    final var source =
        """
        program mods;
        import io;
        import std;
        import strings;
        import math;

        io:println(strings:reversed("SAFE"));
        io:println(strings:repeat("*", 4));
        io:println(std:str(math:factorial(5)));
        io:println(std:str(math:gcd(48, 18)));
        """;
    assertEquals(interpret(source), compileAndRun(source));
  }

  @Test
  void contractsAndDecreasesMatchInterpreter() {
    final var source =
        """
        program contracts;
        import io;
        import std;

        int countdown(int n) decreases(n) {
            return if (n <= 0) then 0 else 1 + countdown(n - 1);
        }

        io:println(std:str(countdown(7)));
        """;
    assertEquals(interpret(source), compileAndRun(source));
  }

  @Test
  void decreasesViolationTrapsLikeInterpreter() {
    final var source =
        """
        program bad;
        import io;
        import std;

        int bad(int n) decreases(n) {
            return if (n == 0) then 0 else bad(n);
        }

        io:println(std:str(bad(5)));
        """;
    assertThrows(RuntimeException.class, () -> compileAndRun(source));
  }

  private static String interpret(final String source) {
    return captured(() -> SafeRuntime.run(source, "test.safe", List.of(), false, List.of()));
  }

  private static String compileAndRun(final String source) {
    final var parsed = SafeRuntime.parse(source, "test.safe", false, List.of());
    final var bytes =
        new JvmCodeGenerator("io/safelang/generated/Program", parsed.registry())
            .generate(parsed.program());
    return captured(
        () -> {
          try {
            final var loaded = new BytesLoader().define("io.safelang.generated.Program", bytes);
            loaded.getMethod("main", String[].class).invoke(null, (Object) new String[0]);
          } catch (final ReflectiveOperationException exception) {
            throw new RuntimeException(unwrap(exception));
          }
        });
  }

  private static Throwable unwrap(final ReflectiveOperationException exception) {
    return exception.getCause() != null ? exception.getCause() : exception;
  }

  private static String captured(final Runnable action) {
    final var buffer = new ByteArrayOutputStream();
    final var original = System.out;
    System.setOut(new PrintStream(buffer));
    try {
      action.run();
    } finally {
      System.setOut(original);
    }
    return buffer.toString();
  }

  private static final class BytesLoader extends ClassLoader {
    Class<?> define(final String binaryName, final byte[] bytes) {
      return defineClass(binaryName, bytes, 0, bytes.length);
    }
  }
}
