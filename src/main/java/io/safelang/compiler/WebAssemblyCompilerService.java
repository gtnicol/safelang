package io.safelang.compiler;

import io.safelang.SafeMain;
import io.safelang.compiler.wasm.WasmPipeline;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;

/** Compiles SAFE programs to WebAssembly modules. */
public final class WebAssemblyCompilerService implements SafeCompiler {

  @Override
  public SafeCompileResult compile(final CompileRequest request) throws Exception {
    final var pipeline = new WasmPipeline(request.registry(), request.capabilities());
    final var compiled = pipeline.compile(request.program());
    final var directory = request.directory();
    SafeMain.extractWasmBuiltins(directory);
    final var artifacts = new ArrayList<Path>();
    artifacts.add(directory.resolve("safe_wasm_builtins.wasm"));

    for (final var entry : compiled.modules().entrySet()) {
      final var moduleFile = directory.resolve(entry.getKey() + ".wasm");
      Files.write(moduleFile, entry.getValue());
      artifacts.add(moduleFile);
    }

    final var mainFile = request.withExtension(".wasm");
    Files.write(mainFile, compiled.main());
    artifacts.add(mainFile);

    // Build the display command through the same helper WasmPipeline.runWithStatus uses so
    // the string shown to the user matches the actual invocation. Uses RunOptions.display()
    // (mounts /tmp and cwd, empty env) — the user's shell already holds their host env, so
    // echoing it back adds noise, not information.
    final var command =
        WasmPipeline.displayCommand(
            WasmPipeline.command(directory, mainFile, compiled, WasmPipeline.RunOptions.display()));

    return new SafeCompileResult(mainFile, artifacts, command);
  }
}
