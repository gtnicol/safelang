package io.safelang;

import static org.junit.jupiter.api.Assertions.*;

import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Closures capture only their free variables (not the whole lexical scope) — the semantics must be
 * preserved: capture-by-value, nested-lambda transitivity, per-iteration loop capture, and
 * capturing a closure that is stored in a variable and called by name (the case the whole-chain
 * snapshot caught incidentally and free-variable capture must not regress).
 */
class ClosureCaptureTests {

  private Path workDirectory;

  @BeforeEach
  void setup() throws Exception {
    workDirectory = Files.createTempDirectory("safe_closures_");
  }

  @AfterEach
  void teardown() throws Exception {
    try (var paths = Files.walk(workDirectory)) {
      paths.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
    }
  }

  private String run(final String source) throws Exception {
    final var main = workDirectory.resolve("main.safe");
    Files.writeString(main, source);
    final var options =
        SafeFrontend.Options.defaults()
            .withSource(main)
            .withPreloads(SafeFrontend.stdlibModules(), true)
            .withModulePath(List.of(workDirectory));
    final var result = SafeFrontend.bootstrap(source, options);
    final var capture = new StringWriter();
    final var interpreter = new io.safelang.interpreter.Interpreter();
    interpreter.setRegistry(result.registry());
    interpreter.setOutput(capture);
    interpreter.interpret(result.program());
    return capture.toString().stripTrailing();
  }

  @Test
  void testCaptureByValue() throws Exception {
    // The closure captures `data` by value; a later reassignment must not change what it sees.
    assertEquals(
        "1",
        run(
            """
            program t;
            import io; import std;
            list<int> data = [1, 2, 3];
            fn() -> int firstOf = fn() -> data[0];
            data = [99, 2, 3];
            io:println(std:str(firstOf()));
            """));
  }

  @Test
  void testNestedLambdaCapturesOuterParam() throws Exception {
    assertEquals(
        "15",
        run(
            """
            program t;
            import io; import std;
            fn(int) -> fn(int) -> int adder = fn(x) -> fn(y) -> x + y;
            fn(int) -> int add10 = adder(10);
            io:println(std:str(add10(5)));
            """));
  }

  @Test
  void testClosureStoredInVariableCalledByName() throws Exception {
    // `helper()` calls a closure held in a VARIABLE — free-variable capture must include the call
    // target (it caught this incidentally before, when it deep-copied the whole scope).
    assertEquals(
        "42",
        run(
            """
            program t;
            import io; import std;
            fn(int) -> int helper = fn(n) -> n * 2;
            fn() -> int useHelper = fn() -> helper(21);
            io:println(std:str(useHelper()));
            """));
  }

  @Test
  void testLoopClosuresCaptureOwnIteration() throws Exception {
    assertEquals(
        "0\n2",
        run(
            """
            program t;
            import io; import std; import collections;
            list<fn() -> int> makers = [];
            for i in 0..3 {
                fn() -> int m = fn() -> i;
                makers = collections:append(makers, m);
            }
            fn() -> int m0 = makers[0];
            fn() -> int m2 = makers[2];
            io:println(std:str(m0()));
            io:println(std:str(m2()));
            """));
  }
}
