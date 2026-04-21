package io.safelang;

import static org.junit.jupiter.api.Assertions.*;

import io.safelang.compiler.CompilerFrontEnd;
import io.safelang.compiler.wasm.WasmPipeline;
import io.safelang.interpreter.Interpreter;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Path;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Parity tests: run the same SAFE source through both the interpreter and the WASM pipeline,
 * asserting identical output. This ensures the new per-module WASM backend produces the same
 * results as the reference interpreter.
 */
class WasmParityTests {

  @BeforeAll
  static void check() {
    Assumptions.assumeTrue(wasmtime(), "wasmtime not available");
  }

  private static boolean wasmtime() {
    try {
      return new ProcessBuilder("wasmtime", "--version").start().waitFor() == 0;
    } catch (Exception exception) {
      return false;
    }
  }

  /**
   * Run the production frontend pipeline (parse → load all stdlib → analyze each module → analyze
   * main). Both {@link #interpret} and {@link #wasm} go through this so any divergence between them
   * reflects backend behaviour, not setup drift.
   */
  private static CompilerFrontEnd.ParseResult bootstrap(final String source) {
    final var options =
        SafeFrontend.Options.defaults()
            .withSource(Path.of("stdlib/io.safe"))
            .withPreloads(SafeFrontend.stdlibModules(), true);
    return SafeFrontend.bootstrap(source, options);
  }

  private static String interpret(final String source) {
    final var loaded = bootstrap(source);

    final var original = System.out;
    final var capture = new ByteArrayOutputStream();
    System.setOut(new PrintStream(capture));
    try {
      final var interpreter = new Interpreter();
      interpreter.setRegistry(loaded.registry());
      interpreter.interpret(loaded.program());
      return capture.toString().stripTrailing();
    } finally {
      System.setOut(original);
    }
  }

  private static String wasm(final String source) throws Exception {
    final var loaded = bootstrap(source);
    final var pipeline = new WasmPipeline(loaded.registry());
    return pipeline.execute(loaded.program());
  }

  private static void parity(final String source) throws Exception {
    final var expected = interpret(source);
    final var actual = wasm(source);
    assertEquals(expected, actual, "WASM output differs from interpreter");
  }

  // === Arithmetic ===

  @Nested
  class Arithmetic {
    @Test
    void addition() throws Exception {
      parity("program t; import io; io:println(`${3 + 4}`);");
    }

    @Test
    void subtraction() throws Exception {
      parity("program t; import io; io:println(`${10 - 3}`);");
    }

    @Test
    void multiplication() throws Exception {
      parity("program t; import io; io:println(`${6 * 7}`);");
    }

    @Test
    void division() throws Exception {
      parity("program t; import io; io:println(`${15 / 4}`);");
    }

    @Test
    void modulo() throws Exception {
      parity("program t; import io; io:println(`${17 % 5}`);");
    }

    @Test
    void negation() throws Exception {
      parity("program t; import io; io:println(`${0 - 42}`);");
    }

    @Test
    void precedence() throws Exception {
      parity("program t; import io; io:println(`${2 + 3 * 4}`);");
    }

    @Test
    void floatArithmetic() throws Exception {
      parity("program t; import io; io:println(`${1.5 + 2.5}`);");
    }

    @Test
    void intComparison() throws Exception {
      parity(
          """
          program t; import io;
          io:println(`${3 < 5}`);
          io:println(`${5 < 3}`);
          io:println(`${3 == 3}`);
          io:println(`${3 != 4}`);
          """);
    }

    @Test
    void bitwiseOps() throws Exception {
      parity(
          """
          program t; import io;
          io:println(`${7 & 3}`);
          io:println(`${5 | 2}`);
          io:println(`${6 ^ 3}`);
          io:println(`${1 << 3}`);
          io:println(`${16 >> 2}`);
          """);
    }
  }

  // === Strings ===

  @Nested
  class Strings {
    @Test
    void concatenation() throws Exception {
      parity(
          """
          program t; import io;
          io:println("hello" + " " + "world");
          """);
    }

