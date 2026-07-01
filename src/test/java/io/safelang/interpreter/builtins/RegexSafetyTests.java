package io.safelang.interpreter.builtins;

import static org.junit.jupiter.api.Assertions.*;

import io.safelang.interpreter.InterpreterException;
import io.safelang.runtime.BuiltinExecutors;
import io.safelang.runtime.SAFEValue;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Regex builtins must trap on catastrophic backtracking instead of hanging the host. */
class RegexSafetyTests {

  private BuiltinExecutors executors;
  private long savedBudget;

  @BeforeEach
  void setup() {
    executors = new BuiltinExecutors();
    StringBuiltins.register(executors);
    savedBudget = StringBuiltins.MAX_REGEX_STEPS;
  }

  @AfterEach
  void teardown() {
    StringBuiltins.MAX_REGEX_STEPS = savedBudget;
  }

  private SAFEValue call(final String name, final SAFEValue... args) {
    return executors.get(name).execute(List.of(args));
  }

  @Test
  void testCatastrophicMatchesTrapsQuickly() {
    StringBuiltins.MAX_REGEX_STEPS = 1_000_000; // tight budget for the test
    // (.*a){20} against a's ending in a non-'a' forces exponential backtracking in Java's engine,
    // and the engine re-reads the input via charAt during the blowup — so the step budget trips.
    final var bomb = "a".repeat(28) + "X";
    assertTimeoutPreemptively(
        Duration.ofSeconds(5),
        () -> {
          final var error =
              assertThrows(
                  InterpreterException.class,
                  () -> call("matches", SAFEValue.ofString(bomb), SAFEValue.ofString("(.*a){20}")));
          assertTrue(error.getMessage().contains("step budget"));
        });
  }

  @Test
  void testCatastrophicReplaceallTrapsQuickly() {
    StringBuiltins.MAX_REGEX_STEPS = 1_000_000;
    final var bomb = "a".repeat(28) + "X";
    assertTimeoutPreemptively(
        Duration.ofSeconds(5),
        () ->
            assertThrows(
                InterpreterException.class,
                () ->
                    call(
                        "replaceall",
                        SAFEValue.ofString(bomb),
                        SAFEValue.ofString("(.*a){20}"),
                        SAFEValue.ofString("x"))));
  }

  @Test
  void testNormalRegexStillWorks() {
    assertTrue(
        call("matches", SAFEValue.ofString("hello123"), SAFEValue.ofString("[a-z]+[0-9]+"))
            .asBoolean());
    final var all =
        call("findall", SAFEValue.ofString("a1b2c3"), SAFEValue.ofString("[0-9]")).asList();
    assertEquals(3, all.size());
    assertEquals(
        "X-X",
        call(
                "replaceall",
                SAFEValue.ofString("a-b"),
                SAFEValue.ofString("[a-z]"),
                SAFEValue.ofString("X"))
            .asString());
  }
}
