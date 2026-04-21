package io.safelang.compiler.wasm;

import io.safelang.ModuleRegistry;
import io.safelang.SAFEException;
import io.safelang.SafeMain;
import io.safelang.ast.ProgramNode;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Orchestrates per-module WASM compilation and multi-module linking.
 *
 * <p>Pipeline:
 *
 * <ol>
 *   <li>Build {@link TypeRegistry} from all modules + main program
 *   <li>Compile each dependency module to its own {@code .wasm} file
 *   <li>Compile main program to its own {@code .wasm} file
 *   <li>Link via wasmtime {@code --preload} at runtime
 * </ol>
 */
public final class WasmPipeline {

  /** Maximum closure arity for cross-module trampolines (arities 0..MAX_ARITY). */
  private static final int MAX_ARITY = 8;

  private final ModuleRegistry registry;

  public WasmPipeline(final ModuleRegistry registry) {
    this.registry = registry;
  }

  /**
   * Build the wasmtime argv that would run a compiled result from {@code directory} under the given
   * {@link RunOptions}. Single source of truth for command construction — {@link #runWithStatus}
   * and {@link io.safelang.compiler.WebAssemblyCompilerService} both go through here so the
   * displayed command and the actual invocation cannot drift.
   *
   * @param directory directory containing {@code safe_wasm_builtins.wasm}, each dependency module's
   *     {@code .wasm}, and the main binary
   * @param main path of the main program's {@code .wasm}
   * @param result compile result (names the dependency modules)
   * @param options run configuration (directory mounts, env, argv, stdin)
   */
  public static List<String> command(
      final Path directory,
      final Path main,
      final CompileResult result,
      final RunOptions options) {
    final var args = new ArrayList<String>();
    args.add("wasmtime");
    args.add("run");
    args.add("--preload");
    args.add("builtins=" + directory.resolve("safe_wasm_builtins.wasm"));
    for (final var module : result.modules().keySet()) {
      args.add("--preload");
      args.add(module + "=" + directory.resolve(module + ".wasm"));
    }
    for (final var path : options.directories()) {
      args.add("--dir");
      args.add(path);
    }
    for (final var entry : options.environment().entrySet()) {
      args.add("--env");
      args.add(entry.getKey() + "=" + entry.getValue());
    }
    args.add(main.toString());
    args.addAll(options.arguments());
    return args;
  }

  /**
   * Shell-quote a command produced by {@link #command} for display. Uses single quotes for
   * arguments containing spaces or shell metacharacters so the string is safe to paste verbatim
   * into a terminal.
   */
  public static String displayCommand(final List<String> args) {
    final var builder = new StringBuilder();
    for (var i = 0; i < args.size(); i++) {
      if (i > 0) {
        builder.append(' ');
      }
      builder.append(quote(args.get(i)));
    }
    return builder.toString();
  }

  private static String quote(final String argument) {
    if (argument.matches("[A-Za-z0-9_\\-./=:@+,]+")) {
      return argument;
    }
    return "'" + argument.replace("'", "'\\''") + "'";
  }

  /** Run a compiled result via wasmtime with --preload for each module. */
  public static String run(final CompileResult result) throws Exception {
    return run(result, RunOptions.defaults());
  }

  /** Run a compiled result via wasmtime with custom stdin/argv/environment. */
  public static String run(final CompileResult result, final RunOptions options) throws Exception {
    final var status = runWithStatus(result, options);
    if (status.exit() != 0) {
      throw new SAFEException(
          "wasmtime exited with code " + status.exit() + ": " + status.output());
    }
    return status.output();
  }

