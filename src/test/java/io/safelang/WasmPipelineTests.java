package io.safelang;

import static org.junit.jupiter.api.Assertions.*;

import io.safelang.compiler.wasm.WasmPipeline;
import io.safelang.parser.SAFEParser;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** End-to-end tests for the per-module WASM compilation pipeline. */
class WasmPipelineTests {

  @BeforeAll
  static void check() {
    Assumptions.assumeTrue(wasmtime(), "wasmtime not available");
  }

  private static boolean wasmtime() {
    try {
      final var process = new ProcessBuilder("wasmtime", "--version").start();
      return process.waitFor() == 0;
    } catch (Exception exception) {
      return false;
    }
  }

  private static String execute(final String source) throws Exception {
    return execute(source, WasmPipeline.RunOptions.defaults());
  }

  private static String execute(final String source, final WasmPipeline.RunOptions options)
      throws Exception {
    final var program = SAFEParser.parse(source);
    final var loader = new ModuleLoader(Path.of("stdlib/io.safe"));
    final var registry = new ModuleRegistry();

    // Load all stdlib modules — silently skip ones not present in this build.
    for (final var name : TestHelper.stdlibModules()) {
      try {
        registry.register(name, loader.load(name));
      } catch (final MissingModuleException ignored) {
      }
    }

    // Load any additional imports — also tolerate misses (some tests use
    // synthetic module names that don't exist on disk).
    for (final var imported : program.imports()) {
      if (!registry.has(imported.module())) {
        try {
          registry.register(imported.module(), loader.load(imported.module()));
        } catch (final MissingModuleException ignored) {
        }
      }
    }

    final var pipeline = new WasmPipeline(registry);
    return WasmPipeline.run(pipeline.compile(program), options);
  }

  /** Execute with custom module sources (name → source pairs, then main source last). */
  private static String executeWith(final String main, final String... modules) throws Exception {
    final var registry = new ModuleRegistry();
    // Load io module from stdlib (always needed) — tolerate absence.
    final var loader = new ModuleLoader(Path.of("stdlib/io.safe"));
    try {
      registry.register("io", loader.load("io"));
    } catch (final MissingModuleException ignored) {
    }

    // Register custom modules from source
    for (var i = 0; i < modules.length; i += 2) {
      final var name = modules[i];
      final var source = modules[i + 1];
      registry.register(name, SAFEParser.parse(source));
    }

    final var program = SAFEParser.parse(main);
    final var pipeline = new WasmPipeline(registry);
    return pipeline.execute(program);
  }

  // === Basic Tests ===

  @Nested
  class Basics {

    @Test
    void helloWorld() throws Exception {
      assertEquals(
          "Hello, World!",
          execute(
              """
          program hello;
          import io;
          io:println("Hello, World!");
          """));
    }

    @Test
    void multiplePrints() throws Exception {
      assertEquals(
          "Hello\nWorld",
          execute(
              """
          program multi;
          import io;
          io:println("Hello");
          io:print("World");
          """));
    }

    @Test
    void integerArithmetic() throws Exception {
      assertEquals(
          "42",
          execute(
              """
          program math;
          import io;
          int x = 6 * 7;
          io:println(`${x}`);
          """));
    }

    @Test
    void stringConcatenation() throws Exception {
      assertEquals(
          "hello world",
          execute(
              """
          program concat;
          import io;
          string a = "hello";
          string b = " world";
          io:println(a + b);
          """));
    }

    @Test
    void booleanLogic() throws Exception {
      assertEquals(
          "true",
          execute(
              """
          program logic;
          import io;
          boolean x = true && true;
          io:println(`${x}`);
          """));
    }

    @Test
    void ifThenElse() throws Exception {
      assertEquals(
          "yes",
          execute(
              """
          program cond;
          import io;
          int x = 5;
          string result = if (x > 3) then "yes" else "no";
          io:println(result);
          """));
    }

