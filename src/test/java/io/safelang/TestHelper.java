package io.safelang;

import io.safelang.ast.ProgramNode;
import io.safelang.bytecode.BytecodeVM;
import io.safelang.compiler.bytecode.BytecodeCompiler;
import io.safelang.interpreter.Interpreter;
import java.io.StringWriter;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

public final class TestHelper {

  private TestHelper() {}

  static List<String> stdlibModules() {
    return SafeFrontend.stdlibModules();
  }

  private static Loaded load(final String source) {
    final var options =
        SafeFrontend.Options.defaults()
            .withSource(Path.of("stdlib/io.safe"))
            .withPreloads(SafeFrontend.stdlibModules(), true);
    final var result = SafeFrontend.bootstrap(source, options);
    return new Loaded(result.program(), result.registry());
  }

  public static String run(final String source) {
    final var capture = new StringWriter();
    final var loaded = load(source);
    final var interpreter = new Interpreter();
    interpreter.setRegistry(loaded.registry());
    interpreter.setOutput(capture);
    interpreter.interpret(loaded.program());
    return capture.toString().stripTrailing();
  }

  public static String bytecode(final String source) {
    final var capture = new StringWriter();
    final var loaded = load(source);
    final var compiler = new BytecodeCompiler();
    compiler.setRegistry(loaded.registry());
    final var compiled = compiler.compile(loaded.program());
    final var vm = new BytecodeVM(compiled);
    vm.setOutput(capture);
    vm.execute();
    return capture.toString().stripTrailing();
  }

  public static String wasm(final String source) throws Exception {
    final var loaded = load(source);
    final var pipeline = new io.safelang.compiler.wasm.WasmPipeline(loaded.registry());
    return io.safelang.compiler.wasm.WasmPipeline.run(
        pipeline.compile(loaded.program()),
        io.safelang.compiler.wasm.WasmPipeline.RunOptions.permissive());
  }

  /**
   * Run with custom args/stdin/environment. Test-only escape hatch — production code should use
   * either {@link io.safelang.compiler.wasm.WasmPipeline.RunOptions#hermetic()} or {@link
   * io.safelang.compiler.wasm.WasmPipeline.RunOptions#permissive()}; this overload exists for the
   * few WasmPipelineTests cases that pin specific environments or argv.
   */
  public static String wasm(
      final String source,
      final List<String> args,
      final String stdin,
      final Map<String, String> environment)
      throws Exception {
    final var loaded = load(source);
    final var pipeline = new io.safelang.compiler.wasm.WasmPipeline(loaded.registry());
    return io.safelang.compiler.wasm.WasmPipeline.run(
        pipeline.compile(loaded.program()),
        new io.safelang.compiler.wasm.WasmPipeline.RunOptions(
            args, stdin, environment, List.of("/tmp", ".")));
  }

  public static void analyze(final String source) {
    final var options =
        SafeFrontend.Options.defaults()
            .withSource(Path.of("stdlib/io.safe"))
            .withPreloads(SafeFrontend.stdlibModules(), true)
            .withStrict(true);
    SafeFrontend.bootstrap(source, options);
  }

  /**
   * Run a strict-mode analysis with caller-supplied module sources. The variadic args are
   * alternating ({@code moduleName}, {@code moduleSource}) pairs registered before the main program
   * is analyzed. Used by StrictModeTests to reproduce the audit's cross-module strict propagation
   * cases without depending on the on-disk stdlib.
   */
  public static void runStrict(final String main, final String... moduleArgs) {
    final var registry = new ModuleRegistry();
    // Always preload the on-disk stdlib so io/std are available.
    final var loader = new io.safelang.ModuleLoader(Path.of("stdlib/io.safe"));
    for (final var name : SafeFrontend.stdlibModules()) {
      try {
        registry.register(name, loader.load(name));
      } catch (final RuntimeException ignored) {
      }
    }
    // Register caller-provided modules.
    for (var i = 0; i + 1 < moduleArgs.length; i += 2) {
      registry.register(moduleArgs[i], io.safelang.parser.SAFEParser.parse(moduleArgs[i + 1]));
    }
    // Analyze each registered module non-strict (the call site is what
    // enforces strict mode for cross-module calls).
    for (final var entry : registry.modules()) {
      final var module = registry.program(entry);
      if (module == null) continue;
      try {
        new io.safelang.analyzer.SemanticAnalyzer(registry).analyze(module, false);
      } catch (final io.safelang.analyzer.SemanticException ignored) {
      }
    }
    // Mirror SafeFrontend.bootstrap: when running strict, check every
    // loaded module's top-level statements/const initializers for
    // nondeterministic builtin calls. Module init runs at import time and
    // is invisible to the cross-module call walker, so the check has to
    // be explicit.
    for (final var entry : registry.modules()) {
      final var module = registry.program(entry);
      if (module == null) continue;
      try {
        io.safelang.analyzer.SemanticAnalyzer.checkTopLevelPurity(module, entry, registry);
      } catch (final io.safelang.analyzer.SemanticException exception) {
        throw new io.safelang.analyzer.SemanticException(
            "In module '" + entry + "': " + exception.getMessage(),
            exception.line(),
            exception.column());
      }
    }
    // Analyze the main program in strict mode.
    final var program = io.safelang.parser.SAFEParser.parse(main);
    new io.safelang.analyzer.SemanticAnalyzer(registry).analyze(program, true);
  }

  private record Loaded(ProgramNode program, ModuleRegistry registry) {}
}