  /**
   * Run a compiled result via wasmtime, returning both the exit code and the captured output
   * without throwing. Use this when the caller needs to inspect a non-zero exit alongside the
   * output (e.g. test runners that parse output for [PASS]/[FAIL] lines even on assertion failure).
   */
  public static RunResult runWithStatus(final CompileResult result, final RunOptions options)
      throws Exception {
    final var directory = Files.createTempDirectory("safe_wasm_");
    try {
      // Write builtins module
      SafeMain.extractWasmBuiltins(directory);

      // Write each compiled module
      for (final var entry : result.modules().entrySet()) {
        final var path = directory.resolve(entry.getKey() + ".wasm");
        Files.write(path, entry.getValue());
      }

      // Write main module
      final var main = directory.resolve("main.wasm");
      Files.write(main, result.main());

      // Build wasmtime command via the shared helper — single source of truth.
      final var args = command(directory, main, result, options);

      final var process = new ProcessBuilder(args).redirectErrorStream(true).start();
      try (var input = process.getOutputStream()) {
        if (options.stdin() != null) {
          input.write(options.stdin().getBytes(StandardCharsets.UTF_8));
        }
      }
      final var output = new ByteArrayOutputStream();
      process.getInputStream().transferTo(output);
      final var exit = process.waitFor();
      return new RunResult(exit, output.toString(StandardCharsets.UTF_8).stripTrailing());
    } finally {
      if (Boolean.getBoolean("safe.wasm.keep")) {
        System.err.println("[wasm] keeping temp dir: " + directory);
      } else {
        try (var paths = Files.walk(directory)) {
          paths
              .sorted(Comparator.reverseOrder())
              .forEach(
                  path -> {
                    try {
                      Files.deleteIfExists(path);
                    } catch (Exception ignored) {
                    }
                  });
        }
      }
    }
  }

  /** Compile the main program and all its dependencies to WASM binaries. */
  public CompileResult compile(final ProgramNode program) {
    // Phase 1: Build global type registry
    final var types = TypeRegistry.build(registry, program);

    // Phase 2: Determine dependency order (topological)
    final var order = dependencyOrder(program);

    // Phase 2b: Shared closure dispatch module (table + trampolines)
    final var compiled = new LinkedHashMap<String, byte[]>();
    compiled.put("__closures", createClosureModule());

    // Phase 3: Compile each dependency module with coordinated offsets
    // All modules share linear memory (data sections) and function table (lambda slots).
    var dataOffset = WasmRuntime.DATA_START;
    var tableOffset = 0;
    for (final var module : order) {
      final var source = registry.program(module);
      if (source == null) continue;

      final var symbols = new ModuleSymbols();
      final var compiler =
          new WasmCompiler(module, false, types, symbols, registry, dataOffset, tableOffset);
      compiled.put(module, compiler.compile(source));
      dataOffset = compiler.dataEnd();
      tableOffset = compiler.tableEnd();
    }

    // Phase 4: Compile main program (offsets start after all modules)
    final var symbols = new ModuleSymbols();
    final var compiler =
        new WasmCompiler(
            TypeRegistry.MAIN, true, types, symbols, registry, dataOffset, tableOffset);
    final var binary = compiler.compile(program);

    return new CompileResult(compiled, binary);
  }

  /** Compile and execute via wasmtime. Returns stdout output. */
  public String execute(final ProgramNode program) throws Exception {
    final var result = compile(program);
    return run(result);
  }

  /**
   * Determine the dependency order of modules (topological sort). Only includes modules directly or
   * transitively imported by the main program.
   */
  private List<String> dependencyOrder(final ProgramNode program) {
    final var visited = new LinkedHashSet<String>();
    final var result = new ArrayList<String>();

    for (final var imported : program.imports()) {
      visit(imported.module(), visited, result);
    }
    return result;
  }

  private void visit(final String module, final Set<String> visited, final List<String> result) {
    if (visited.contains(module)) return;
    visited.add(module);

    final var source = registry.program(module);
    if (source != null) {
      for (final var imported : source.imports()) {
        visit(imported.module(), visited, result);
      }
    }
    result.add(module);
  }