    @Test
    void forLoop() throws Exception {
      assertEquals(
          "0\n1\n2",
          execute(
              """
          program loop;
          import io;
          for i in range(3) {
              io:println(`${i}`);
          }
          """));
    }

    @Test
    void functionDefinition() throws Exception {
      assertEquals(
          "25",
          execute(
              """
          program func;
          import io;
          int square(int n) {
              return n * n;
          }
          io:println(`${square(5)}`);
          """));
    }
  }

  // === Phase 3: Module-qualified resolution ===

  @Nested
  class ModuleResolution {

    @Test
    void ioModulePrintln() throws Exception {
      assertEquals(
          "hello",
          execute(
              """
          program test;
          import io;
          io:println("hello");
          """));
    }

    @Test
    void ioModulePrint() throws Exception {
      assertEquals(
          "ab",
          execute(
              """
          program test;
          import io;
          io:print("a");
          io:print("b");
          """));
    }

    @Test
    void userFunctionWithModuleCall() throws Exception {
      assertEquals(
          "hi\nbye",
          execute(
              """
          program test;
          import io;
          void say(string msg) {
              io:println(msg);
          }
          say("hi");
          say("bye");
          """));
    }

    @Test
    void customModuleFunction() throws Exception {
      assertEquals(
          "15",
          executeWith(
              """
          program test;
          import io;
          import mymath;
          int x = mymath:add(7, 8);
          io:println(`${x}`);
          """,
              "mymath",
              """
          module mymath;
          public int add(int a, int b) {
              return a + b;
          }
          """));
    }

    @Test
    void customModuleConstant() throws Exception {
      assertEquals(
          "42",
          executeWith(
              """
          program test;
          import io;
          import cfg;
          io:println(`${cfg.ANSWER}`);
          """,
              "cfg",
              """
          module cfg;
          public const int ANSWER = 42;
          """));
    }

    @Test
    void customModuleEnum() throws Exception {
      assertEquals(
          "found: 7",
          executeWith(
              """
          program test;
          import io;
          import types;
          types:Result r = types:Ok(7);
          string msg = case r of {
              Ok(v): "found: " + `${v}`;
              Err(e): "error: " + e;
          };
          io:println(msg);
          """,
              "types",
              """
          module types;
          public enum Result { Ok(int), Err(string) }
          public Result ok(int v) { return Ok(v); }
          """));
    }

    @Test
    void transitiveModuleConstants() throws Exception {
      assertEquals(
          "99",
          executeWith(
              """
          program test;
          import io;
          import a;
          int x = a:get();
          io:println(`${x}`);
          """,
              "b",
              """
          module b;
          const int SECRET = 99;
          public int value() { return SECRET; }
          """,
              "a",
              """
          module a;
          import b;
          public int get() { return b:value(); }
          """));
    }

    @Test
    void multipleCustomModules() throws Exception {
      assertEquals(
          "hello world!!!",
          executeWith(
              """
          program test;
          import io;
          import greet;
          import transform;
          string msg = greet:hello("world");
          io:println(transform:exclaim(msg));
          """,
              "greet",
              """
          module greet;
          public string hello(string name) {
              return "hello " + name;
          }
          """,
              "transform",
              """
          module transform;
          public string exclaim(string text) {
              return text + "!!!";
          }
          """));
    }

    @Test
    void zeroArityBuiltinWrapper() throws Exception {
      // Reproduces std.safe pattern: public int time() { return time(); }
      executeWith(
          """
          program test;
          import io;
          import mymod;
          int t = mymod:now();
          io:println(if (t > 0) then "ok" else "ok");
          """,
          "mymod",
          """
          module mymod;
          public int now() {
              return time();
          }
          """);
    }

    @Test
    void importedEnumPatternUsesSubjectType() throws Exception {
      assertEquals(
          "ok 7",
          executeWith(
              """
          program test;
          import io;
          import a;
          import b;
          b:B x = b:makeOk(7);
          string msg = case x of {
              Ok(v): "ok " + `${v}`;
              Err: "err";
          };
          io:println(msg);
          """,
              "a",
              """
          module a;
          public enum A { Err, Ok(int) }
          """,
              "b",
              """
          module b;
          public enum B { Ok(int), Err }
          public B makeOk(int x) {
              return Ok(x);
          }
          """));
    }
  }

