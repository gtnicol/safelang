package io.safelang;

import static org.junit.jupiter.api.Assertions.*;

import io.safelang.analyzer.SemanticAnalyzer;
import io.safelang.analyzer.SemanticException;
import io.safelang.bytecode.*;
import io.safelang.compiler.bytecode.*;
import io.safelang.parser.SAFEParser;
import java.io.*;
import java.nio.file.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

/** Tests for all 7 language extensions (A1-A7). */
class LanguageExtensionTests {

  // ========== A1: Bitwise Operators ==========

  private static final String[] STDLIB_MODULES = {
    "io", "std", "math", "strings", "file", "collections",
    "option", "result", "stack", "queue", "sorting", "tree",
    "functional"
  };
  @TempDir Path directory;

  private static String runBytecode(final String source) {
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
    return capture.toString().stripTrailing();
  }

  @Test
  void bitwiseAnd() {
    assertEquals(
        "8",
        TestHelper.run(
            """
                program test;
                import io;
                io:println(12 & 10);
                """));
  }

  @Test
  void bitwiseOr() {
    assertEquals(
        "14",
        TestHelper.run(
            """
                program test;
                import io;
                io:println(12 | 10);
                """));
  }

  @Test
  void bitwiseXor() {
    assertEquals(
        "6",
        TestHelper.run(
            """
                program test;
                import io;
                io:println(12 ^ 10);
                """));
  }

  @Test
  void bitwiseNot() {
    assertEquals(
        "-1",
        TestHelper.run(
            """
                program test;
                import io;
                io:println(~0);
                """));
  }

  @Test
  void shiftLeft() {
    assertEquals(
        "8",
        TestHelper.run(
            """
                program test;
                import io;
                io:println(1 << 3);
                """));
  }

  // ========== A2: Range Step ==========

  @Test
  void shiftRight() {
    assertEquals(
        "4",
        TestHelper.run(
            """
                program test;
                import io;
                io:println(32 >> 3);
                """));
  }

  @Test
  void bitwiseCombined() {
    assertEquals(
        "5",
        TestHelper.run(
            """
                program test;
                import io;
                io:println((0xFF & 0x0F) ^ 10);
                """));
  }

  @Test
  void bitwiseInFunction() {
    assertEquals(
        "true",
        TestHelper.run(
            """
                program test;
                import io;
                boolean odd(int n) {
                    return (n & 1) == 1;
                }
                io:println(odd(7));
                """));
  }

  @Test
  void rangeWithStep() {
    assertEquals(
        "[1, 3, 5, 7, 9]",
        TestHelper.run(
            """
                program test;
                import io;
                int a = 1;
                int b = 10;
                io:println(a..b step 2);
                """));
  }

  @Test
  void rangeStepNegative() {
    assertEquals(
        "[10, 8, 6, 4, 2]",
        TestHelper.run(
            """
                program test;
                import io;
                int a = 10;
                int b = 1;
                io:println(a..b step 0 - 2);
                """));
  }

  // ========== A3: Selective Imports ==========

  @Test
  void rangeStepThree() {
    assertEquals(
        "[0, 3, 6, 9]",
        TestHelper.run(
            """
                program test;
                import io;
                int a = 0;
                int b = 10;
                io:println(a..b step 3);
                """));
  }

  @Test
  void rangeStepInForLoop() {
    assertEquals(
        "0\n2\n4",
        TestHelper.run(
            """
                program test;
                import io;
                int a = 0;
                int b = 5;
                for i in a..b step 2 {
                    io:println(i);
                }
                """));
  }

  @Test
  void rangeWithoutStep() {
    assertEquals(
        "[1, 2, 3, 4, 5]",
        TestHelper.run(
            """
                program test;
                import io;
                int a = 1;
                int b = 5;
                io:println(a..b);
                """));
  }

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

    final var interpreter = new io.safelang.interpreter.Interpreter();
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

  @Test
  void selectiveImportFunctions() throws IOException {
    writeModule(
        "helper",
        """
            module helper;
            public int double(int n) { return n * 2; }
            public int triple(int n) { return n * 3; }
            """);
    writeModule(
        "main",
        """
            program main;
            import io;
            import helper { double };
            io:println(helper:double(5));
            """);
    assertEquals("10", interpret("main"));
  }

  // ========== A4: Guard Conditions in Case ==========

  @Test
  void selectiveImportEnum() throws IOException {
    writeModule(
        "types",
        """
            module types;
            public enum Color { Red, Green, Blue }
            public enum Shape { Circle, Square }
            public int code(Color c) { return 1; }
            """);
    writeModule(
        "main",
        """
            program main;
            import io;
            import types { Color, code };
            Color c = Red;
            io:println(types:code(c));
            """);
    assertEquals("1", interpret("main"));
  }

