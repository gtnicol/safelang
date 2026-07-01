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
  /**
   * Default warning sink: prints to {@link System#err} with a {@code WARNING:} prefix. The warning
   * sink is now a <em>per-call parameter</em> rather than mutable global state — every entry point
   * defaults to this, and callers that need to capture warnings pass their own sink to the
   * sink-accepting overloads. This keeps concurrent compilations on different threads from racing
   * on a shared sink.
   */
  public static final Consumer<String> DEFAULT_WARNINGS =
      message -> System.err.println("WARNING: " + message);

  /** Route a list of analyzer warnings to {@code sink}. */
  public static void emit(final List<String> list, final Consumer<String> sink) {
    for (final var message : list) {
      sink.accept(message);
    }
  }

  /**
   * Route warnings to the default stderr sink (convenience for drivers like {@link TestRunner}).
   */
  public static void emit(final List<String> list) {
    emit(list, DEFAULT_WARNINGS);
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
    run(source, filename, arguments, strict, modulePath, io.safelang.runtime.Capabilities.none());
  }

  public static void run(
      final String source,
      final String filename,
      final List<String> arguments,
      final boolean strict,
      final List<Path> modulePath,
      final io.safelang.runtime.Capabilities capabilities) {
    run(
        source,
        filename,
        arguments,
        strict,
        modulePath,
        io.safelang.runtime.HostPolicy.of(capabilities));
  }

  public static void run(
      final String source,
      final String filename,
      final List<String> arguments,
      final boolean strict,
      final List<Path> modulePath,
      final io.safelang.runtime.HostPolicy policy) {
    run(source, filename, arguments, strict, modulePath, policy, DEFAULT_WARNINGS);
  }

  /** Capture analyzer warnings via {@code warnings} instead of the default stderr sink. */
  public static void run(
      final String source,
      final String filename,
      final List<String> arguments,
      final boolean strict,
      final Consumer<String> warnings) {
    // The warning-capture overloads are a build-tooling convenience (inspecting your own program),
    // so they stay permissive; the primary no-policy overloads deny by default.
    run(
        source,
        filename,
        arguments,
        strict,
        List.of(),
        io.safelang.runtime.HostPolicy.trusted(),
        warnings);
  }

  public static void run(
      final String source,
      final String filename,
      final List<String> arguments,
      final boolean strict,
      final List<Path> modulePath,
      final io.safelang.runtime.HostPolicy policy,
      final Consumer<String> warnings) {
    final var parsed = parse(source, filename, strict, modulePath);
    emit(parsed.warnings(), warnings);
    final var interpreter = new Interpreter(arguments, policy);
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
    return compile(source, filename, strict, modulePath, io.safelang.runtime.Capabilities.none());
  }

  public static SafeCompileResult compile(
      final String source,
      final String filename,
      final boolean strict,
      final List<Path> modulePath,
      final io.safelang.runtime.Capabilities capabilities) {
    return invoke(
        new CCompiler(), source, filename, strict, modulePath, capabilities, DEFAULT_WARNINGS);
  }

  /** Compile to C, capturing analyzer warnings via {@code warnings} instead of stderr. */
  public static SafeCompileResult compile(
      final String source,
      final String filename,
      final boolean strict,
      final Consumer<String> warnings) {
    return invoke(
        new CCompiler(),
        source,
        filename,
        strict,
        List.of(),
        io.safelang.runtime.Capabilities.all(),
        warnings);
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
    return bytecode(source, filename, strict, modulePath, io.safelang.runtime.Capabilities.none());
  }

  public static SafeCompileResult bytecode(
      final String source,
      final String filename,
      final boolean strict,
      final List<Path> modulePath,
      final io.safelang.runtime.Capabilities capabilities) {
    return invoke(
        new BytecodeCompilerService(),
        source,
        filename,
        strict,
        modulePath,
        capabilities,
        DEFAULT_WARNINGS);
  }

  /** Compile to bytecode, capturing analyzer warnings via {@code warnings} instead of stderr. */
  public static SafeCompileResult bytecode(
      final String source,
      final String filename,
      final boolean strict,
      final Consumer<String> warnings) {
    return invoke(
        new BytecodeCompilerService(),
        source,
        filename,
        strict,
        List.of(),
        io.safelang.runtime.Capabilities.all(),
        warnings);
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
    return wasm(source, filename, strict, modulePath, io.safelang.runtime.Capabilities.none());
  }

  public static SafeCompileResult wasm(
      final String source,
      final String filename,
      final boolean strict,
      final List<Path> modulePath,
      final io.safelang.runtime.Capabilities capabilities) {
    return invoke(
        new WebAssemblyCompilerService(),
        source,
        filename,
        strict,
        modulePath,
        capabilities,
        DEFAULT_WARNINGS);
  }

  /** Compile to WASM, capturing analyzer warnings via {@code warnings} instead of stderr. */
  public static SafeCompileResult wasm(
      final String source,
      final String filename,
      final boolean strict,
      final Consumer<String> warnings) {
    return invoke(
        new WebAssemblyCompilerService(),
        source,
        filename,
        strict,
        List.of(),
        io.safelang.runtime.Capabilities.all(),
        warnings);
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
    return jvm(source, filename, strict, modulePath, io.safelang.runtime.Capabilities.none());
  }

  public static SafeCompileResult jvm(
      final String source,
      final String filename,
      final boolean strict,
      final List<Path> modulePath,
      final io.safelang.runtime.Capabilities capabilities) {
    return invoke(
        new JvmCompilerService(),
        source,
        filename,
        strict,
        modulePath,
        capabilities,
        DEFAULT_WARNINGS);
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
    return build(source, filename, strict, modulePath, io.safelang.runtime.Capabilities.none());
  }

  public static Path build(
      final String source,
      final String filename,
      final boolean strict,
      final List<Path> modulePath,
      final io.safelang.runtime.Capabilities capabilities) {
    final var request =
        requestAndWarn(source, filename, strict, modulePath, capabilities, DEFAULT_WARNINGS);
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
    vm(filename, arguments, io.safelang.runtime.Capabilities.none());
  }

  /**
   * Load and execute a {@code .safeb} file under a capability policy. Bytecode bypasses source
   * analysis, so this is where an embedder running untrusted bytecode must restrict host access.
   */
  public static void vm(
      final String filename,
      final List<String> arguments,
      final io.safelang.runtime.Capabilities capabilities) {
    vm(filename, arguments, io.safelang.runtime.HostPolicy.of(capabilities));
  }

  public static void vm(
      final String filename,
      final List<String> arguments,
      final io.safelang.runtime.HostPolicy policy) {
    try {
      final var module = new BytecodeReader().load(filename);
      new BytecodeVM(module, arguments, policy).execute();
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
      final List<Path> modulePath,
      final io.safelang.runtime.Capabilities capabilities,
      final Consumer<String> warnings) {
    final var parsed = parse(source, filename, strict, modulePath);
    emit(parsed.warnings(), warnings);
    if (filename == null) {
      throw new IllegalArgumentException(
          "SafeRuntime: filename is required for compile/build/bytecode/wasm");
    }
    return new CompileRequest(
        Path.of(filename), parsed.program(), parsed.registry(), strict, capabilities);
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
      final List<Path> modulePath,
      final io.safelang.runtime.Capabilities capabilities,
      final Consumer<String> warnings) {
    final var request =
        requestAndWarn(source, filename, strict, modulePath, capabilities, warnings);
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
