package io.safelang;

import io.safelang.bytecode.BytecodeReader;
import io.safelang.bytecode.BytecodeVM;
import io.safelang.compiler.BytecodeCompilerService;
import io.safelang.compiler.CompileRequest;
import io.safelang.compiler.CompilerFrontEnd;
import io.safelang.compiler.JvmCompilerService;
import io.safelang.compiler.SafeCompileResult;
import io.safelang.compiler.SafeCompiler;
import io.safelang.compiler.WebAssemblyCompilerService;
import io.safelang.compiler.c.CBuildDriver;
import io.safelang.compiler.c.CCompiler;
import io.safelang.interpreter.Interpreter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Embedder-facing facade over the SAFE toolchain.
 *
 * <p>This is the supported entry point for code that wants to drive the compiler / interpreter / VM
 * programmatically (test harnesses, JSR-223 script engines, IDE integrations, etc.). Every
 * operation throws a typed {@link SAFEException} on failure — no method calls {@link System#exit}
 * or writes diagnostics to {@link System#err}. The CLI driver ({@link SafeMain}) is the only place
 * that translates these exceptions into exit codes.
 */
public final class SafeRuntime {

  private SafeRuntime() {}

  /**
   * Default warning sink: prints to {@link System#err} with a {@code WARNING:} prefix. Matches the
   * format the CLI has historically used from the {@code ast} command.
   */
  private static final Consumer<String> DEFAULT_WARNINGS =
      message -> System.err.println("WARNING: " + message);

  private static volatile Consumer<String> warnings = DEFAULT_WARNINGS;

  /**
   * Install a custom sink for analyzer warnings. Every subsequent call to {@link #run}, {@link
   * #compile}, {@link #build}, {@link #bytecode}, and {@link #wasm} routes each warning produced
   * during semantic analysis through the sink. Pass {@code message -> {}} to silence warnings.
   *
   * <p>Affects the JVM globally — call {@link #resetWarnings} to restore the stderr default.
   */
  public static void setWarnings(final Consumer<String> sink) {
    warnings = Objects.requireNonNull(sink, "warning sink");
  }

  /** Restore the default warning sink (stderr with {@code WARNING:} prefix). */
  public static void resetWarnings() {
    warnings = DEFAULT_WARNINGS;
  }

  /**
   * Route a list of analyzer warnings through the installed sink. Public so alternate drivers (e.g.
   * {@link TestRunner}) can surface warnings the same way every {@code SafeRuntime} entry point
   * does without duplicating the stderr-vs-sink dispatch.
   */
  public static void emit(final List<String> list) {
    final var sink = warnings;
    for (final var message : list) {
      sink.accept(message);
    }
  }

  /** Parse and semantically validate a SAFE source. Warnings are NOT emitted — caller decides. */
  public static CompilerFrontEnd.ParseResult parse(
      final String source, final String filename, final boolean strict) {
    return parse(source, filename, strict, List.of());
  }

  /** Parse and semantically validate, with extra module search directories. */
  public static CompilerFrontEnd.ParseResult parse(
      final String source,
      final String filename,
      final boolean strict,
      final List<Path> modulePath) {
    return CompilerFrontEnd.parse(source, filename, strict, modulePath);
  }

  /** Interpret a SAFE source via the tree-walking interpreter. */
  public static void run(
      final String source,
      final String filename,
      final List<String> arguments,
      final boolean strict) {
    run(source, filename, arguments, strict, List.of());
  }

  public static void run(
      final String source,
      final String filename,
      final List<String> arguments,
      final boolean strict,
      final List<Path> modulePath) {
    final var parsed = parse(source, filename, strict, modulePath);
    emit(parsed.warnings());
    final var interpreter = new Interpreter(arguments);
    interpreter.setRegistry(parsed.registry());
    interpreter.interpret(parsed.program());
  }

  /** Compile a SAFE source to C. */
  public static SafeCompileResult compile(
      final String source, final String filename, final boolean strict) {
    return compile(source, filename, strict, List.of());
  }

  public static SafeCompileResult compile(
      final String source,
      final String filename,
      final boolean strict,
      final List<Path> modulePath) {
    return invoke(new CCompiler(), source, filename, strict, modulePath);
  }

  /** Compile a SAFE source to a bytecode {@code .safeb} file. */
  public static SafeCompileResult bytecode(
      final String source, final String filename, final boolean strict) {
    return bytecode(source, filename, strict, List.of());
  }

  public static SafeCompileResult bytecode(
      final String source,
      final String filename,
      final boolean strict,
      final List<Path> modulePath) {
    return invoke(new BytecodeCompilerService(), source, filename, strict, modulePath);
  }

  /** Compile a SAFE source to a WebAssembly module {@code .wasm} file. */
  public static SafeCompileResult wasm(
      final String source, final String filename, final boolean strict) {
    return wasm(source, filename, strict, List.of());
  }

  public static SafeCompileResult wasm(
      final String source,
      final String filename,
      final boolean strict,
      final List<Path> modulePath) {
    return invoke(new WebAssemblyCompilerService(), source, filename, strict, modulePath);
  }

  /** Compile a SAFE source to a self-contained executable JVM jar. */
  public static SafeCompileResult jvm(
      final String source, final String filename, final boolean strict) {
    return jvm(source, filename, strict, List.of());
  }

  public static SafeCompileResult jvm(
      final String source,
      final String filename,
      final boolean strict,
      final List<Path> modulePath) {
    return invoke(new JvmCompilerService(), source, filename, strict, modulePath);
  }

  /**
   * Compile to C and invoke the system C compiler to produce a native binary.
   *
   * <p>The compiler is resolved from the {@code SAFE_CC} environment variable (or the {@code
   * safe.cc} system property), defaulting to {@code gcc}. This lets users point at {@code clang},
   * {@code cc}, or a full path to a cross-compiler on systems where {@code gcc} is absent.
   *
   * <p>The subprocess's stdout/stderr are captured (not inherited), so embedders never see compiler
   * diagnostics on their own stderr. On non-zero exit, the captured output is included in the
   * thrown {@link SAFEException}'s message.
   */
  public static Path build(final String source, final String filename, final boolean strict) {
    return build(source, filename, strict, List.of());
  }

  public static Path build(
      final String source,
      final String filename,
      final boolean strict,
      final List<Path> modulePath) {
    final var request = requestAndWarn(source, filename, strict, modulePath);
    final SafeCompileResult compiled;
    try {
      compiled = new CCompiler().compile(request);
    } catch (final SAFEException pass) {
      throw pass;
    } catch (final Exception exception) {
      throw new SAFEException("C compilation failed: " + exception.getMessage(), exception);
    }
    final var binary = request.binaryPath();
    CBuildDriver.build(compiled.output(), binary);
    return binary;
  }

  /** Load and execute a compiled bytecode {@code .safeb} file. */
  public static void vm(final String filename, final List<String> arguments) {
    try {
      final var module = new BytecodeReader().load(filename);
      new BytecodeVM(module, arguments).execute();
    } catch (final SAFEException pass) {
      throw pass;
    } catch (final IOException exception) {
      throw new SAFEException(
          "Failed to read bytecode file '" + filename + "': " + exception.getMessage(), exception);
    }
  }

  /** Read a SAFE source file from disk. */
  public static String read(final String filename) {
    try {
      return Files.readString(Path.of(filename));
    } catch (final IOException exception) {
      throw new SAFEException(
          "Failed to read source file '" + filename + "': " + exception.getMessage(), exception);
    }
  }

  /**
   * Parse + validate, emit warnings through the installed sink, then build a {@link
   * CompileRequest}. The request itself carries no warnings — they are drained here so every
   * SafeRuntime backend entry point surfaces them the same way.
   */
  private static CompileRequest requestAndWarn(
      final String source,
      final String filename,
      final boolean strict,
      final List<Path> modulePath) {
    final var parsed = parse(source, filename, strict, modulePath);
    emit(parsed.warnings());
    if (filename == null) {
      throw new IllegalArgumentException(
          "SafeRuntime: filename is required for compile/build/bytecode/wasm");
    }
    return new CompileRequest(Path.of(filename), parsed.program(), parsed.registry(), strict);
  }

  /**
   * Drive a {@link SafeCompiler} backend, wrapping any non-{@link SAFEException} failure in a
   * {@link SAFEException} so the public API stays narrow.
   */
  private static SafeCompileResult invoke(
      final SafeCompiler compiler,
      final String source,
      final String filename,
      final boolean strict,
      final List<Path> modulePath) {
    final var request = requestAndWarn(source, filename, strict, modulePath);
    try {
      return compiler.compile(request);
    } catch (final SAFEException pass) {
      throw pass;
    } catch (final Exception exception) {
      throw new SAFEException(
          compiler.getClass().getSimpleName() + " failed: " + exception.getMessage(), exception);
    }
  }
}
