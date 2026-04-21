package io.safelang;

import static org.junit.jupiter.api.Assertions.*;

import io.safelang.compiler.wasm.WasmPipeline;
import io.safelang.parser.SAFEParser;
import java.nio.file.Path;
import java.util.ArrayList;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class WasmStdlibDiagnosticTest {

  @BeforeAll
  static void check() {
    try {
      Assumptions.assumeTrue(new ProcessBuilder("wasmtime", "--version").start().waitFor() == 0);
    } catch (Exception exception) {
      Assumptions.assumeTrue(false, "wasmtime not available");
    }
  }

  @Test
  void stdlibModulesCompileThroughPipeline() throws Exception {
    final var modules = TestHelper.stdlibModules();
    final var loader = new ModuleLoader(Path.of("stdlib/io.safe"));
    final var registry = new ModuleRegistry();
    for (final var module : modules) {
      registry.register(module, loader.load(module));
    }

    final var failures = new ArrayList<String>();
    for (final var module : modules) {
      try {
        final var program =
            SAFEParser.parse(
                """
            program diag;
            import %s;
            """
                    .formatted(module));
        final var pipeline = new WasmPipeline(registry);
        WasmPipeline.run(pipeline.compile(program), WasmPipeline.RunOptions.defaults());
      } catch (Exception exception) {
        final var message =
            exception.getMessage() != null
                ? exception.getMessage().replace('\n', ' ')
                : exception.getClass().getSimpleName();
        failures.add(module + ": " + message);
      }
    }

    assertTrue(failures.isEmpty(), "WASM stdlib failures:\n" + String.join("\n", failures));
  }
}