  /**
   * Create a shared closure dispatch module. Exports a funcref table and __callN trampolines (N =
   * 0..MAX_ARITY) for cross-module closure calls. All modules import the table and appropriate
   * __callN from this module.
   */
  private byte[] createClosureModule() {
    final var mod = new WasmModule();
    mod.importMemory("builtins", "memory");

    // Shared funcref table (max 1024 entries)
    mod.addTable(1024);
    mod.exportTable("__table");

    // Generate __callN for each arity 0..MAX_ARITY
    for (var arity = 0; arity <= MAX_ARITY; arity++) {
      // Trampoline signature: (i64 closure, i64 arg0, ..., i64 argN-1) -> i64
      final var params = new int[1 + arity];
      for (var i = 0; i < params.length; i++) params[i] = WasmOpcode.TYPE_I64;
      final var trampolineType = mod.addType(params, new int[] {WasmOpcode.TYPE_I64});
      final var funcIdx = mod.addFunction(trampolineType);
      mod.exportFunction("__call" + arity, funcIdx);

      // Target signature: (i32 env, i64 arg0, ..., i64 argN-1) -> i64
      final var targetParams = new int[1 + arity];
      targetParams[0] = WasmOpcode.TYPE_I32;
      for (var i = 0; i < arity; i++) targetParams[1 + i] = WasmOpcode.TYPE_I64;
      final var targetType = mod.addType(targetParams, new int[] {WasmOpcode.TYPE_I64});

      final var fn = new WasmFunction(funcIdx, trampolineType, 1 + arity);
      final var ptr = fn.addLocal(WasmOpcode.TYPE_I32);
      final var env = fn.addLocal(WasmOpcode.TYPE_I32);

      // Untag closure pointer: ptr = (closure >> TAG_BITS) as i32
      fn.emitLocalGet(0); // closure param
      fn.emitI64Const(WasmRuntime.TAG_BITS);
      fn.emit(WasmOpcode.I64_SHR_U);
      fn.emit(WasmOpcode.I32_WRAP_I64);
      fn.emitLocalSet(ptr);

      // env = ptr (whole closure struct is the env)
      fn.emitLocalGet(ptr);
      fn.emitLocalSet(env);

      // Push env as first arg to lambda
      fn.emitLocalGet(env);

      // Push additional args (params 1..arity)
      for (var i = 0; i < arity; i++) {
        fn.emitLocalGet(1 + i);
      }

      // Load table index from closure[0]
      fn.emitLocalGet(ptr);
      fn.emitLoad(WasmOpcode.I32_LOAD, 2, 0);

      // call_indirect with target type
      fn.emitCallIndirect(targetType, 0);

      mod.addCode(funcIdx, fn.encode(mod));
    }

    mod.exportMemory("memory", 0);
    return mod.assemble();
  }

  /**
   * Wasmtime run options.
   *
   * <p>Defaults are <em>hermetic</em>: no host environment, no directory mounts, no command-line
   * arguments. Callers that need impure access must construct an explicit {@code RunOptions} with
   * the directories and environment they expect to read.
   */
  public record RunOptions(
      List<String> arguments,
      String stdin,
      Map<String, String> environment,
      List<String> directories) {

    public RunOptions {
      arguments = List.copyOf(arguments != null ? arguments : List.of());
      environment = Map.copyOf(environment != null ? environment : Map.of());
      directories = List.copyOf(directories != null ? directories : List.of());
    }

    /** Hermetic defaults: no environment, no directory mounts, no arguments. */
    public static RunOptions defaults() {
      return new RunOptions(List.of(), null, Map.of(), List.of());
    }

    /** Permissive defaults: inherit host environment and mount /tmp + cwd. */
    public static RunOptions permissive() {
      return new RunOptions(List.of(), null, System.getenv(), List.of("/tmp", "."));
    }

    /**
     * Options for displaying a user-pasteable run command. Matches {@link #permissive} for
     * filesystem mounts ({@code /tmp} + cwd) but inherits an empty environment — the user's shell
     * already has their host environment, and echoing every host env var into the displayed command
     * is noise, not information.
     */
    public static RunOptions display() {
      return new RunOptions(List.of(), null, Map.of(), List.of("/tmp", "."));
    }
  }

  /**
   * Result of compilation: a map of module name to WASM binary bytes, plus the main program binary
   * as a separate {@code main} field.
   */
  public record CompileResult(Map<String, byte[]> modules, byte[] main) {}

  /**
   * Result of executing a compiled wasm module via {@link #runWithStatus}: the wasmtime exit code
   * and the captured (combined stdout+stderr) output. Used by callers that need to read the output
   * even on a non-zero exit (e.g. {@link io.safelang.TestRunner}, which parses [PASS]/[FAIL] lines
   * from a process that may exit non-zero on assertion failure).
   */
  public record RunResult(int exit, String output) {}
}