  @Nested
  class StdlibBuiltins {

    @Test
    void stdArgsReadsWasmtimeArguments() throws Exception {
      assertEquals(
          "2\nalpha\nbeta",
          execute(
              """
          program test;
          import io;
          import std;
          list<string> xs = std:args();
          io:println(`${len(xs)}`);
          io:println(xs[0]);
          io:println(xs[1]);
          """,
              new WasmPipeline.RunOptions(
                  List.of("alpha", "beta"), null, Map.of(), List.of("/tmp", "."))));
    }

    @Test
    void ioInputReadsStdin() throws Exception {
      assertEquals(
          "Name: Ada",
          execute(
              """
          program test;
          import io;
          string name = io:input("Name: ");
          io:println(name);
          """,
              new WasmPipeline.RunOptions(List.of(), "Ada\n", Map.of(), List.of("/tmp", "."))));
    }

    @Test
    void envModuleReadsWasiEnvironment() throws Exception {
      assertEquals(
          "present",
          execute(
              """
          program test;
          import io;
          import env;
          io:println(env:get("SAFE_WASM_TEST_ENV"));
          """,
              new WasmPipeline.RunOptions(
                  List.of(), null, Map.of("SAFE_WASM_TEST_ENV", "present"), List.of("/tmp", "."))));
    }

    @Test
    void binarySizeUsesPathContract() throws Exception {
      final var file = Files.createTempFile("safe_bsize_", ".bin");
      Files.write(file, new byte[] {1, 2, 3, 4, 5, 6});
      try {
        final var path = file.toAbsolutePath().toString().replace("\\", "\\\\");
        assertEquals(
            "6",
            execute(
                """
            program test;
            import io;
            import binary;
            int size = binary:size("%s");
            io:println(`${size}`);
            """
                    .formatted(path),
                new WasmPipeline.RunOptions(
                    List.of(), null, Map.of(), List.of("/tmp", ".", file.getParent().toString()))));
      } finally {
        Files.deleteIfExists(file);
      }
    }
  }

  // === Phase 4: Enums, structs, pattern matching ===

  @Nested
  class EnumsAndStructs {

    @Test
    void localEnumPatternMatch() throws Exception {
      assertEquals(
          "found: 42",
          execute(
              """
          program test;
          import io;
          enum Result { Ok(int), Err(string) }
          Result r = Ok(42);
          string msg = case r of {
              Ok(v): "found: " + `${v}`;
              Err(e): "error: " + e;
          };
          io:println(msg);
          """));
    }

    @Test
    void enumZeroArityVariant() throws Exception {
      assertEquals(
          "none",
          execute(
              """
          program test;
          import io;
          enum Option { Some(int), None }
          Option x = None;
          string msg = case x of {
              Some(v): `${v}`;
              None: "none";
          };
          io:println(msg);
          """));
    }

    @Test
    void structCreationAndFieldAccess() throws Exception {
      assertEquals(
          "3\n4",
          execute(
              """
          program test;
          import io;
          type Point { int x; int y; }
          Point p = Point(3, 4);
          io:println(`${p.x}`);
          io:println(`${p.y}`);
          """));
    }

    @Test
    void fieldAccessOnFunctionCallUsesReturnedStructType() throws Exception {
      assertEquals(
          "99",
          execute(
              """
          program test;
          import io;
          type A { int x; int y; }
          type B { int z; int x; }
          B makeB() { return B { z: 1, x: 99 }; }
          A makeA() { return A { x: 11, y: 22 }; }
          A unused = makeA();
          io:println(`${makeB().x}`);
          """));
    }

    @Test
    void nestedEnum() throws Exception {
      assertEquals(
          "inner: 7",
          execute(
              """
          program test;
          import io;
          enum Wrapper { Box(int) }
          Wrapper w = Box(7);
          string msg = case w of {
              Box(v): "inner: " + `${v}`;
          };
          io:println(msg);
          """));
    }

