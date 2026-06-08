package io.safelang.compiler.jvm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.safelang.SafeRuntime;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.StringWriter;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
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
  void rangeAritiesMatchInterpreter() {
    final var source =
        """
        program rangetest;
        import io;
        import std;

        io:println(std:range(5));
        io:println(std:range(2, 8));
        io:println(std:range(2, 8, 2));
        io:println(std:range(10, 0, -2));
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

  @Test
  void concurrentExecutionsAreIsolated() throws Exception {
    // Two programs share a recursive `sum` with a decreases measure. Run concurrently on separate
    // threads: per-thread runtime state (measure stacks, output) must stay isolated — otherwise the
    // interleaved decreases checks would trap or the captured output would be corrupted.
    final var bytesA = compile(concurrentProgram("proga", 10));
    final var bytesB = compile(concurrentProgram("progb", 20));
    final var outA = new StringWriter();
    final var outB = new StringWriter();
    final var errorA = new AtomicReference<Throwable>();
    final var errorB = new AtomicReference<Throwable>();
    final var start = new CountDownLatch(1);

    final var threadA = new Thread(() -> runConcurrently(bytesA, outA, start, errorA));
    final var threadB = new Thread(() -> runConcurrently(bytesB, outB, start, errorB));
    threadA.start();
    threadB.start();
    start.countDown();
    threadA.join();
    threadB.join();

    if (errorA.get() != null) {
      throw new AssertionError("thread A failed", errorA.get());
    }
    if (errorB.get() != null) {
      throw new AssertionError("thread B failed", errorB.get());
    }
    assertEquals("55\n".repeat(300), outA.toString());
    assertEquals("210\n".repeat(300), outB.toString());
  }

  private static String concurrentProgram(final String name, final int n) {
    return "program "
        + name
        + ";\nimport io;\nimport std;\n"
        + "int sum(int k) decreases(k) { return if (k <= 0) then 0 else k + sum(k - 1); }\n"
        + "for i in std:range(300) { io:println(sum("
        + n
        + ")); }\n";
  }

  private static byte[] compile(final String source) {
    final var parsed = SafeRuntime.parse(source, "test.safe", false, List.of());
    return new JvmCodeGenerator("io/safelang/generated/Program", parsed.registry())
        .generate(parsed.program());
  }

  private static void runConcurrently(
      final byte[] bytes,
      final StringWriter out,
      final CountDownLatch start,
      final AtomicReference<Throwable> error) {
    try {
      start.await();
      JvmRuntime.setOutput(out);
      try {
        final var loaded = new BytesLoader().define("io.safelang.generated.Program", bytes);
        loaded.getMethod("main", String[].class).invoke(null, (Object) new String[0]);
      } finally {
        JvmRuntime.clearOutput();
      }
    } catch (final Throwable failure) {
      error.set(failure);
    }
  }

  private static String compileAndRun(final String source) {
    final var bytes = compile(source);
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
