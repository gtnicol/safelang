package io.safelang.compiler.c;

import io.safelang.SAFEException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;

/**
 * Drives the host C compiler to turn a generated {@code .c} file into a native binary.
 *
 * <p>Single source of truth for native build invocation — {@link io.safelang.SafeRuntime#build} and
 * {@link io.safelang.TestRunner} both route through here so compiler resolution, argument
 * construction, process spawning, and stdout/stderr capture cannot drift between them.
 */
public final class CBuildDriver {

  private CBuildDriver() {}

  /**
   * Resolve the host C compiler: {@code safe.cc} system property first, {@code SAFE_CC} environment
   * variable second, {@code gcc} as the final fallback. Lets users point at {@code clang}, {@code
   * cc}, or a full path to a cross-compiler.
   */
  public static String resolveCompiler() {
    final var property = System.getProperty("safe.cc");
    if (property != null && !property.isBlank()) {
      return property;
    }
    final var env = System.getenv("SAFE_CC");
    if (env != null && !env.isBlank()) {
      return env;
    }
    return "gcc";
  }

  /**
   * Build a native binary from a generated C source file.
   *
   * <p>No working directory is set on the compiler process — {@code source} should be an absolute
   * path, or one resolvable from the caller's CWD. The generated code uses {@code #include
   * "safe_runtime.h"}, which the C compiler resolves relative to {@code source}'s directory via the
   * quote-include search path; {@link io.safelang.SafeMain#extractRuntime} is responsible for
   * placing {@code safe_runtime.h} there.
   *
   * @param source the {@code .c} file to compile; must exist and sit beside the runtime headers it
   *     includes
   * @param binary path of the binary to produce (overwritten if present)
   * @throws SAFEException on non-zero exit or I/O failure; the captured combined stdout/stderr is
   *     included in the exception message
   */
  public static void build(final Path source, final Path binary) {
    final var compiler = resolveCompiler();
    final var command = new ArrayList<String>();
    command.add(compiler);
    command.add("-O2");
    command.add("-o");
    command.add(binary.toString());
    command.add(source.toString());
    command.add("-lm");
    try {
      final var process = new ProcessBuilder(command).redirectErrorStream(true).start();
      final var output =
          new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
      final var exit = process.waitFor();
      if (exit != 0) {
        throw new SAFEException(
            compiler
                + " exited with code "
                + exit
                + (output.isBlank() ? "" : ":\n" + output.stripTrailing()));
      }
    } catch (final IOException exception) {
      throw new SAFEException(
          compiler + " invocation failed: " + exception.getMessage(), exception);
    } catch (final InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new SAFEException(compiler + " invocation interrupted", exception);
    }
  }
}