    @Test
    void caseWithGuard() throws Exception {
      assertEquals(
          "positive",
          execute(
              """
          program test;
          import io;
          enum Val { Num(int) }
          Val v = Num(5);
          string msg = case v of {
              Num(x) if x > 0: "positive";
              Num(x): "non-positive";
          };
          io:println(msg);
          """));
    }

    @Test
    void caseWithWildcard() throws Exception {
      assertEquals(
          "other",
          execute(
              """
          program test;
          import io;
          enum Color { Red, Green, Blue }
          Color c = Blue;
          string msg = case c of {
              Red: "red";
              _: "other";
          };
          io:println(msg);
          """));
    }

    @Test
    void caseOnLiteral() throws Exception {
      assertEquals(
          "two",
          execute(
              """
          program test;
          import io;
          int x = 2;
          string msg = case x of {
              1: "one";
              2: "two";
              3: "three";
              _: "other";
          };
          io:println(msg);
          """));
    }

    @Test
    void multipleStructsFieldIndependence() throws Exception {
      // Issue 5 repro: two structs share a field name at different offsets
      assertEquals(
          "20\n10",
          execute(
              """
          program test;
          import io;
          type A { int shared; int other; }
          type B { int first; int shared; }
          A a = A(10, 20);
          B b = B(30, 40);
          io:println(`${a.other}`);
          io:println(`${a.shared}`);
          """));
    }
  }

  // === Phase 5: Control flow & advanced features ===

  @Nested
  class ControlFlow {

    @Test
    void whileLoop() throws Exception {
      assertEquals(
          "10",
          execute(
              """
          program test;
          import io;
          int x = 0;
          while (x < 10) bound (100) {
              x = x + 1;
          }
          io:println(`${x}`);
          """));
    }

    @Test
    void nestedForLoops() throws Exception {
      assertEquals(
          "0,0\n0,1\n1,0\n1,1",
          execute(
              """
          program test;
          import io;
          for i in 0..1 {
              for j in 0..1 {
                  io:println(`${i},${j}`);
              }
          }
          """));
    }

    @Test
    void rangeWithStep() throws Exception {
      assertEquals(
          "0\n2\n4",
          execute(
              """
          program test;
          import io;
          for i in 0..4 step 2 {
              io:println(`${i}`);
          }
          """));
    }

    @Test
    void descendingRangeWithStep() throws Exception {
      assertEquals(
          "3\n2\n1",
          execute(
              """
          program test;
          import io;
          for i in 3..1 step -1 {
              io:println(`${i}`);
          }
          """));
    }

    @Test
    void doBlock() throws Exception {
      assertEquals(
          "15",
          execute(
              """
          program test;
          import io;
          int result = do {
              int a = 5;
              int b = 10;
              a + b
          };
          io:println(`${result}`);
          """));
    }

    @Test
    void tupleCreationAndDestructuring() throws Exception {
      assertEquals(
          "3\nhello",
          execute(
              """
          program test;
          import io;
          (int, string) pair = (3, "hello");
          const (a, b) = pair;
          io:println(`${a}`);
          io:println(b);
          """));
    }

    @Test
    void listOperations() throws Exception {
      assertEquals(
          "1\n2\n3",
          execute(
              """
          program test;
          import io;
          list<int> items = [1, 2, 3];
          for x in items {
              io:println(`${x}`);
          }
          """));
    }

    @Test
    void mapLiteral() throws Exception {
      assertEquals(
          "hello",
          execute(
              """
          program test;
          import io;
          map<string, string> m = {"key": "hello"};
          io:println(m["key"]);
          """));
    }

    @Test
    void bitwiseOperators() throws Exception {
      assertEquals(
          "3",
          execute(
              """
          program test;
          import io;
          int x = 7 & 3;
          io:println(`${x}`);
          """));
    }

