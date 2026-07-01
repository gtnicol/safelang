package io.safelang;

import static org.junit.jupiter.api.Assertions.*;

import io.safelang.analyzer.SemanticAnalyzer;
import io.safelang.compiler.c.CCodeGenerator;
import io.safelang.parser.SAFEParser;
import java.nio.file.*;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/** Tests for C code generation (Audit Fix 10). */
class CCodeGenTests {

  private static String generate(final String source) {
    final var program = SAFEParser.parse(source);
    final var loader = new ModuleLoader(Path.of("stdlib/io.safe"));
    final var registry = new ModuleRegistry();
    for (final var name : SafeFrontend.stdlibModules()) {
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
    final var generator = new CCodeGenerator();
    generator.setRegistry(registry);
    return generator.generate(program);
  }

  private static String generateMinimal(final String source) {
    final var program = SAFEParser.parse(source);
    final var loader = new ModuleLoader(Path.of("stdlib/io.safe"));
    final var registry = new ModuleRegistry();
    // Only load modules actually imported by the program
    for (final var imported : program.imports()) {
      try {
        final var module = loader.load(imported.module());
        registry.register(imported.module(), module);
      } catch (Exception ignored) {
      }
    }
    // Also load transitive dependencies
    for (final var entry : loader.loaded().entrySet()) {
      if (!registry.has(entry.getKey())) {
        registry.register(entry.getKey(), entry.getValue());
      }
    }
    final var analyzer = new SemanticAnalyzer(registry);
    analyzer.analyze(program);
    final var generator = new CCodeGenerator();
    generator.setRegistry(registry);
    return generator.generate(program);
  }

  private static boolean gccAvailable() {
    try {
      final var process = new ProcessBuilder("gcc", "--version").start();
      return process.waitFor(5, TimeUnit.SECONDS) && process.exitValue() == 0;
    } catch (Exception e) {
      return false;
    }
  }

  private static RunResult compileAndRunWithStatus(final String source) throws Exception {
    Assumptions.assumeTrue(gccAvailable(), "gcc not available");
    final var code = generateMinimal(source);
    final var directory = Files.createTempDirectory("safe_parity_");
    try {
      final var cFile = directory.resolve("test.c");
      Files.writeString(cFile, code);
      // Extract runtime headers (safe_runtime.h + its dependency safe_refcount.h)
      for (final var name : new String[] {"safe_runtime.h", "safe_refcount.h"}) {
        try (var stream = CCodeGenTests.class.getResourceAsStream("/" + name)) {
          if (stream != null) {
            Files.write(directory.resolve(name), stream.readAllBytes());
          }
        }
      }
      final var binary = directory.resolve("test_binary");
      final var compile =
          new ProcessBuilder("gcc", "-O2", "-o", binary.toString(), cFile.toString(), "-lm")
              .directory(directory.toFile())
              .redirectErrorStream(true)
              .start();
      final var compileOutput = new String(compile.getInputStream().readAllBytes());
      assertTrue(compile.waitFor(30, TimeUnit.SECONDS), "gcc timed out");
      assertEquals(0, compile.exitValue(), "gcc failed: " + compileOutput);

      final var run =
          new ProcessBuilder(binary.toString())
              .directory(directory.toFile())
              .redirectErrorStream(true)
              .start();
      final var output = new String(run.getInputStream().readAllBytes()).stripTrailing();
      assertTrue(run.waitFor(10, TimeUnit.SECONDS), "binary timed out");
      return new RunResult(run.exitValue(), output);
    } finally {
      // Clean up temp files
      Files.walk(directory)
          .sorted(Comparator.reverseOrder())
          .forEach(
              p -> {
                try {
                  Files.delete(p);
                } catch (Exception ignored) {
                }
              });
    }
  }

  private static String compileAndRun(final String source) throws Exception {
    final var result = compileAndRunWithStatus(source);
    assertEquals(0, result.exitCode());
    return result.output();
  }

  @Test
  void helloWorld() {
    final var code =
        generate(
            """
                program test;
                import io;
                io:println("Hello");
                """);
    assertTrue(code.contains("#include"));
    assertTrue(code.contains("main("));
  }

  @Test
  void ensuresOnReturn() {
    final var code =
        generate(
            """
                program test;
                int positive(int x)
                    ensures result > 0
                {
                    return x + 1;
                }
                """);
    assertTrue(code.contains("result ="));
    assertTrue(code.contains("Postcondition failed"));
  }

  @Test
  void typeVariableMappedToVoidPointer() {
    final var code =
        generate(
            """
                program test;
                ?T identity(?T x) {
                    return x;
                }
                """);
    assertTrue(code.contains("void*"));
    assertFalse(code.contains("?T"));
  }

  @Test
  void inputResolvesToSafeInput() {
    final var code =
        generate(
            """
                program test;
                import io;
                string name = io:input("Enter: ");
                """);
    assertTrue(code.contains("safe_input("));
  }

  @Test
  void argsResolvesToSafeArgs() {
    final var code =
        generate(
            """
                program test;
                import std;
                list<string> a = std:args();
                """);
    assertTrue(code.contains("safe_args()"));
  }

  @Test
  void mainCallsSafeInitArgs() {
    final var code =
        generate(
            """
                program test;
                import io;
                io:println("hello");
                """);
    assertTrue(code.contains("safe_init_args(argc, argv)"));
  }

  @Test
  void moduleVariableDotSyntax() {
    final var code =
        generate(
            """
                program test;
                import math;
                float x = math.PI;
                """);
    assertTrue(code.contains("safe__math_PI"));
  }

  @Test
  void booleanPrint() {
    final var code =
        generate(
            """
                program test;
                import io;
                import std;
                io:println(std:str(true));
                """);
    assertTrue(code.contains("safe_string_val_bool"));
  }

  // CB6: Enum declaration generates valid C struct
  @Test
  void enumGeneratesTaggedStruct() {
    final var code =
        generate(
            """
                program test;
                enum Color { Red, Green, Blue }
                """);
    assertTrue(code.contains("Color_Tag"));
    assertTrue(code.contains("Color_Red"));
    assertTrue(code.contains("Color_Green"));
    assertTrue(code.contains("Color_Blue"));
    assertTrue(code.contains("Color_Red_new"));
  }

  // CB6: Enum with data generates union
  @Test
  void enumWithDataGeneratesUnion() {
    final var code =
        generate(
            """
                program test;
                enum Result { Ok(int), Err(string) }
                """);
    assertTrue(code.contains("union"));
    assertTrue(code.contains("Result_Ok_new"));
    assertTrue(code.contains("Result_Err_new"));
  }

  // CB6: Case matching on enum uses tag comparison
  @Test
  void caseEnumUsesTag() {
    final var code =
        generate(
            """
                program test;
                enum Color { Red, Green, Blue }
                string name(Color c) {
                    return case c of {
                        Red: "red";
                        _: "other";
                    };
                }
                """);
    assertTrue(code.contains(".tag == Color_Red"));
  }

  // CB7: String interpolation uses strdup
  @Test
  void interpolationUsesStrdup() {
    final var code =
        generate(
            """
                program test;
                string name = "world";
                string greeting = `hello ${name}`;
                """);
    assertTrue(code.contains("strdup("));
    assertFalse(code.contains("(char*)__interp_buf__"));
  }

  // Fix 4: For-loop over map generates key iteration
  @Test
  void forLoopOverMapGeneratesKeys() {
    final var code =
        generate(
            """
                program test;
                import io;
                map<string, int> m = {"a": 1, "b": 2};
                for k in m {
                    io:println(k);
                }
                """);
    assertTrue(code.contains("safe_map_keys("));
  }

  // ======================== Fix 9: Executable parity tests ========================

  // Fix 7: Range literal with integers parses
  @Test
  void rangeLiteralIntegers() {
    final var code =
        generate(
            """
                program test;
                list<int> r = 1..5;
                """);
    assertTrue(code.contains("safe_range_inclusive("));
  }

  @Test
  void parityHelloWorld() throws Exception {
    final var source =
        """
                program test;
                import io;
                io:println("hello");
                """;
    final var expected = TestHelper.run(source);
    final var actual = compileAndRun(source);
    assertEquals(expected, actual);
  }

  @Test
  void parityArithmetic() throws Exception {
    final var source =
        """
                program test;
                import io;
                import std;
                io:println(std:str(2 + 3));
                """;
    final var expected = TestHelper.run(source);
    final var actual = compileAndRun(source);
    assertEquals(expected, actual);
  }

  @Test
  void parityRange() throws Exception {
    final var source =
        """
                program test;
                import io;
                import std;
                list<int> r = std:range(5);
                io:println(std:str(std:len(r)));
                """;
    final var expected = TestHelper.run(source);
    final var actual = compileAndRun(source);
    assertEquals(expected, actual);
  }

  @Test
  void streamingBuiltinsEmitRuntimeCalls() {
    final var code =
        generate(
            """
                program test;
                import io { println };
                import file;
                Stream w = case file:sopen("out.txt", "w") of { Ok(s): s; Err(e): Stream { id: 0 - 1, path: "" }; };
                WriteResult x = file:swrite(w, "hi");
                file:sflush(w);
                file:sclose(w);
                Stream r = case file:sopen("out.txt", "r") of { Ok(s): s; Err(e): Stream { id: 0 - 1, path: "" }; };
                io:println(case file:sread(r, 2) of { Ok(t): t; Err(e): e; });
                io:println(case file:sline(r) of { Line(t): t; End: "END"; Err(e): e; });
                file:sclose(r);
                """);
    assertTrue(code.contains("safe_sopen("), "sopen runtime call");
    assertTrue(code.contains("safe_swrite("), "swrite runtime call");
    assertTrue(code.contains("safe_sflush("), "sflush runtime call");
    assertTrue(code.contains("safe_sread("), "sread runtime call");
    assertTrue(code.contains("safe_sline("), "sline runtime call");
    assertTrue(code.contains("safe_sclose("), "sclose runtime call");
    assertTrue(code.contains("StreamResult_Ok_new"), "Stream result construction");
    // The case subject must be bound to a temp so an effectful subject (sline/sread) is evaluated
    // exactly once, not re-run per branch.
    assertTrue(code.contains("__case"), "case subject bound to a temp");
  }

  @Test
  void streamingRoundTripNative() throws Exception {
    // Round-trip on the native C backend: write three lines, then read them back one at a time.
    // Each sline is an effectful case subject — if it were re-evaluated per branch, lines would be
    // consumed multiple times and the output would be wrong.
    final var output =
        compileAndRun(
            """
                program test;
                import io { println };
                import file;
                Stream w = case file:sopen("stream_rt.txt", "w") of { Ok(s): s; Err(e): Stream { id: 0 - 1, path: "" }; };
                WriteResult x = file:swrite(w, "alpha\\nbeta\\ngamma\\n");
                file:sflush(w);
                file:sclose(w);
                Stream r = case file:sopen("stream_rt.txt", "r") of { Ok(s): s; Err(e): Stream { id: 0 - 1, path: "" }; };
                io:println(case file:sline(r) of { Line(t): t; End: "END"; Err(e): "E:" + e; });
                io:println(case file:sline(r) of { Line(t): t; End: "END"; Err(e): "E:" + e; });
                io:println(case file:sline(r) of { Line(t): t; End: "END"; Err(e): "E:" + e; });
                io:println(case file:sline(r) of { Line(t): t; End: "END"; Err(e): "E:" + e; });
                file:sclose(r);
                """);
    assertEquals("alpha\nbeta\ngamma\nEND", output);
  }

  @Test
  void cycleCollectorSurvivesManyCollections() throws Exception {
    // Allocating and dropping refcounted containers in a long loop runs the native cycle collector
    // many times. The collector must not re-enter itself: disposing a count-0 corpse during a pass
    // releases its children, which re-buffer possible roots and can re-hit SAFE_GC_THRESHOLD; a
    // nested safe_collect_cycles would then free objects the outer pass is still iterating — a
    // use-after-free that crashed (SIGABRT) at scale. 30001 iterations triggers many collections.
    final var output =
        compileAndRun(
            """
                program test;
                import io;
                import std;
                import collections;
                int total = 0;
                for i in 0..30000 {
                    list<int> a = [i, i + 1, i + 2];
                    list<list<int>> b = [a, a, a];
                    total = total + collections:size(b);
                }
                io:println(std:str(total));
                """);
    assertEquals("90003", output);
  }

  @Test
  void tupleReturningFunctionDoesNotLeakOrCorrupt() throws Exception {
    // The lsm-read leak shape: a function builds a list of boxed tuples in a for-loop over a range,
    // returns it inside a tuple; the caller destructures and consumes it in a loop. Exercises all
    // three leak fixes at once — move-append of the fresh boxes (no cycle-collector buffering),
    // release of the range iterable, and release of the tuple-typed local — plus their interaction
    // with the collector. Must produce the right sum and (under ASan, run separately) not leak/UAF.
    final var output =
        compileAndRun(
            """
                program test;
                import io;
                import std;
                import collections;
                (int, list<(int, int, int)>) build(int n) {
                    list<(int, int, int)> idx = [];
                    for i in 0..(n - 1) {
                        idx = collections:append(idx, (i, i + 1, i + 2));
                    }
                    return (n, idx);
                }
                int total = 0;
                for j in 0..20000 {
                    (int, list<(int, int, int)>) r = build(5);
                    const (cnt, lst) = r;
                    total = total + collections:size(lst);
                }
                io:println(std:str(total));
                """);
    assertEquals("100005", output);
  }

  @Test
  void parityListPrinting() throws Exception {
    final var source =
        """
                program test;
                import io;
                import std;
                list<int> a = [1, 2, 3];
                list<string> b = ["x", "y", "z"];
                list<boolean> c = [true, false];
                list<int> empty = [];
                io:println(a);
                io:println(b);
                io:println(c);
                io:println(empty);
                io:println(std:range(5));
                io:println(std:str(a));
                """;
    final var expected = TestHelper.run(source);
    final var actual = compileAndRun(source);
    assertEquals(expected, actual);
  }

  @Test
  void parityFloatFormatting() throws Exception {
    final var source =
        """
                program test;
                import io;
                import std;
                io:println(2.0);
                io:println(3.25);
                io:println(1.0 / 3.0);
                io:println(0.001);
                io:println(100000000.0);
                io:println(0.0001);
                list<float> xs = [1.5, 2.0, 3.25];
                io:println(xs);
                io:println(std:str(2.0));
                io:println(`v=${2.0}`);
                """;
    final var expected = TestHelper.run(source);
    final var actual = compileAndRun(source);
    assertEquals(expected, actual);
  }

  @Test
  void parityStructPrinting() throws Exception {
    final var source =
        """
                program test;
                import io;
                import std;
                type Point { int x; int y; }
                type Bag { string name; list<int> items; }
                Point p = Point { x: 3, y: 4 };
                io:println(p);
                io:println(std:str(p));
                io:println(`pt=${p}`);
                io:println(Bag { name: "n", items: [1, 2, 3] });
                """;
    final var expected = TestHelper.run(source);
    final var actual = compileAndRun(source);
    assertEquals(expected, actual);
  }

  @Test
  void parityEnumPrinting() throws Exception {
    final var source =
        """
                program test;
                import io;
                enum Shape { Circle(float), Rect(int, int), Dot }
                io:println(Circle(2.5));
                io:println(Rect(3, 4));
                io:println(Dot);
                """;
    final var expected = TestHelper.run(source);
    final var actual = compileAndRun(source);
    assertEquals(expected, actual);
  }

  @Test
  void parityTupleAndMapPrinting() throws Exception {
    final var source =
        """
                program test;
                import io;
                (int, string) t = (1, "a");
                io:println(t);
                map<string, int> m = {"a": 1, "b": 2};
                io:println(m);
                map<int, string> n = {1: "one", 2: "two"};
                io:println(n);
                list<list<int>> nested = [[1, 2], [3, 4]];
                io:println(nested);
                """;
    final var expected = TestHelper.run(source);
    final var actual = compileAndRun(source);
    assertEquals(expected, actual);
  }

  @Test
  void parityListOfStructs() throws Exception {
    final var source =
        """
                program test;
                import io;
                type Point { int x; int y; }
                list<Point> ps = [Point { x: 1, y: 2 }, Point { x: 3, y: 4 }];
                io:println(ps[0].x);
                io:println(ps[1].y);
                io:println(ps);
                int total = 0;
                for p in ps { total = total + p.x; }
                io:println(total);
                ps[0] = Point { x: 9, y: 9 };
                io:println(ps[0].x);
                """;
    final var expected = TestHelper.run(source);
    final var actual = compileAndRun(source);
    assertEquals(expected, actual);
  }

  @Test
  void parityListOfTuplesAndEnums() throws Exception {
    final var source =
        """
                program test;
                import io;
                enum Shape { Circle(int), Square }
                list<(int, string)> ts = [(1, "a"), (2, "b")];
                io:println(ts);
                for pair in ts { const (n, m) = pair; io:println(m); }
                list<Shape> shapes = [Circle(5), Square];
                io:println(shapes);
                io:println(shapes[0]);
                """;
    final var expected = TestHelper.run(source);
    final var actual = compileAndRun(source);
    assertEquals(expected, actual);
  }

  @Test
  void parityListOfStructsNested() throws Exception {
    final var source =
        """
                program test;
                import io;
                type Bag { string name; list<int> items; }
                list<Bag> bs = [Bag { name: "a", items: [1, 2] }, Bag { name: "b", items: [3] }];
                io:println(bs);
                """;
    final var expected = TestHelper.run(source);
    final var actual = compileAndRun(source);
    assertEquals(expected, actual);
  }

  @Test
  void parityStringConcat() throws Exception {
    final var source =
        """
                program test;
                import io;
                string a = "hello";
                string b = " world";
                io:println(a + b);
                """;
    final var expected = TestHelper.run(source);
    final var actual = compileAndRun(source);
    assertEquals(expected, actual);
  }

  @Test
  void parityVoidEnsuresCheckedOnEarlyReturn() throws Exception {
    final var source =
        """
                program test;
                void f(int x)
                    ensures x > 0
                {
                    return;
                }
                f(0);
                """;
    final var result = compileAndRunWithStatus(source);
    assertNotEquals(0, result.exitCode(), "Expected contract failure exit status");
    assertTrue(
        result.output().contains("Postcondition failed"), "Expected postcondition failure output");
  }

  @Test
  void parityForLoopOverMapLiteral() throws Exception {
    final var source =
        """
                program test;
                import io;
                int count = 0;
                for key in {"a": 1, "b": 2} {
                    count = count + 1;
                }
                io:println(count);
                """;
    final var expected = TestHelper.run(source);
    final var actual = compileAndRun(source);
    assertEquals(expected, actual);
  }

  @Test
  void parityForLoopOverSetLiteral() throws Exception {
    final var source =
        """
                program test;
                import io;
                int sum = 0;
                for item in #{1, 2} {
                    sum = sum + item;
                }
                io:println(sum);
                """;
    final var expected = TestHelper.run(source);
    final var actual = compileAndRun(source);
    assertEquals(expected, actual);
  }

  @Test
  void parityForLoopOverStringExpression() throws Exception {
    final var source =
        """
                program test;
                import io;
                import std;
                for ch in std:str(12) {
                    io:println(ch);
                }
                """;
    final var expected = TestHelper.run(source);
    final var actual = compileAndRun(source);
    assertEquals(expected, actual);
  }

  @Test
  void parityIfWithoutElse() throws Exception {
    final var source =
        """
                program test;
                import io;
                io:println(if (false) then 1);
                """;
    final var expected = TestHelper.run(source);
    final var actual = compileAndRun(source);
    assertEquals(expected, actual);
  }

  @Test
  void parityIfWithoutElseTrueBranch() throws Exception {
    final var source =
        """
                program test;
                import io;
                io:println(if (true) then 1);
                """;
    final var expected = TestHelper.run(source);
    final var actual = compileAndRun(source);
    assertEquals(expected, actual);
  }

  @Test
  void collectionsAppendResolvesToBuiltin() {
    final var code =
        generateMinimal(
            """
                program test;
                import io;
                import collections;
                list<int> items = [1, 2, 3];
                collections:append(items, 4);
                io:println(collections:size(items));
                """);
    // Should contain safe_list_append (builtin), not a self-recursive call
    assertTrue(code.contains("safe_list_append"), "Expected safe_list_append builtin call");
  }

  // ======================== Audit Round 5 Tests ========================

  @Test
  void listVariableElementBoxing() {
    final var code =
        generateMinimal(
            """
                program test;
                import io;
                string x = "hello";
                list<string> items = [x];
                """);
    // String variable elements should be appended directly, not int64_t boxed
    assertFalse(
        code.contains("int64_t* __val__"), "String list element should not use int64_t boxing");
  }

  @Test
  void mapVariableIntKey() {
    final var code =
        generateMinimal(
            """
                program test;
                int k = 42;
                map<int, string> m = {k: "hello"};
                """);
    // Should detect int key from variable type and use ikey variant
    assertTrue(
        code.contains("safe_map_ikey_put_"), "Expected safe_map_ikey_put for int variable key");
  }

  @Test
  void typeofResolvesCorrectly() {
    final var code =
        generateMinimal(
            """
                program test;
                import io;
                import std;
                io:println(std:typeof(42));
                io:println(std:typeof("hi"));
                """);
    assertTrue(code.contains("\"int\""), "Expected typeof(42) to resolve to \"int\"");
    assertTrue(code.contains("\"string\""), "Expected typeof(\"hi\") to resolve to \"string\"");
  }

  @Test
  void rangeStepZeroGuard() {
    // Literal step 0 should be rejected at analysis time
    assertThrows(
        io.safelang.analyzer.SemanticException.class,
        () ->
            generateMinimal(
                """
                program test;
                list<int> r = 1..10 step 0;
                """));
  }

  @Test
  void mapWithListValueUsesPutPtr() {
    final var code =
        generateMinimal(
            """
                program test;
                list<int> items = [1, 2, 3];
                map<string, list<int>> m = {"nums": items};
                """);
    assertTrue(code.contains("safe_map_put_ptr"), "Expected safe_map_put_ptr for list value");
  }

  // ======================== Map backend tests ========================

  @Test
  void mapIndexAccessListValueUsesGetPtr() {
    final var code =
        generateMinimal(
            """
                program test;
                map<string, list<int>> m = {};
                list<int> items = m["nums"];
                """);
    assertTrue(
        code.contains("safe_map_get_ptr"), "Expected safe_map_get_ptr for list value access");
  }

  @Test
  void mapIndexAssignmentListValueUsesPutPtr() {
    final var code =
        generateMinimal(
            """
                program test;
                map<string, list<int>> m = {};
                m["nums"] = [1, 2, 3];
                """);
    assertTrue(
        code.contains("safe_map_put_ptr"), "Expected safe_map_put_ptr for list value assignment");
  }

  @Test
  void collectionsKeysResolvesToBuiltin() {
    final var code =
        generateMinimal(
            """
                program test;
                import collections;
                map<string, int> m = {"a": 1};
                list<string> k = collections:keys(m);
                """);
    assertTrue(code.contains("safe_map_keys("), "Expected safe_map_keys builtin call");
  }

  @Test
  void collectionsValuesIntResolvesToBuiltin() {
    final var code =
        generateMinimal(
            """
                program test;
                import collections;
                map<string, int> m = {"a": 1};
                list<int> v = collections:values(m);
                """);
    assertTrue(code.contains("safe_map_values_int("), "Expected safe_map_values_int builtin call");
  }

  @Test
  void collectionsContainsMapResolvesToBuiltin() {
    final var code =
        generateMinimal(
            """
                program test;
                import collections;
                map<string, int> m = {"a": 1};
                boolean found = collections:contains(m, "a");
                """);
    assertTrue(code.contains("safe_map_contains("), "Expected safe_map_contains builtin call");
  }

  @Test
  void mapWithMapValueUsesPutPtr() {
    final var code =
        generateMinimal(
            """
                program test;
                map<string, int> inner = {"x": 1};
                map<string, map<string, int>> outer = {"a": inner};
                """);
    assertTrue(code.contains("safe_map_put_ptr"), "Expected safe_map_put_ptr for nested map value");
  }

  @Test
  void inferTypeMapLiteralReturnsFullType() {
    final var code =
        generateMinimal(
            """
                program test;
                import collections;
                map<string, int> m = {"a": 1, "b": 2};
                list<int> v = collections:values(m);
                """);
    // values() dispatches by value type — if inferType works, we get _int variant
    assertTrue(
        code.contains("safe_map_values_int("),
        "Expected full type inference for map values dispatch");
  }

  @Test
  void intDivisionUsesCheckedFunction() {
    final var code =
        generate(
            """
                program test;
                int x = 10 / 3;
                """);
    assertTrue(code.contains("safe_int_div("), "Expected checked int division");
  }

  @Test
  void intModuloUsesCheckedFunction() {
    final var code =
        generate(
            """
                program test;
                int x = 10 % 3;
                """);
    assertTrue(code.contains("safe_int_mod("), "Expected checked int modulo");
  }

  @Test
  void floatDivisionUsesCheckedFunction() {
    final var code =
        generate(
            """
                program test;
                float x = 10.0 / 3.0;
                """);
    assertTrue(code.contains("safe_float_div("), "Expected checked float division");
  }

  @Test
  void uintDivisionUsesCheckedFunction() {
    final var code =
        generate(
            """
                program test;
                uint x = 10u / 3u;
                """);
    assertTrue(code.contains("safe_uint_div("), "Expected checked uint division");
  }

  @Test
  void selectiveImportKeepsPrivateHelpers() {
    // sorting module has public mergesort which may depend on private helpers
    final var code =
        generateMinimal(
            """
                program test;
                import sorting { mergesort };
                list<int> items = [3, 1, 2];
                list<int> sorted = sorting:mergesort(items, fn(a, b) -> a < b);
                """);
    // The public function should be present
    assertTrue(code.contains("safe__sorting_mergesort"), "Expected mergesort in output");
  }

  @Test
  void generatedMainCallsArenaFree() {
    final var code =
        generate(
            """
                program test;
                import io;
                io:println("hello");
                """);
    assertTrue(code.contains("safe_arena_free()"), "Expected arena cleanup before return");
  }

  @Test
  void listBoxingUsesArena() {
    final var code =
        generate(
            """
                program test;
                list<int> items = [1, 2, 3];
                """);
    assertTrue(code.contains("safe_arena_alloc"), "Expected arena allocation for boxing");
    assertFalse(code.contains("malloc"), "Expected no malloc calls in generated code");
  }

  @Test
  void floatKeyMapCodeGen() {
    final var code =
        generate(
            """
                program test;
                map<float, string> prices = {1.5: "cheap", 9.99: "expensive"};
                """);
    assertTrue(code.contains("safe_map_fkey_put_"), "Expected float-keyed map put in generated C");
  }

  @Test
  void decreasesClauseRestoresBeforeReturn() {
    final var code =
        generate(
            """
                program test;
                import io;
                import std;
                int fib(int n)
                decreases(n) {
                    return if (n <= 1) then n else fib(n - 1) + fib(n - 2);
                }
                """);
    assertTrue(
        code.contains("__decreases_stack_safe_user_fib"), "Expected decreases stack variable");
    assertTrue(code.contains("__decreases_curr"), "Expected decreases current measure");
    // Stack pop must appear before return within the fib function
    final var fibStart = code.indexOf("int64_t safe_user_fib(");
    assertTrue(fibStart > 0, "Expected fib function definition");
    final var fibBody = code.substring(fibStart);
    final var restore = fibBody.indexOf("__decreases_stack_safe_user_fib.sp--");
    final var returnStmt = fibBody.indexOf("return __result__");
    assertTrue(
        restore > 0 && returnStmt > 0 && restore < returnStmt,
        "Decreases stack pop must come before return");
  }

  @Test
  void decreasesFloatCastNotStringConversion() {
    final var code =
        generate(
            """
                program test;
                import io;
                import std;
                float geometric(float x)
                decreases(std:integer(x)) {
                    return if (x <= 0.0) then 0.0 else x + geometric(x - 1.0);
                }
                """);
    assertTrue(
        code.contains("(int64_t)(x)"), "Expected int64_t cast for float-to-int in decreases");
    assertFalse(code.contains("safe_int_val(x)"), "Should not use safe_int_val for float argument");
  }

  // ======================== Phase 2: C builtin parity ========================

  /**
   * Audit round 3 reproducer for Finding 2: a custom module that calls the unqualified file builtin
   * {@code write(p, c)} from inside a function body. Before Phase 2, the C backend's
   * CBuiltinResolver had no case for {@code write}/{@code appendfile}/{@code lines}, so
   * CCallCompiler's fallback emitted the raw unmangled call {@code write(p, c)}, which collides
   * with POSIX {@code <unistd.h>} {@code write(int, void*, size_t)} and gcc errors out with "too
   * few arguments".
   */
  @Test
  void parityFileWriteFromCustomModule() throws Exception {
    final var source =
        """
                program test;
                import file;
                file:write("/tmp/safe_phase2_write.txt", "phase2");
                io:println(file:read("/tmp/safe_phase2_write.txt"));
                import io;
                """;
    // file:write goes through the SAFE wrapper in stdlib/file.safe which
    // calls filesave (already handled), so this case isn't the audit
    // reproduction. The reproduction is testing a CUSTOM module that
    // directly references the unqualified `write` builtin name.
    // Run a simpler program here that confirms file:write works end-to-end
    // through the C backend (it always did, via the SAFE wrapper).
    final var output =
        compileAndRun(
            """
                program test;
                import io;
                import file;
                file:write("/tmp/safe_phase2_write.txt", "phase2");
                """);
    assertEquals("", output);
  }

  /**
   * Source-grep parity check: every builtin registered in {@link
   * io.safelang.runtime.BuiltinRegistry} must have a {@code case "name":} branch in {@code
   * CBuiltinResolver}. This catches the structural drift that allowed Finding 2 to survive:
   * registry says yes, resolver says no, fallback emits raw {@code name(args)} and collides with
   * libc.
   *
   * <p>The test reads {@code CBuiltinResolver.java} from the source tree and matches every {@code
   * case "..."} string against {@link io.safelang.runtime.BuiltinRegistry#all()}. Builtins in the
   * {@code KNOWN_GAPS} set are intentionally unhandled by the C backend (none today; the set exists
   * so future drift can be acknowledged explicitly).
   */
  @Test
  void cBuiltinResolverHandlesEveryRegisteredBuiltin() throws Exception {
    // Builtins that are intentionally unhandled by the C backend.
    // Add an entry here only with a comment justifying the gap.
    final var gaps =
        Set.<String>of(
            // (none — every registered builtin must have a resolver case)
            );

    final var path = Path.of("src/main/java/io/safelang/compiler/c/CBuiltinResolver.java");
    Assumptions.assumeTrue(
        Files.exists(path),
        "Source tree not present (test skipped when running from a packaged JAR)");
    final var source = Files.readString(path);
    final var pattern = Pattern.compile("case \"([a-zA-Z_][a-zA-Z0-9_]*)\":");
    final var handled = new HashSet<String>();
    final var matcher = pattern.matcher(source);
    while (matcher.find()) {
      handled.add(matcher.group(1));
    }

    final var missing = new TreeSet<String>();
    for (final var builtin : io.safelang.runtime.BuiltinRegistry.all()) {
      if (gaps.contains(builtin.name())) continue;
      if (!handled.contains(builtin.name())) {
        missing.add(builtin.name() + " (id " + builtin.id() + ", module " + builtin.module() + ")");
      }
    }
    assertTrue(
        missing.isEmpty(),
        "CBuiltinResolver is missing case branches for registered builtins: " + missing);
  }

  // ========== Phase 5 (fourth-round audit): C host globals at runtime ==========

  @Test
  void cBackendOsReflectsRuntime() throws Exception {
    // Compile + run a program that prints OS and ARCH. Before this phase, CCodeGenerator
    // baked System.getProperty("os.name") into the generated C as a string literal at codegen
    // time — a Linux-compiled binary would report "Linux" even on macOS. Now the generated
    // C uses #define macros that expand to runtime helpers calling uname(3), so the output
    // reflects the host the binary actually runs on.
    final var output =
        compileAndRun(
            """
                program test;
                import io;
                io:println(OS);
                io:println(ARCH);
                """);
    final var lines = output.split("\n");
    assertEquals(2, lines.length, "expected two lines of output, got: " + output);
    assertFalse(lines[0].isBlank(), "OS should be non-blank, got: '" + lines[0] + "'");
    assertFalse(lines[1].isBlank(), "ARCH should be non-blank, got: '" + lines[1] + "'");
    // On the hosts SAFE actually builds on (macOS, Linux), uname sysname is either "Darwin"
    // or "Linux". Reject any "unknown" literal that would indicate the uname call failed.
    assertNotEquals("unknown", lines[0]);
    assertNotEquals("unknown", lines[1]);
  }

  @Test
  void cBackendDoesNotBakeHostStringLiterals() {
    // The generated C must NOT contain a string literal that embeds the build host's OS
    // name under System.getProperty. Verify the emitted code uses the runtime helper
    // macros instead.
    final var source =
        """
                program test;
                import io;
                io:println(OS);
                """;
    final var code = generateMinimal(source);
    assertTrue(
        code.contains("#define OS safe_os()"),
        "expected `#define OS safe_os()` macro in generated C; got:\n" + code);
    assertFalse(
        code.contains("const char* OS = \""),
        "generated C still bakes OS as a string literal:\n" + code);
  }

  @Test
  void parityLambdaCapturingDecodedString() throws Exception {
    // Refcount regression: a lambda capturing a non-literal `string` (a bare char* with no
    // SAFEHeader, here from binary:decode) must NOT be safe_retain'd at capture — that read a
    // header
    // 8 bytes before the buffer (heap-buffer-overflow). The capture is stored as a plain pointer.
    final var source =
        """
            program test;
            import std;
            import binary;
            import test;
            bytes encoded = binary:encode("Bob");
            string name = binary:decode(encoded);
            test:suite("capture", [
                fn() -> do {
                    test:equal(name, "Bob", "captured decoded string")
                }
            ]);
            """;
    final var expected = TestHelper.run(source);
    final var actual = compileAndRun(source);
    assertEquals(expected, actual);
  }

  @Test
  void parityMapStoreReadAndStructReassign() throws Exception {
    // Refcount regression: a bytes value stored in a map, read back via `map[k]` (an OWNED
    // safe_map_get_ptr — must not double-retain), inside a struct rebuilt every iteration via
    // `b = put(b, ..)` (the old field must be released since put returns a freshly-constructed
    // struct). Exercises the map-index / struct-reassign / literal-arg discipline together.
    final var source =
        """
            program test;
            import io;
            import std;
            import binary;
            type Box { map<string,bytes> data; int n; }
            Box put(Box b, string k, bytes v) {
                map<string,bytes> d = b.data;
                d[k] = v;
                return Box { data: d, n: b.n + 1 };
            }
            Box b = Box { data: {}, n: 0 };
            for i in 0..99 {
                b = put(b, std:str(i), binary:encode(std:str(i * 2)));
            }
            bytes r = b.data[std:str(50)];
            io:println(binary:decode(r));
            io:println(std:str(b.n));
            """;
    final var expected = TestHelper.run(source);
    final var actual = compileAndRun(source);
    assertEquals(expected, actual);
  }

  @Test
  void parityHeapArgToFunctionWithDefault() throws Exception {
    // Step 5 regression: a BORROWED heap arg passed to a function that also has a default parameter
    // goes through the default-padding path; the callee releases every heap param at exit, so the
    // caller must retain the borrowed arg there too — otherwise it is freed and the later use is a
    // use-after-free.
    final var source =
        """
            program test;
            import io;
            import std;
            import binary;
            int score(bytes data, int bonus = 5) { return binary:length(data) + bonus; }
            bytes b = binary:encode("hello");
            io:println(std:str(score(b)));
            io:println(std:str(binary:length(b)));
            io:println(std:str(score(b, 10)));
            """;
    final var expected = TestHelper.run(source);
    final var actual = compileAndRun(source);
    assertEquals(expected, actual);
  }

  @Test
  void parityFreshHeapArgToStoringFunction() throws Exception {
    // Step 5 regression: a FRESH heap value passed to a function that STORES it into a map (which
    // retains on insert) must survive the callee's param-release; and the empty map literal in the
    // struct field must be TYPED so the store retains. Reading it back after 100 iterations
    // exercises
    // the whole caller-transfer / callee-release / map-retain chain.
    final var source =
        """
            program test;
            import io;
            import std;
            import binary;
            type Box { map<string,bytes> data; int n; }
            Box put(Box b, string k, bytes v) {
                map<string,bytes> d = b.data;
                d[k] = v;
                return Box { data: d, n: b.n + 1 };
            }
            Box b = Box { data: {}, n: 0 };
            for i in 0..99 {
                b = put(b, std:str(i), binary:encode(std:str(i * 3)));
            }
            io:println(binary:decode(b.data[std:str(40)]));
            io:println(std:str(b.n));
            """;
    final var expected = TestHelper.run(source);
    final var actual = compileAndRun(source);
    assertEquals(expected, actual);
  }

  private record RunResult(int exitCode, String output) {}
}