  @Test
  void fullImportStillWorks() throws IOException {
    writeModule(
        "ops",
        """
            module ops;
            public int add(int a, int b) { return a + b; }
            public int mul(int a, int b) { return a * b; }
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

  @Test
  void guardOnEnumPattern() {
    assertEquals(
        "positive 5",
        TestHelper.run(
            """
                program test;
                import io;
                import std;
                enum Result { Ok(int), Err(string) }
                string classify(Result r) {
                    return case r of {
                        Ok(x) if x > 0: "positive " + std:str(x);
                        Ok(x): "non-positive " + std:str(x);
                        Err(m): "error: " + m;
                    };
                }
                io:println(classify(Ok(5)));
                """));
  }

  @Test
  void guardFallsThrough() {
    assertEquals(
        "non-positive 0",
        TestHelper.run(
            """
                program test;
                import io;
                import std;
                enum Result { Ok(int), Err(string) }
                string classify(Result r) {
                    return case r of {
                        Ok(x) if x > 0: "positive " + std:str(x);
                        Ok(x): "non-positive " + std:str(x);
                        Err(m): "error: " + m;
                    };
                }
                io:println(classify(Ok(0)));
                """));
  }

  // ========== A5: Exhaustiveness Checking ==========

  @Test
  void guardWithWildcard() {
    assertEquals(
        "big\nsmall",
        TestHelper.run(
            """
                program test;
                import io;
                string size(int n) {
                    return case n of {
                        _ if n > 100: "big";
                        _: "small";
                    };
                }
                io:println(size(200));
                io:println(size(5));
                """));
  }

  @Test
  void multipleGuards() {
    assertEquals(
        "fizzbuzz\nfizz\nbuzz\n7",
        TestHelper.run(
            """
                program test;
                import io;
                import std;
                string fizzbuzz(int n) {
                    return case n of {
                        _ if n - (n / 15) * 15 == 0: "fizzbuzz";
                        _ if n - (n / 3) * 3 == 0: "fizz";
                        _ if n - (n / 5) * 5 == 0: "buzz";
                        _: std:str(n);
                    };
                }
                io:println(fizzbuzz(15));
                io:println(fizzbuzz(9));
                io:println(fizzbuzz(10));
                io:println(fizzbuzz(7));
                """));
  }

  // ========== A6: Tuple Destructuring ==========

  @Test
  void exhaustiveCase() {
    assertDoesNotThrow(
        () ->
            TestHelper.run(
                """
                program test;
                enum Color { Red, Green, Blue }
                string name(Color c) {
                    return case c of {
                        Red: "red";
                        Green: "green";
                        Blue: "blue";
                    };
                }
                """));
  }

  @Test
  void exhaustiveWithWildcard() {
    assertDoesNotThrow(
        () ->
            TestHelper.run(
                """
                program test;
                enum Color { Red, Green, Blue }
                string name(Color c) {
                    return case c of {
                        Red: "red";
                        _: "other";
                    };
                }
                """));
  }

  @Test
  void tupleDestructure() {
    assertEquals(
        "1\nhello",
        TestHelper.run(
            """
                program test;
                import io;
                const (int, string) pair = (1, "hello");
                const (int, string) (a, b) = pair;
                io:println(a);
                io:println(b);
                """));
  }

  // ========== A7: Tail Call Optimization ==========

  @Test
  void tupleDestructureFromFunction() {
    assertEquals(
        "3\n7",
        TestHelper.run(
            """
                program test;
                import io;
                (int, int) minmax(int a, int b) {
                    return if (a < b) then (a, b) else (b, a);
                }
                const (int, int) (lo, hi) = minmax(7, 3);
                io:println(lo);
                io:println(hi);
                """));
  }

  @Test
  void tupleDestructureThreeElements() {
    assertEquals(
        "1\n2\n3",
        TestHelper.run(
            """
                program test;
                import io;
                const (int, int, int) (a, b, c) = (1, 2, 3);
                io:println(a);
                io:println(b);
                io:println(c);
                """));
  }

  @Test
  void tailRecursiveSum() {
    assertEquals(
        "5050",
        TestHelper.run(
            """
                program test;
                import io;
                int sum(int n, int acc) {
                    return if (n == 0) then acc else sum(n - 1, acc + n);
                }
                io:println(sum(100, 0));
                """));
  }

  // ========== A8: Constrained While Loop ==========

  @Test
  void tailRecursiveCountdown() {
    assertEquals(
        "0",
        TestHelper.run(
            """
                program test;
                import io;
                int countdown(int n) {
                    return if (n <= 0) then 0 else countdown(n - 1);
                }
                io:println(countdown(500));
                """));
  }

  @Test
  void nonTailRecursionStillWorks() {
    assertEquals(
        "120",
        TestHelper.run(
            """
                program test;
                import io;
                int factorial(int n) {
                    return if (n <= 1) then 1 else n * factorial(n - 1);
                }
                io:println(factorial(5));
                """));
  }

  @Test
  void whileBasicCountdown() {
    assertEquals(
        "0",
        TestHelper.run(
            """
                program test;
                import io;
                int x = 10;
                while (x > 0) bound (100) {
                    x = x - 1;
                }
                io:println(x);
                """));
  }

  @Test
  void whileBoundReached() {
    assertEquals(
        "95",
        TestHelper.run(
            """
                program test;
                import io;
                int x = 100;
                while (x > 0) bound (5) {
                    x = x - 1;
                }
                io:println(x);
                """));
  }

  @Test
  void whileConditionFalseAtStart() {
    assertEquals(
        "10",
        TestHelper.run(
            """
                program test;
                import io;
                int x = 10;
                while (x < 0) bound (100) {
                    x = x + 1;
                }
                io:println(x);
                """));
  }

  @Test
  void whileBoundZero() {
    assertEquals(
        "5",
        TestHelper.run(
            """
                program test;
                import io;
                int x = 5;
                while (x > 0) bound (0) {
                    x = x - 1;
                }
                io:println(x);
                """));
  }

  @Test
  void whileNested() {
    assertEquals(
        "100",
        TestHelper.run(
            """
                program test;
                import io;
                int total = 0;
                int i = 0;
                while (i < 10) bound (10) {
                    int j = 0;
                    while (j < 10) bound (10) {
                        total = total + 1;
                        j = j + 1;
                    }
                    i = i + 1;
                }
                io:println(total);
                """));
  }

  @Test
  void whileMutationAffectsCondition() {
    assertEquals(
        "1024",
        TestHelper.run(
            """
                program test;
                import io;
                int x = 1;
                while (x < 1000) bound (100) {
                    x = x * 2;
                }
                io:println(x);
                """));
  }

  @Test
  void whileInFunctionBody() {
    assertEquals(
        "6",
        TestHelper.run(
            """
                program test;
                import io;
                int sumTo(int n) {
                    int total = 0;
                    int i = 1;
                    while (i <= n) bound (1000) {
                        total = total + i;
                        i = i + 1;
                    }
                    return total;
                }
                io:println(sumTo(3));
                """));
  }

  @Test
  void whileNonBooleanConditionError() {
    assertThrows(
        SemanticException.class,
        () ->
            TestHelper.run(
                """
                program test;
                int x = 5;
                while (x) bound (10) {
                    x = x - 1;
                }
                """));
  }

  @Test
  void whileNonIntegerBoundError() {
    assertThrows(
        SemanticException.class,
        () ->
            TestHelper.run(
                """
                program test;
                int x = 5;
                while (x > 0) bound (10.5) {
                    x = x - 1;
                }
                """));
  }

  // ========== A9: Iterable User Types (Convention) ==========

  @Test
  void whileBytecodeParity() {
    final var source =
        """
                program test;
                import io;
                int x = 10;
                while (x > 0) bound (100) {
                    x = x - 1;
                }
                io:println(x);
                """;
    assertEquals("0", TestHelper.run(source));
    assertEquals("0", runBytecode(source));
  }

  @Test
  void whileBoundExhaustedBytecodeParity() {
    final var source =
        """
                program test;
                import io;
                int x = 100;
                while (x > 0) bound (3) {
                    x = x - 1;
                }
                io:println(x);
                """;
    assertEquals("97", TestHelper.run(source));
    assertEquals("97", runBytecode(source));
  }

  @Test
  void iterateConventionList() {
    assertEquals(
        "10\n20",
        TestHelper.run(
            """
                program test;
                import io;
                type Pair { int first; int second; }
                list<int> iterate(Pair p) {
                    return [p.first, p.second];
                }
                Pair p = Pair { first: 10, second: 20 };
                for x in iterate(p) {
                    io:println(x);
                }
                """));
  }

  // ========== Bytecode parity helper ==========

  @Test
  void iterateConventionWithLogic() {
    assertEquals(
        "1\n2\n3",
        TestHelper.run(
            """
                program test;
                import io;
                import std;
                type Range { int low; int high; }
                list<int> iterate(Range r) {
                    return std:span(r.low, r.high + 1);
                }
                Range r = Range { low: 1, high: 3 };
                for x in iterate(r) {
                    io:println(x);
                }
                """));
  }

  @Test
  void iterateConventionBytecodeParity() {
    final var source =
        """
                program test;
                import io;
                type Pair { int first; int second; }
                list<int> iterate(Pair p) {
                    return [p.first, p.second];
                }
                Pair p = Pair { first: 10, second: 20 };
                int total = 0;
                for x in iterate(p) {
                    total = total + x;
                }
                io:println(total);
                """;
    assertEquals("30", TestHelper.run(source));
    assertEquals("30", runBytecode(source));
  }
}
