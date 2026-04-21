package io.safelang;

import static org.junit.jupiter.api.Assertions.*;

import io.safelang.analyzer.SemanticException;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Phase 6 regression tests for SAFE's three-tier purity model and strict mode propagation across
 * module boundaries.
 *
 * <p>The audit's reproducer is in {@link Propagation#strictRejectsHelperWithTime}: a strict main
 * calls a public module function whose private helper calls {@code time()}. Before Phase 6, this
 * slipped through because cross-module unqualified calls weren't transitively checked. After Phase
 * 6, the {@link io.safelang.analyzer.PurityChecker} is module-aware and walks helpers in the called
 * module's namespace.
 */
class StrictModeTests {

  @Nested
  class ObservableAllowed {

    @Test
    void printlnIsObservableNotImpure() {
      // print/println produce stdout but are deterministic functions of
      // their arguments. Strict mode admits them.
      assertEquals(
          "hello",
          TestHelper.run(
              """
          program test;
          import io;
          io:println("hello");
          """));
    }

    @Test
    void helloWorldRunsUnderStrict() {
      // examples/hello.safe pattern: just println.
      assertDoesNotThrow(
          () ->
              TestHelper.run(
                  """
          program test;
          import io;
          io:println("Hello, World!");
          """));
    }
  }

  @Nested
  class NondeterministicRejected {

    @Test
    void timeIsRejectedInStrict() {
      assertThrows(
          SemanticException.class,
          () ->
              TestHelper.analyze(
                  """
          program test;
          import std;
          int t = std:time();
          """));
      // Without strict mode, no error.
    }
  }

  @Nested
  class Propagation {

    @Test
    void strictRejectsHelperWithTime() {
      // The audit's exact reproduction: strict main → public module function
      // → private helper → time(). Before Phase 6 this slipped through; the
      // PurityChecker now walks helper() in the called module's namespace.
      assertThrows(
          SemanticException.class,
          () ->
              TestHelper.runStrict(
                  """
              program main;
              import sideeffects;
              int x = sideeffects:noop();
              """,
                  "sideeffects",
                  """
              module sideeffects;
              import std;
              private int helper() {
                  return std:time();
              }
              public int noop() {
                  return helper();
              }
              """));
    }

    @Test
    void strictAcceptsHelperWithPrintln() {
      // println is OBSERVABLE — strict propagates through but doesn't reject.
      assertDoesNotThrow(
          () ->
              TestHelper.runStrict(
                  """
          program main;
          import io;
          import greeter;
          greeter:hello();
          """,
                  "greeter",
                  """
          module greeter;
          import io;
          public void hello() {
              io:println("hi from greeter");
          }
          """));
    }
  }

  @Nested
  class TopLevelInitializers {

    @Test
    void strictRejectsModuleWithTopLevelTime() {
      // Audit round 3 reproducer: a strict main importing a module whose
      // top-level const declaration calls a NONDETERMINISTIC builtin
      // (std:time). The previous round's cross-module call walker only
      // checks function-call surfaces; module top-level work runs at
      // import time and was invisible to the strict check.
      final var thrown =
          assertThrows(
              SemanticException.class,
              () ->
                  TestHelper.runStrict(
                      """
              program main;
              import sideeffects;
              import io;
              io:println("ok");
              """,
                      "sideeffects",
                      """
              module sideeffects;
              import std;
              const int t = std:time();
              public int noop() { return t; }
              """));
      assertTrue(thrown.getMessage().contains("sideeffects"));
      assertTrue(thrown.getMessage().contains("strict mode"));
    }

    @Test
    void strictRejectsMainWithTopLevelTime() {
      // Top-level reproducer for the main program. This case actually
      // already worked via the existing visitFunctionCall path, but the
      // assertion locks the behaviour in.
      assertThrows(
          SemanticException.class,
          () ->
              TestHelper.analyze(
                  """
              program test;
              import std;
              const int t = std:time();
              """));
    }

    @Test
    void strictAcceptsModuleWithPureTopLevelConst() {
      // A pure top-level const must not be rejected.
      assertDoesNotThrow(
          () ->
              TestHelper.runStrict(
                  """
          program main;
          import constants;
          import io;
          io:println("ok");
          """,
                  "constants",
                  """
          module constants;
          const int N = 42;
          public int answer() { return N; }
          """));
    }

    @Test
    void strictRejectsModuleTopLevelStatementCallingTime() {
      // Same shape, but the impure call is in a top-level statement
      // rather than a const initializer.
      assertThrows(
          SemanticException.class,
          () ->
              TestHelper.runStrict(
                  """
              program main;
              import sideeffects;
              import io;
              io:println("ok");
              """,
                  "sideeffects",
                  """
              module sideeffects;
              import std;
              std:time();
              """));
    }

    @Test
    void strictRejectsModuleTopLevelTransitiveImpurity() {
      // The top-level const calls a private helper that calls time().
      // The walker must follow private helpers in the module's namespace.
      assertThrows(
          SemanticException.class,
          () ->
              TestHelper.runStrict(
                  """
              program main;
              import sideeffects;
              import io;
              io:println("ok");
              """,
                  "sideeffects",
                  """
              module sideeffects;
              import std;
              private int helper() { return std:time(); }
              const int t = helper();
              public int noop() { return t; }
              """));
    }
  }
}