    @Test
    void unaryBitwiseNot() throws Exception {
      assertEquals(
          "-1",
          execute(
              """
          program test;
          import io;
          io:println(`${~0}`);
          """));
    }

    @Test
    void inOperatorDispatchesByContainerType() throws Exception {
      assertEquals(
          "true\ntrue\nfalse",
          execute(
              """
          program test;
          import io;
          io:println(`${"b" in "abc"}`);
          io:println(`${"a" in {"a": 1}}`);
          io:println(`${9 in #{1, 2, 3}}`);
          """));
    }

    @Test
    void forLoopOverString() throws Exception {
      assertEquals(
          "a\nb",
          execute(
              """
          program test;
          import io;
          for ch in "ab" {
              io:println(ch);
          }
          """));
    }

    @Test
    void stringIndexAccessReturnsCharacterStrings() throws Exception {
      assertEquals(
          "n\nl",
          execute(
              """
          program test;
          import io;
          string text = "null";
          io:println(text[0]);
          io:println(text[3]);
          """));
    }

    @Test
    void forLoopOverMapKeys() throws Exception {
      assertEquals(
          "a\nb",
          execute(
              """
          program test;
          import io;
          map<string, int> items = {"a": 1, "b": 2};
          for key in items {
              io:println(key);
          }
          """));
    }

    @Test
    void collectionsBuiltinsSizeRemoveAndSort() throws Exception {
      assertEquals(
          "3\n10\n30\n1\n2\n3",
          execute(
              """
          program test;
          import io;
          import collections;
          int size = collections:size([1, 2, 3]);
          io:println(`${size}`);
          list<int> removed = collections:remove([10, 20, 30], 1);
          for x in removed {
              io:println(`${x}`);
          }
          list<int> sorted = collections:sort([3, 1, 2]);
          for x in sorted {
              io:println(`${x}`);
          }
          """));
    }

    @Test
    void ifExpressionInsideForLoopBody() throws Exception {
      assertEquals(
          "odd\neven\nodd",
          execute(
              """
          program test;
          import io;
          for i in [1, 2, 3] {
              io:println(if (i % 2 == 0) then "even" else "odd");
          }
          """));
    }

    @Test
    void localFunctionDefaultArgument() throws Exception {
      assertEquals(
          "5",
          execute(
              """
          program test;
          import io;
          int next(int base, int delta = base + 1) {
              return delta;
          }
          io:println(`${next(4)}`);
          """));
    }

    @Test
    void importedFunctionDefaultArgument() throws Exception {
      assertEquals(
          "wow!\n8",
          executeWith(
              """
          program test;
          import io;
          import defaults;
          io:println(defaults:punctuate("wow"));
          int next = defaults:next(4);
          io:println(`${next}`);
          """,
              "defaults",
              """
          module defaults;
          public string punctuate(string text, string suffix = "!") {
              return text + suffix;
          }
          public int next(int base, int delta = base * 2) {
              return delta;
          }
          """));
    }

    @Test
    void jsonParseNull() throws Exception {
      assertEquals(
          "null",
          execute(
              """
          program test;
          import io;
          import json;
          ParseResult result = json:parse("null");
          io:println(case result of {
              Ok(value): json:kind(value);
              Err(message): message;
          });
          """));
    }

    @Test
    void binaryPackUnpackPatchAndCompare() throws Exception {
      assertEquals(
          "8\n42\n9\n0\n-1",
          execute(
              """
          program test;
          import io;
          import binary;
          bytes packed = binary:pack(42, 8);
          int length = binary:length(packed);
          int unpacked = binary:unpack(packed, 0, 8);
          bytes patched = binary:patch(packed, 0, binary:pack(9, 8));
          int repacked = binary:unpack(patched, 0, 8);
          int same = binary:compare(binary:encode("abc"), binary:encode("abc"));
          int different = binary:compare(binary:encode("abc"), binary:encode("abd"));
          io:println(`${length}`);
          io:println(`${unpacked}`);
          io:println(`${repacked}`);
          io:println(`${same}`);
          io:println(`${different}`);
          """));
    }

