package io.safelang;

import static org.junit.jupiter.api.Assertions.*;

import io.safelang.compiler.CompilerFrontEnd;
import io.safelang.compiler.wasm.WasmPipeline;
import java.nio.file.Path;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Phase 5 regression tests: the WASM backend must enforce {@code requires}, {@code ensures}, and
 * {@code decreases} contracts at runtime, matching the other three backends. Before Phase 5 the
 * WASM backend silently ignored every contract.
 *
 * <p>Each test feeds a small SAFE program through the WASM pipeline, executes it via wasmtime, and
 * asserts that contract violations exit non-zero with the expected diagnostic on stderr. Successful
 * contracts (the "happy path" cases) verify that valid programs still run.
 */
class WasmContractTests {

  @BeforeAll
  static void check() {
    Assumptions.assumeTrue(wasmtime(), "wasmtime not available");
  }

  private static boolean wasmtime() {
    try {
      return new ProcessBuilder("wasmtime", "--version").start().waitFor() == 0;
    } catch (final Exception exception) {
      return false;
    }
  }

  /** Compile and run a SAFE source through the WASM pipeline; capture stdout. */
  private static String runOk(final String source) throws Exception {
    final var loaded = bootstrap(source);
    final var pipeline = new WasmPipeline(loaded.registry());
    return pipeline.execute(loaded.program());
  }

  /**
   * Compile and run a SAFE source that is expected to trap. Returns the exception's message (which
   * includes the captured stderr from wasmtime). Asserts the run threw and the exit code was
   * non-zero.
   */
  private static String runTrap(final String source) throws Exception {
    final var loaded = bootstrap(source);
    final var pipeline = new WasmPipeline(loaded.registry());
    final var thrown =
        assertThrows(RuntimeException.class, () -> pipeline.execute(loaded.program()));
    return thrown.getMessage();
  }

  private static CompilerFrontEnd.ParseResult bootstrap(final String source) {
    final var options =
        SafeFrontend.Options.defaults()
            .withSource(Path.of("stdlib/io.safe"))
            .withPreloads(SafeFrontend.stdlibModules(), true);
    return SafeFrontend.bootstrap(source, options);
  }

  @Nested
  class Requires {

    @Test
    void preconditionRejects() throws Exception {
      // The audit's exact reproducer.
      final var message =
          runTrap(
              """
          program test;
          import io;
          int safe_div(int a, int b)
              requires b != 0
              ensures result * b == a {
              return a / b;
          }
          io:println(`${safe_div(10, 0)}`);
          """);
      assertTrue(
          message.contains("Requires contract failed for function: safe_div"),
          "Expected requires diagnostic, got: " + message);
    }

    @Test
    void preconditionAccepts() throws Exception {
      // Valid input — the contract should not trip.
      assertEquals(
          "5",
          runOk(
              """
              program test;
              import io;
              int safe_div(int a, int b)
                  requires b != 0 {
                  return a / b;
              }
              io:println(`${safe_div(10, 2)}`);
              """));
    }
  }

  @Nested
  class Ensures {

    @Test
    void postconditionRejects() throws Exception {
      // ensures is broken: returning 0 violates `result > 0`.
      final var message =
          runTrap(
              """
          program test;
          import io;
          int positive(int x)
              ensures result > 0 {
              return 0;
          }
          io:println(`${positive(5)}`);
          """);
      assertTrue(
          message.contains("Ensures contract failed for function: positive"),
          "Expected ensures diagnostic, got: " + message);
    }

    @Test
    void postconditionAccepts() throws Exception {
      assertEquals(
          "7",
          runOk(
              """
              program test;
              import io;
              int positive(int x)
                  ensures result > 0 {
                  return x;
              }
              io:println(`${positive(7)}`);
              """));
    }
  }

  @Nested
  class Decreases {