    @Test
    void interpolation() throws Exception {
      parity(
          """
          program t; import io;
          int x = 42;
          io:println(`value is ${x}`);
          """);
    }

    @Test
    void multiInterpolation() throws Exception {
      parity(
          """
          program t; import io;
          string name = "world";
          int n = 3;
          io:println(`hello ${name}, ${n} times`);
          """);
    }

    @Test
    void equality() throws Exception {
      parity(
          """
          program t; import io;
          io:println(`${"abc" == "abc"}`);
          io:println(`${"abc" == "def"}`);
          io:println(`${"abc" != "def"}`);
          """);
    }
  }

  // === Control Flow ===

  @Nested
  class ControlFlowParity {
    @Test
    void ifThenElse() throws Exception {
      parity(
          """
          program t; import io;
          io:println(if (3 > 2) then "yes" else "no");
          io:println(if (1 > 2) then "yes" else "no");
          """);
    }

    @Test
    void forLoop() throws Exception {
      parity(
          """
          program t; import io;
          for i in 0..4 {
              io:println(`${i}`);
          }
          """);
    }

    @Test
    void whileLoop() throws Exception {
      parity(
          """
          program t; import io;
          int x = 0;
          while (x < 5) bound (10) {
              x = x + 1;
          }
          io:println(`${x}`);
          """);
    }

    @Test
    void shortCircuit() throws Exception {
      parity(
          """
          program t; import io;
          io:println(`${true && true}`);
          io:println(`${true && false}`);
          io:println(`${false || true}`);
          io:println(`${false || false}`);
          """);
    }

    @Test
    void doBlock() throws Exception {
      parity(
          """
          program t; import io;
          int x = do {
              int a = 10;
              int b = 20;
              a + b
          };
          io:println(`${x}`);
          """);
    }
  }

  // === Functions ===

  @Nested
  class Functions {
    @Test
    void simpleFunction() throws Exception {
      parity(
          """
          program t; import io;
          int double(int n) { return n * 2; }
          io:println(`${double(21)}`);
          """);
    }

    @Test
    void recursion() throws Exception {
      parity(
          """
          program t; import io;
          int fib(int n) {
              return if (n <= 1) then n else fib(n - 1) + fib(n - 2);
          }
          io:println(`${fib(10)}`);
          """);
    }

    @Test
    void multipleReturns() throws Exception {
      parity(
          """
          program t; import io;
          string grade(int score) {
              return if (score >= 90) then "A"
                     else if (score >= 80) then "B"
                     else if (score >= 70) then "C"
                     else "F";
          }
          io:println(grade(95));
          io:println(grade(85));
          io:println(grade(75));
          io:println(grade(50));
          """);
    }

    @Test
    void nestedCalls() throws Exception {
      parity(
          """
          program t; import io;
          int add(int a, int b) { return a + b; }
          int mul(int a, int b) { return a * b; }
          io:println(`${add(mul(3, 4), 5)}`);
          """);
    }
  }

  // === Collections ===

  @Nested
  class Collections {
    @Test
    void listIteration() throws Exception {
      parity(
          """
          program t; import io;
          list<int> items = [10, 20, 30];
          for x in items {
              io:println(`${x}`);
          }
          """);
    }

    @Test
    void mapAccess() throws Exception {
      parity(
          """
          program t; import io;
          map<string, int> ages = {"alice": 30, "bob": 25};
          io:println(`${ages["alice"]}`);
          io:println(`${ages["bob"]}`);
          """);
    }

    @Test
    void listIndex() throws Exception {
      parity(
          """
          program t; import io;
          list<string> names = ["a", "b", "c"];
          io:println(names[0]);
          io:println(names[2]);
          """);
    }

    @Test
    void rangeInclusive() throws Exception {
      parity(
          """
          program t; import io;
          for i in 1..3 {
              io:println(`${i}`);
          }
          """);
    }

    @Test
    void rangeStep() throws Exception {
      parity(
          """
          program t; import io;
          for i in 0..10 step 3 {
              io:println(`${i}`);
          }
          """);
    }
  }