    @Test
    void recursiveFunction() throws Exception {
      assertEquals(
          "120",
          execute(
              """
          program test;
          import io;
          int factorial(int n) {
              return if (n <= 1) then 1 else n * factorial(n - 1);
          }
          io:println(`${factorial(5)}`);
          """));
    }

    @Test
    void multipleReturnPaths() throws Exception {
      assertEquals(
          "even",
          execute(
              """
          program test;
          import io;
          string classify(int n) {
              return if (n % 2 == 0) then "even" else "odd";
          }
          io:println(classify(4));
          """));
    }

    @Test
    void simpleLambda() throws Exception {
      assertEquals(
          "42",
          execute(
              """
          program test;
          import io;
          fn(int) -> int doubler = fn(x) -> x * 2;
          io:println(`${doubler(21)}`);
          """));
    }

    @Test
    void lambdaWithCapture() throws Exception {
      assertEquals(
          "15",
          execute(
              """
          program test;
          import io;
          int base = 10;
          fn(int) -> int adder = fn(x) -> x + base;
          io:println(`${adder(5)}`);
          """));
    }

    @Test
    void lambdaInList() throws Exception {
      assertEquals(
          "hello\nworld",
          execute(
              """
          program test;
          import io;
          list<fn() -> void> actions = [
              fn() -> io:println("hello"),
              fn() -> io:println("world")
          ];
          for f in actions {
              f();
          }
          """));
    }

    @Test
    void suitePatternWithCustomModules() throws Exception {
      // Mimics test_option.safe: suite function takes list of closures
      assertEquals(
          "[PASS] check\n[DONE]",
          executeWith(
              """
          program test;
          import io;
          import tester;
          tester:suite("checks", [
              fn() -> tester:ok(true, "check")
          ]);
          tester:done();
          """,
              "tester",
              """
          module tester;
          import io;
          public void suite(string name, list<fn() -> void> tests) {
              for t in tests {
                  t();
              }
          }
          public void ok(boolean condition, string label) {
              io:println(if (condition) then "[PASS] " + label else "[FAIL] " + label);
          }
          public void done() {
              io:println("[DONE]");
          }
          """));
    }

    @Test
    void lambdaCallingModuleFunction() throws Exception {
      assertEquals(
          "ok",
          executeWith(
              """
          program test;
          import io;
          import checker;
          int x = 5;
          fn() -> void action = fn() -> checker:verify(x > 0, "positive");
          action();
          """,
              "checker",
              """
          module checker;
          import io;
          public void verify(boolean ok, string label) {
              io:println(if (ok) then "ok" else "fail");
          }
          """));
    }
  }

  /**
   * Tests for lambda capture analysis (Phase 1C). Each test exercises a node type that the previous
   * hand-written walker missed (range, tuple, set, map, object creation, return, case guards,
   * nested lambdas, shadowing).
   */
  @Nested
  class LambdaCaptures {

    @Test
    void captureInsideTupleLiteral() throws Exception {
      assertEquals(
          "(7, 11)",
          execute(
              """
          program test;
          import io;
          int base = 4;
          fn(int) -> (int, int) f = fn(x) -> (x + base, x + base + 4);
          const (a, b) = f(3);
          io:println(`(${a}, ${b})`);
          """));
    }

    @Test
    void captureInsideSetLiteral() throws Exception {
      assertEquals(
          "3",
          execute(
              """
          program test;
          import io;
          import collections;
          int base = 100;
          fn(int) -> set<int> f = fn(x) -> #{x, x + base, base + 1};
          set<int> result = f(7);
          int n = collections:size(result);
          io:println(`${n}`);
          """));
    }

    @Test
    void captureInsideMapLiteral() throws Exception {
      assertEquals(
          "105",
          execute(
              """
          program test;
          import io;
          int base = 100;
          fn(int) -> map<string, int> f = fn(x) -> {"sum": x + base};
          io:println(`${f(5)["sum"]}`);
          """));
    }

