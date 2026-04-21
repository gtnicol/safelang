package io.safelang;

import io.safelang.analyzer.SemanticAnalyzer;
import io.safelang.analyzer.SemanticException;
import io.safelang.compiler.CompilerFrontEnd;
import io.safelang.parser.SAFEParser;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Single entry point for the SAFE frontend pipeline:
 *
 * <pre>
 *   parse → load preloads → load program imports → register modules
 *   → analyze each loaded module → analyze main program
 * </pre>
 *
 * <p>Used by the CLI ({@code SafeMain} via {@code CompilerFrontEnd}), the test harness ({@code
 * TestHelper}), and the JSR-223 script engine. Each caller passes a tailored {@link Options} record
 * so the same pipeline can cover the (small) differences in behaviour:
 *
 * <ul>
 *   <li>The CLI parses a single source file and analyzes the main program in user-selected strict
 *       mode, with builtin globals as predefined names. Missing modules are fatal.
 *   <li>The script engine wraps a script with a {@code program} header, runs in non-strict mode,
 *       and threads the {@link javax.script.ScriptContext} bindings through as predefined names.
 *       Missing modules are fatal.
 *   <li>{@code TestHelper} preloads the entire stdlib (skipping any module that doesn't exist for a
 *       given test) and runs in non-strict mode.
 * </ul>
 */
public final class SafeFrontend {

  private SafeFrontend() {}

  /**
   * Enumerate every {@code .safe} module shipped under {@code stdlib/}, in deterministic
   * alphabetical order. Reads exclusively from the {@code stdlib/} resource path on this class's
   * classpath — which is {@code target/classes/stdlib/} during a Maven build and {@code
   * jar:.../stdlib/} in a packaged distribution.
   *
   * <p>The result is cached for the lifetime of the JVM. There is exactly one source of truth for
   * "what stdlib modules exist"; tests and the frontend pipeline both call this method. Resolution
   * deliberately does NOT consult the current working directory: ambient cwd state would violate
   * the determinism contract documented on {@link ModuleLoader} and cache a different answer in
   * every JVM depending on where it was launched.
   */
  public static List<String> stdlibModules() {
    return StdlibHolder.MODULES;
  }

  // Initialization-on-demand holder: the JVM guarantees StdlibHolder's class
  // initializer runs exactly once, under a class-loader lock, the first time
  // stdlibModules() is called. No explicit synchronization needed.
  private static final class StdlibHolder {
    private static final List<String> MODULES = discoverStdlib();
  }

  private static List<String> discoverStdlib() {
    final var url = SafeFrontend.class.getResource("/stdlib");
    if (url == null) {
      // Legitimate absence: running against an unbuilt dev tree or a jar that
      // genuinely does not bundle stdlib resources.
      return List.of();
    }
    try {
      final var uri = url.toURI();
      if ("jar".equals(uri.getScheme())) {
        try (var fs = FileSystems.newFileSystem(uri, Map.of())) {
          return enumerateStdlib(fs.getPath("/stdlib"));
        }
      }
      return enumerateStdlib(Path.of(uri));
    } catch (final URISyntaxException | IOException exception) {
      // The /stdlib resource exists but we cannot read it. Surfacing as a
      // SAFEException means packaging corruption fails loud instead of
      // silently pretending the stdlib is empty for the JVM lifetime.
      throw new SAFEException(
          "Packaged stdlib is corrupt or unreadable: " + exception.getMessage(), exception);
    }
  }

  private static List<String> enumerateStdlib(final Path directory) {
    try (Stream<Path> stream = Files.list(directory)) {
      final var names = new ArrayList<String>();
      stream
          .map(p -> p.getFileName().toString())
          .filter(n -> n.endsWith(".safe"))
          .map(n -> n.substring(0, n.length() - ".safe".length()))
          .forEach(names::add);
      Collections.sort(names);
      return List.copyOf(names);
    } catch (final IOException exception) {
      throw new SAFEException(
          "Packaged stdlib directory is unreadable: " + exception.getMessage(), exception);
    }
  }

