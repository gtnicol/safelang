package io.safelang.runtime;

import java.util.List;

/**
 * Thread-local bridge that lets a builtin call back into a SAFE closure. Builtins normally receive
 * only {@code List<SAFEValue>} with no handle on the executing backend, so a higher-order builtin
 * like {@code http:serve} (whose accept loop must invoke a {@code fn(Request)->Response} handler)
 * has no way to apply a {@link SAFEValue} function on its own.
 *
 * <p>Each backend installs an {@link Applier} before running user code (interpreter and bytecode VM
 * expose a synchronous {@code apply}; the JVM backend forwards to {@code JvmRuntime.invoke}) and
 * clears it afterwards. The applier is per-thread so concurrent runs do not interfere.
 */
public final class HostCallback {

  /** Applies a SAFE function value to argument values, returning the result. */
  @FunctionalInterface
  public interface Applier {
    SAFEValue apply(SAFEValue function, List<SAFEValue> arguments);
  }

  private static final ThreadLocal<Applier> CURRENT = new ThreadLocal<>();

  private HostCallback() {}

  public static void set(final Applier applier) {
    CURRENT.set(applier);
  }

  public static void clear() {
    CURRENT.remove();
  }

  /** The applier installed by the current backend. */
  public static Applier current() {
    final var applier = CURRENT.get();
    if (applier == null) {
      throw new IllegalStateException(
          "No host callback is active; cannot invoke a SAFE function from this context");
    }
    return applier;
  }
}
