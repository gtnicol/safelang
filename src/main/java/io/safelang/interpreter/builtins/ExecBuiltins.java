package io.safelang.interpreter.builtins;

import io.safelang.runtime.BuiltinExecutors;
import io.safelang.runtime.SAFEValue;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Shell/process execution builtins (the {@code system} module). Naming avoids colliding with the
 * existing {@link SystemBuiltins}, which holds the {@code std}/{@code env} primitives. Commands run
 * as an argument list with no shell interpretation, so there is no injection surface.
 *
 * <p>Hardened against runaway children: each run is bounded by {@link #TIMEOUT_SECONDS} of wall
 * clock and {@link #MAX_CAPTURE} bytes of captured output per stream — a child that hangs or floods
 * is killed and the call returns {@code Err}. The two limits are package-private and non-final so
 * tests can shrink them; production keeps the documented defaults.
 */
public final class ExecBuiltins {

  static long TIMEOUT_SECONDS = 30;
  static int MAX_CAPTURE = 16 * 1024 * 1024; // 16 MiB per stream

  private ExecBuiltins() {}

  public static void register(
      final BuiltinExecutors executors, final io.safelang.runtime.HostPolicy policy) {
    executors.register("system_exec", args -> exec(args, policy));
  }

  private static SAFEValue exec(
      final List<SAFEValue> args, final io.safelang.runtime.HostPolicy policy) {
    final var argv = new ArrayList<String>();
    for (final var element : args.getFirst().asList()) {
      argv.add(element.asString());
    }
    if (argv.isEmpty()) {
      return err("Empty command");
    }
    if (!policy.execAllowed(argv.getFirst())) {
      return err("command not allowed: " + argv.getFirst());
    }
    Process process = null;
    try {
      final var builder = new ProcessBuilder(argv);
      if (policy.scrubEnv()) {
        builder.environment().clear();
      }
      process = builder.start();
      // Drain stdout and stderr concurrently so a large stream on one cannot deadlock the other.
      final var out = new BoundedSink(MAX_CAPTURE);
      final var err = new BoundedSink(MAX_CAPTURE);
      final var pumpOut = pump(process.getInputStream(), out, process);
      final var pumpErr = pump(process.getErrorStream(), err, process);
      if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
        process.destroyForcibly();
        return err("command timed out after " + TIMEOUT_SECONDS + "s");
      }
      pumpOut.join();
      pumpErr.join();
      if (out.overflowed() || err.overflowed()) {
        return err("command output exceeds " + MAX_CAPTURE + " bytes");
      }
      if (out.failed() || err.failed()) {
        return err("error reading command output");
      }
      return ok(process.exitValue(), out.text(), err.text());
    } catch (final IOException exception) {
      return err("Cannot run command: " + exception.getMessage());
    } catch (final InterruptedException exception) {
      if (process != null) {
        process.destroyForcibly();
      }
      Thread.currentThread().interrupt();
      return err("Interrupted while running command");
    }
  }

  /**
   * Copy {@code source} into {@code sink} on a daemon thread. On overflow it kills {@code process}
   * <em>immediately</em> — otherwise the child blocks on a full pipe and {@code waitFor} would
   * wedge the calling thread for the entire timeout before the cap takes effect.
   */
  private static Thread pump(
      final InputStream source, final BoundedSink sink, final Process process) {
    final var thread =
        new Thread(
            () -> {
              final var buffer = new byte[8192];
              try (source) {
                int read;
                while ((read = source.read(buffer)) != -1) {
                  if (!sink.write(buffer, read)) {
                    process.destroyForcibly(); // capped — stop the flooding child now
                    break;
                  }
                }
              } catch (final IOException exception) {
                sink.markFailed();
              }
            });
    thread.setDaemon(true);
    thread.start();
    return thread;
  }

  /** Accumulates output up to a byte cap, then refuses further writes and records the overflow. */
  private static final class BoundedSink {
    private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    private final int cap;
    private volatile boolean overflowed;
    private volatile boolean failed;

    BoundedSink(final int cap) {
      this.cap = cap;
    }

    synchronized boolean write(final byte[] data, final int length) {
      if (buffer.size() + length > cap) {
        overflowed = true;
        return false;
      }
      buffer.write(data, 0, length);
      return true;
    }

    void markFailed() {
      failed = true;
    }

    boolean overflowed() {
      return overflowed;
    }

    boolean failed() {
      return failed;
    }

    synchronized String text() {
      return buffer.toString(StandardCharsets.UTF_8);
    }
  }

  private static SAFEValue ok(final int exit, final String stdout, final String stderr) {
    final Map<String, SAFEValue> fields = new LinkedHashMap<>();
    fields.put("exit", SAFEValue.ofInt(exit));
    fields.put("stdout", SAFEValue.ofString(stdout));
    fields.put("stderr", SAFEValue.ofString(stderr));
    return SAFEValue.ofEnum("RunResult", "Ok", List.of(SAFEValue.ofObject("Output", fields)));
  }

  private static SAFEValue err(final String message) {
    return SAFEValue.ofEnum("RunResult", "Err", List.of(SAFEValue.ofString(message)));
  }
}