  // === Enums & Structs ===

  @Nested
  class EnumsStructs {
    @Test
    void enumMatch() throws Exception {
      parity(
          """
          program t; import io;
          enum Shape { Circle(int), Rect(int, int) }
          Shape s = Circle(5);
          string msg = case s of {
              Circle(r): `circle r=${r}`;
              Rect(w, h): `rect ${w}x${h}`;
          };
          io:println(msg);
          """);
    }

    @Test
    void enumZeroArity() throws Exception {
      parity(
          """
          program t; import io;
          enum Light { Red, Yellow, Green }
          Light l = Green;
          string msg = case l of {
              Red: "stop";
              Yellow: "caution";
              Green: "go";
          };
          io:println(msg);
          """);
    }

    @Test
    void structFields() throws Exception {
      parity(
          """
          program t; import io;
          type Vec2 { int x; int y; }
          Vec2 v = Vec2 { x: 3, y: 7 };
          io:println(`${v.x}`);
          io:println(`${v.y}`);
          io:println(`${v.x + v.y}`);
          """);
    }

    @Test
    void caseLiteral() throws Exception {
      parity(
          """
          program t; import io;
          int x = 2;
          string msg = case x of {
              1: "one";
              2: "two";
              3: "three";
              _: "other";
          };
          io:println(msg);
          """);
    }

    @Test
    void caseGuard() throws Exception {
      parity(
          """
          program t; import io;
          enum Val { N(int) }
          Val v = N(5);
          string msg = case v of {
              N(x) if x > 0: "positive";
              N(x): "non-positive";
          };
          io:println(msg);
          """);
    }

    @Test
    void tupleDestructure() throws Exception {
      parity(
          """
          program t; import io;
          (int, string) pair = (42, "hello");
          const (n, s) = pair;
          io:println(`${n}`);
          io:println(s);
          """);
    }
  }

  // === Guards on all branch types ===

  @Nested
  class Guards {
    @Test
    void literalWithGuard() throws Exception {
      parity(
          """
          program t; import io;
          int x = 2;
          int extra = 10;
          string msg = case x of {
              2 if extra > 5: "two-big";
              2: "two-small";
              _: "other";
          };
          io:println(msg);
          """);
    }

    @Test
    void literalGuardFails() throws Exception {
      parity(
          """
          program t; import io;
          int x = 2;
          int extra = 1;
          string msg = case x of {
              2 if extra > 5: "two-big";
              2: "two-small";
              _: "other";
          };
          io:println(msg);
          """);
    }

    @Test
    void wildcardWithGuard() throws Exception {
      parity(
          """
          program t; import io;
          int x = 7;
          string msg = case x of {
              1: "one";
              _ if x > 5: "big";
              _: "small";
          };
          io:println(msg);
          """);
    }

    @Test
    void zeroArityVariantGuard() throws Exception {
      parity(
          """
          program t; import io;
          enum Light { Red, Yellow, Green }
          Light l = Red;
          int brightness = 100;
          string msg = case l of {
              Red if brightness > 50: "bright-red";
              Red: "dim-red";
              _: "other";
          };
          io:println(msg);
          """);
    }

    @Test
    void enumPatternGuard() throws Exception {
      parity(
          """
          program t; import io;
          enum Box { Val(int) }
          Box b = Val(3);
          string msg = case b of {
              Val(x) if x > 10: "big";
              Val(x) if x > 0: "small";
              Val(x): "zero-or-neg";
          };
          io:println(msg);
          """);
    }
  }

  // === Struct field isolation ===

  @Nested
  class StructFields {
    @Test
    void sameFieldNameDifferentStructs() throws Exception {
      parity(
          """
          program t; import io;
          type A { int value; int extra; }
          type B { int first; int value; }
          A a = A { value: 10, extra: 20 };
          B b = B { first: 30, value: 40 };
          io:println(`${a.value}`);
          io:println(`${a.extra}`);
          io:println(`${b.first}`);
          io:println(`${b.value}`);
          """);
    }