  /** Run the full frontend pipeline on the given source. */
  public static CompilerFrontEnd.ParseResult bootstrap(final String source, final Options options) {
    final var program = SAFEParser.parse(source);
    final var loader = new ModuleLoader(options.source(), options.modulePath());
    final var registry = new ModuleRegistry();

    // Preloads (e.g. stdlib for tests). Tolerated misses let a single test
    // run with a partial environment — but only "module not found" is
    // tolerated; parse errors and circular imports still propagate.
    for (final var name : options.preloads()) {
      try {
        loader.load(name);
      } catch (final MissingModuleException exception) {
        if (!options.tolerateMissingPreloads()) {
          throw exception;
        }
      }
    }

    // Imports declared by the main program — these are NEVER tolerated.
    for (final var imported : program.imports()) {
      loader.load(imported.module());
    }

    // Register everything the loader picked up (transitive imports included).
    for (final var entry : loader.loaded().entrySet()) {
      registry.register(entry.getKey(), entry.getValue());
    }

    // Analyze every loaded module against its own dependency view.
    for (final var entry : loader.loaded().entrySet()) {
      final var name = entry.getKey();
      final var module = entry.getValue();
      final var dependencies = new ModuleRegistry();
      for (final var imported : module.imports()) {
        final var dependency = loader.loaded().get(imported.module());
        if (dependency != null) {
          dependencies.register(imported.module(), dependency);
        }
      }
      try {
        // Modules are always analyzed in non-strict mode: a module may
        // legitimately export impure functions (e.g., io exports println,
        // file exports read) that the main program does not call. Strict
        // checks live at the call site instead — see
        // {@code SemanticCallChecker.qualified} which transitively walks
        // every cross-module call from a strict main.
        new SemanticAnalyzer(dependencies).analyze(module, false);
      } catch (final SemanticException exception) {
        throw new SemanticException(
            "In module '" + name + "': " + exception.getMessage(),
            exception.line(),
            exception.column());
      }
    }

    // Strict mode: also check imported module top-level work for
    // nondeterministic calls. Module init runs at import time (the
    // interpreter executes top-level const declarations and statements
    // during {@code visitImport}), so a strict main importing a module with
    // {@code const int t = std:time();} would silently invoke the impure
    // call. Function bodies are intentionally NOT re-checked here — the
    // cross-module call walker handles those at call time, and stdlib
    // modules legitimately export wrappers around nondeterministic
    // builtins that should not be rejected during analysis.
    if (options.strict()) {
      for (final var entry : loader.loaded().entrySet()) {
        try {
          SemanticAnalyzer.checkTopLevelPurity(entry.getValue(), entry.getKey(), registry);
        } catch (final SemanticException exception) {
          throw new SemanticException(
              "In module '" + entry.getKey() + "': " + exception.getMessage(),
              exception.line(),
              exception.column());
        }
      }
    }

    // Analyze the main program against the full registry.
    final var analyzer = new SemanticAnalyzer(registry);
    analyzer.analyze(program, options.strict(), options.bindings());
    return new CompilerFrontEnd.ParseResult(program, registry, analyzer.warnings());
  }

  /** Inputs to {@link #bootstrap}. */
  public record Options(
      Path source,
      List<String> preloads,
      boolean tolerateMissingPreloads,
      boolean strict,
      Set<String> bindings,
      List<Path> modulePath) {

    public Options {
      preloads = preloads != null ? List.copyOf(preloads) : List.of();
      bindings = bindings != null ? Set.copyOf(bindings) : Set.of();
      modulePath = modulePath != null ? List.copyOf(modulePath) : List.of();
    }

    /**
     * Default options: no source path, no preloads, non-strict, no extra bindings, no module path.
     */
    public static Options defaults() {
      return new Options(null, List.of(), false, false, Set.of(), List.of());
    }

    public Options withStrict(final boolean value) {
      return new Options(source, preloads, tolerateMissingPreloads, value, bindings, modulePath);
    }

    public Options withSource(final Path path) {
      return new Options(path, preloads, tolerateMissingPreloads, strict, bindings, modulePath);
    }

    public Options withPreloads(final List<String> modules, final boolean tolerate) {
      return new Options(source, modules, tolerate, strict, bindings, modulePath);
    }

    public Options withBindings(final Set<String> names) {
      return new Options(source, preloads, tolerateMissingPreloads, strict, names, modulePath);
    }

    /**
     * Add explicit module search directories. Each entry is checked after the source file's
     * directory and before the bundled stdlib classpath. The list replaces any previously set
     * module path.
     */
    public Options withModulePath(final List<Path> paths) {
      return new Options(source, preloads, tolerateMissingPreloads, strict, bindings, paths);
    }
  }
}