    @Test
    void captureInsideRange() throws Exception {
      // Range bounds reference an outer constant — the lambda must capture it.
      // (Range nodes were missing from the old hand-written walker.)
      assertEquals(
          "15",
          execute(
              """
          program test;
          import io;
          int upper = 5;
          fn() -> int sum = fn() -> do {
              int total = 0;
              for i in 1..upper {
                  total = total + i;
              }
              total
          };
          io:println(`${sum()}`);
          """));
    }

    @Test
    void captureInsideObjectCreation() throws Exception {
      assertEquals(
          "3 7",
          execute(
              """
          program test;
          import io;
          type Point { int x; int y; }
          int yShift = 7;
          fn(int) -> Point makePoint = fn(x) -> Point { x: x, y: yShift };
          Point p = makePoint(3);
          io:println(`${p.x} ${p.y}`);
          """));
    }

    @Test
    void captureInsideDoBlockTrailingExpression() throws Exception {
      // The lambda body is a do-block whose final expression references an
      // outer constant. The walker must descend into the do-block tail.
      assertEquals(
          "12",
          execute(
              """
          program test;
          import io;
          int base = 7;
          fn(int) -> int f = fn(x) -> do {
              int doubled = x + x;
              doubled - x + base
          };
          io:println(`${f(5)}`);
          """));
    }

    @Test
    void captureInsideVariableInitializer() throws Exception {
      // Outer constant referenced inside a do-block VariableDeclarationNode
      // initializer — was a missing node type for the previous walker.
      assertEquals(
          "17",
          execute(
              """
          program test;
          import io;
          int base = 10;
          fn(int) -> int f = fn(x) -> do {
              int local = base + x;
              local + 2
          };
          io:println(`${f(5)}`);
          """));
    }

    @Test
    void captureInsideCaseGuard() throws Exception {
      assertEquals(
          "yes",
          execute(
              """
          program test;
          import io;
          enum Box { Open(int), Closed }
          int threshold = 10;
          fn(Box) -> string label = fn(b) -> case b of {
              Open(v) if v > threshold: "yes";
              Open(v): "no";
              Closed: "shut";
          };
          io:println(label(Open(15)));
          """));
    }

    @Test
    void parameterShadowsOuterConstant() throws Exception {
      // The lambda parameter shadows the outer `x`. The lambda body should
      // print the parameter value, not the outer one.
      assertEquals(
          "99",
          execute(
              """
          program test;
          import io;
          int x = 1;
          fn(int) -> int f = fn(x) -> x;
          io:println(`${f(99)}`);
          """));
    }

    @Test
    void localShadowsOuterConstantInsideLambda() throws Exception {
      // A do-block inside the lambda introduces a local that shadows the
      // outer constant. The shadowed name must NOT be captured.
      assertEquals(
          "99",
          execute(
              """
          program test;
          import io;
          int base = 1;
          fn() -> int f = fn() -> do {
              int base = 99;
              base
          };
          io:println(`${f()}`);
          """));
    }

    @Test
    void casePatternShadowsOuterConstant() throws Exception {
      // The pattern binding `x` shadows the outer `x` only inside the branch.
      assertEquals(
          "42",
          execute(
              """
          program test;
          import io;
          enum Holder { Hold(int) }
          int x = 1;
          fn(Holder) -> int f = fn(h) -> case h of {
              Hold(x): x;
          };
          io:println(`${f(Hold(42))}`);
          """));
    }

    @Test
    void nestedLambdaCapturesThroughOuter() throws Exception {
      // The inner lambda references `base`. The outer lambda has no parameter
      // shadowing it, so the outer must capture `base` and propagate it so
      // the inner closure can see it. Returning the inner from the outer and
      // calling it must produce base + arg.
      assertEquals(
          "13",
          execute(
              """
          program test;
          import io;
          int base = 10;
          fn() -> fn(int) -> int outer = fn() -> fn(y) -> y + base;
          fn(int) -> int inner = outer();
          io:println(`${inner(3)}`);
          """));
    }
  }
}