    @Test
    void functionCallReceiverUsesReturnedStructType() throws Exception {
      parity(
          """
          program t; import io;
          type A { int x; int y; }
          type B { int z; int x; }
          B makeB() { return B { z: 1, x: 99 }; }
          A makeA() { return A { x: 11, y: 22 }; }
          A unused = makeA();
          io:println(`${makeB().x}`);
          """);
    }

    @Test
    void nestedFieldAccess() throws Exception {
      parity(
          """
          program t; import io;
          type Inner { int n; }
          type Outer { Inner child; }
          Inner i = Inner { n: 42 };
          Outer o = Outer { child: i };
          io:println(`${o.child.n}`);
          """);
    }
  }

  // === Lambdas/Closures ===

  @Nested
  class Lambdas {
    @Test
    void simpleLambda() throws Exception {
      parity(
          """
          program t; import io;
          fn(int) -> int twice = fn(x) -> x * 2;
          io:println(`${twice(21)}`);
          """);
    }

    @Test
    void lambdaWithCapture() throws Exception {
      parity(
          """
          program t; import io;
          int base = 100;
          fn(int) -> int sum = fn(x) -> x + base;
          int result = sum(5);
          io:println(`${result}`);
          """);
    }

    @Test
    void lambdaInForLoop() throws Exception {
      parity(
          """
          program t; import io;
          list<fn() -> void> actions = [
              fn() -> io:println("a"),
              fn() -> io:println("b"),
              fn() -> io:println("c")
          ];
          for f in actions {
              f();
          }
          """);
    }
  }

  // === Cross-Module ===

  @Nested
  class CrossModule {
    @Test
    void ioModuleParity() throws Exception {
      parity(
          """
          program t; import io;
          io:println("hello");
          io:print("a");
          io:print("b");
          io:println("");
          """);
    }
  }

  /**
   * Phase 1B regression tests: struct field access on the result of an expression that previously
   * did not propagate its nominal type. Two structs with a field of the same name at different
   * offsets are used so that any silent miscompilation reads from the wrong slot and produces the
   * wrong value.
   */
  @Nested
  class StructFieldAccessThroughExpressions {

    @Test
    void fieldOnIfExpressionResult() throws Exception {
      // Outer has 'value' at offset 0; Inner has 'value' at offset 1.
      // If the visitor doesn't propagate Inner's type, the field offset
      // resolver will fall through and read offset 0 instead of offset 1,
      // returning the spacer (0) instead of the real value (99).
      parity(
          """
          program t; import io;
          type Outer { int value; int spacer; }
          type Inner { int spacer; int value; }
          Inner a = Inner { spacer: 0, value: 99 };
          Inner b = Inner { spacer: 0, value: 42 };
          int n = (if (true) then a else b).value;
          io:println(`${n}`);
          """);
    }

    @Test
    void fieldOnFunctionCallResult() throws Exception {
      parity(
          """
          program t; import io;
          type Outer { int value; int spacer; }
          type Inner { int spacer; int value; }
          Inner make() { return Inner { spacer: 0, value: 123 }; }
          io:println(`${make().value}`);
          """);
    }

    @Test
    void fieldOnCaseBranchResult() throws Exception {
      parity(
          """
          program t; import io;
          type Outer { int value; int spacer; }
          type Inner { int spacer; int value; }
          enum Tag { A, B }
          Inner pickA = Inner { spacer: 0, value: 11 };
          Inner pickB = Inner { spacer: 0, value: 22 };
          Tag tag = A;
          Inner picked = case tag of {
              A: pickA;
              B: pickB;
          };
          io:println(`${picked.value}`);
          """);
    }

    @Test
    void fieldOnDoExpressionResult() throws Exception {
      parity(
          """
          program t; import io;
          type Outer { int value; int spacer; }
          type Inner { int spacer; int value; }
          Inner picked = do {
              Inner i = Inner { spacer: 0, value: 55 };
              i
          };
          io:println(`${picked.value}`);
          """);
    }
  }
}
