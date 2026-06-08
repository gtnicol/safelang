package io.safelang.runtime;

import io.safelang.SAFEException;
import java.util.Deque;
import java.util.Map;

/**
 * Shared {@code decreases} measure discipline for every Java backend (interpreter, bytecode VM, JVM
 * runtime). Each backend owns a per-function stack of the most recent measures; this enforces the
 * single invariant identically: a measure must be non-negative and strictly smaller than the
 * previous one for the same function. Centralizing it keeps the backends from drifting — they
 * previously carried three copies, one with a divergent error message.
 *
 * <p>Throws {@link SAFEException}; callers translate it into their own backend exception type where
 * their tests require it.
 */
public final class Measures {

  private Measures() {}

  /** Evaluate one recursive call's measure against {@code name}'s stack, then record it. */
  public static void push(
      final Map<String, Deque<Long>> stacks, final String name, final long measure) {
    final var stack = stacks.computeIfAbsent(name, key -> new java.util.ArrayDeque<>());
    if (measure < 0) {
      throw new SAFEException("Decreases measure must be non-negative for: " + name);
    }
    if (!stack.isEmpty() && measure >= stack.peek()) {
      throw new SAFEException(
          "Decreases clause not satisfied for: "
              + name
              + " (measure "
              + measure
              + " >= previous "
              + stack.peek()
              + ")");
    }
    stack.push(measure);
  }

  /** Discard {@code name}'s most recent measure as its call returns. */
  public static void pop(final Map<String, Deque<Long>> stacks, final String name) {
    final var stack = stacks.get(name);
    if (stack != null && !stack.isEmpty()) {
      stack.pop();
    }
  }
}