    @Test
    void decreasesRejectsNonDecreasingRecursion() throws Exception {
      // The static termination checker accepts this (it has a base case and
      // a numerically-decreasing call), but the decreases clause measures
      // the wrong thing — it returns a constant — so the runtime check trips
      // on the second call. This exercises the runtime decreases stack.
      final var message =
          runTrap(
              """
          program test;
          import io;
          int loopy(int n)
              decreases(7) {
              return if (n <= 0) then 0 else loopy(n - 1);
          }
          io:println(`${loopy(3)}`);
          """);
      assertTrue(
          message.contains("Decreases clause not satisfied for: loopy"),
          "Expected decreases diagnostic, got: " + message);
    }

    @Test
    void decreasesRejectsNegativeMeasure() throws Exception {
      final var message =
          runTrap(
              """
          program test;
          import io;
          int negative(int n)
              decreases(0 - 1) {
              return n;
          }
          io:println(`${negative(5)}`);
          """);
      assertTrue(
          message.contains("Decreases measure must be non-negative for: negative"),
          "Expected negative-measure diagnostic, got: " + message);
    }

    @Test
    void decreasesAcceptsValidRecursion() throws Exception {
      assertEquals(
          "0",
          runOk(
              """
              program test;
              import io;
              int countdown(int n)
                  decreases(n) {
                  return if (n <= 0) then 0 else countdown(n - 1);
              }
              io:println(`${countdown(5)}`);
              """));
    }
  }

  @Nested
  class CombinedContracts {

    @Test
    void requiresAndEnsuresBothEnforced() throws Exception {
      // Audit reproducer: both requires and ensures should fire.
      final var message =
          runTrap(
              """
          program test;
          import io;
          int safe_div(int a, int b)
              requires b != 0
              ensures result * b == a {
              return a / b;
          }
          io:println(`${safe_div(10, 0)}`);
          """);
      assertTrue(
          message.contains("Requires contract failed"),
          "Expected requires to fire first, got: " + message);
    }
  }

  /**
   * Parity regressions for guards that were missing or buggy in the WASM backend relative to the
   * interpreter, bytecode VM, and C backend.
   */
  @Nested
  class RuntimeGuards {

    @Test
    void decreasesZeroCannotRecurse() throws Exception {
      // A measure of 0 is legal (non-negative) but leaves no room to shrink
      // further, so any recursive call must trip the strict-decrease check.
      // Previously the WASM backend treated 0 as a "no call in flight"
      // sentinel and skipped the check entirely, allowing silent infinite
      // recursion.
      final var message =
          runTrap(
              """
          program test;
          import io;
          int spin(int n)
              decreases(0) {
              return if (n <= 0) then 0 else spin(n - 1);
          }
          io:println(`${spin(3)}`);
          """);
      assertTrue(
          message.contains("Decreases clause not satisfied for: spin"),
          "Expected decreases diagnostic for decreases(0) recursion, got: " + message);
    }

    @Test
    void whileBoundMustBeNonNegative() throws Exception {
      final var message =
          runTrap(
              """
          program test;
          import io;
          int bad(int n) {
              int i = 0;
              while (i < n) bound (0 - 1) {
                  i = i + 1;
              }
              return i;
          }
          io:println(`${bad(3)}`);
          """);
      assertTrue(
          message.contains("While loop bound must be non-negative"),
          "Expected while-bound diagnostic, got: " + message);
    }

    @Test
    void rangeStepZeroTraps() throws Exception {
      final var message =
          runTrap(
              """
          program test;
          import io;
          list<int> build(int s) {
              return 0..10 step s;
          }
          io:println(`${build(0)}`);
          """);
      assertTrue(
          message.contains("Range step cannot be zero"),
          "Expected step-zero diagnostic, got: " + message);
    }

    @Test
    void nanArgumentRejected() throws Exception {
      // sqrt of a negative float is IEEE NaN; pass it straight into a user
      // function so the prologue guard fires.
      final var message =
          runTrap(
              """
          program test;
          import io;
          import math;
          float identity(float x) {
              return x;
          }
          io:println(`${identity(math:sqrt(0.0 - 1.0))}`);
          """);
      assertTrue(
          message.contains("NaN is not allowed as an argument to function 'identity'"),
          "Expected NaN rejection diagnostic, got: " + message);
    }
  }
}
