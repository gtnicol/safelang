package io.safelang.runtime;

import io.safelang.SAFEException;
import java.util.ArrayList;
import java.util.List;

/**
 * Shared range construction for every Java backend (interpreter, bytecode VM, JVM runtime).
 * Centralizes the guarded size calculation so an overflowing span is rejected identically instead
 * of wrapping past the {@link SAFEValue#MAX_LIST_SIZE} cap. Callers translate the thrown {@link
 * SAFEException} into their own backend exception type where their tests require it.
 */
public final class RangeSemantics {

  private RangeSemantics() {}

  /**
   * Materialize the inclusive range {@code start..end} stepped by {@code step}. Throws {@link
   * SAFEException} on a zero step or a span that exceeds {@link SAFEValue#MAX_LIST_SIZE}.
   */
  public static List<SAFEValue> build(final long start, final long end, final long step) {
    if (step == 0) {
      throw new SAFEException("Range step cannot be zero");
    }
    // Empty when the step points away from the end.
    if ((step > 0 && start > end) || (step < 0 && start < end)) {
      return new ArrayList<>();
    }
    // subtractExact rejects a span wider than Long range instead of wrapping to a small
    // count; the div-first form degenerates to end - start when |step| == 1.
    final long extent;
    try {
      extent = Math.subtractExact(step > 0 ? end : start, step > 0 ? start : end);
    } catch (final ArithmeticException overflow) {
      throw new SAFEException("range size exceeds maximum of " + SAFEValue.MAX_LIST_SIZE);
    }
    final var size = extent / Math.abs(step) + 1;
    if (size > SAFEValue.MAX_LIST_SIZE || size < 0) {
      throw new SAFEException("range size exceeds maximum of " + SAFEValue.MAX_LIST_SIZE);
    }
    final var list = new ArrayList<SAFEValue>();
    if (step > 0) {
      for (var value = start; value <= end; value += step) {
        list.add(SAFEValue.ofInt(value));
        if (value > 0 && end - value < step) {
          break;
        }
      }
    } else {
      for (var value = start; value >= end; value += step) {
        list.add(SAFEValue.ofInt(value));
        if (value < 0 && end - value > step) {
          break;
        }
      }
    }
    return list;
  }
}
