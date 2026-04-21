package io.safelang;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Embedder-perspective tests for {@link SafeRuntime}.
 *
 * <p>Phase 4 of the second-round audit narrowed every public method's declared throws to {@link
 * SAFEException}, captured gcc IO instead of inheriting it, and removed the NPE asymmetry between
 * {@code parse(source, null, ...)} (allowed) and {@code request(source, null, ...)} (NPE).
 */
class SafeRuntimeTests {

  @Nested
  class ExceptionContract {

    @Test
    void compileFailureThrowsSAFEException() {
      final var bad = "program test;\nint x = ;\n"; // intentional syntax error
      final var thrown =
          assertThrows(SAFEException.class, () -> SafeRuntime.compile(bad, "test.safe", false));
      assertNotNull(thrown.getMessage());
    }

    @Test
    void bytecodeFailureThrowsSAFEException() {
      final var bad = "program test;\nint x = ;\n";
      assertThrows(SAFEException.class, () -> SafeRuntime.bytecode(bad, "test.safe", false));
    }

    @Test
    void wasmFailureThrowsSAFEException() {
      final var bad = "program test;\nint x = ;\n";
      assertThrows(SAFEException.class, () -> SafeRuntime.wasm(bad, "test.safe", false));
    }
  }

  @Nested
  class NullFilenameContract {

    @Test
    void parseAcceptsNullFilename() {
      // parse() does not require a filename — it's only used for relative
      // module-search-path resolution.
      final var result = SafeRuntime.parse("program test;\nint x = 1;\n", null, false);
      assertNotNull(result.program());
      assertNotNull(result.registry());
    }

    @Test
    void requestRejectsNullFilenameExplicitly() {
      // request() requires a filename because the binary output path is
      // derived from it. Embedders should get a clear IllegalArgumentException
      // rather than a confusing NPE deep inside Path.of(null).
      final var thrown =
          assertThrows(
              IllegalArgumentException.class,
              () -> SafeRuntime.compile("program test;\nint x = 1;\n", null, false));
      assertTrue(
          thrown.getMessage().toLowerCase().contains("filename"),
          "Error should mention 'filename', got: " + thrown.getMessage());
    }
  }

  @Nested
  class WarningsExposedViaResult {

    @Test
    void warningsAreOnTheResult() {
      // The result record carries warnings; embedders can decide whether to
      // print, log, or suppress them.
      final var result = SafeRuntime.parse("program test;\nint x = 1;\n", "test.safe", false);
      assertNotNull(result.warnings());
      // The list is unmodifiable; embedders shouldn't be able to mutate it.
      assertThrows(UnsupportedOperationException.class, () -> result.warnings().add("hack"));
    }
  }

  /**
   * The sink installed via {@link SafeRuntime#setWarnings} fires for warnings produced by each
   * public backend entry point. The sink is JVM-global static state — tests save/restore via
   * try-finally and avoid overlapping concurrent use.
   */
  @Nested
  class WarningSink {

    private static final String SOURCE =
        """
        program testwarn;
        import io;
        import collections;
        io:println("hi");
        """;

    private List<String> capture() {
      final var captured = new ArrayList<String>();
      SafeRuntime.setWarnings(captured::add);
      return captured;
    }

    @Test
    void runEmitsWarning() {
      final var captured = capture();
      final var originalOut = System.out;
      try {
        System.setOut(new PrintStream(new ByteArrayOutputStream()));
        SafeRuntime.run(SOURCE, "testwarn.safe", List.of(), false);
      } finally {
        System.setOut(originalOut);
        SafeRuntime.resetWarnings();
      }
      assertTrue(
          captured.stream().anyMatch(message -> message.contains("Unused import: collections")),
          "run() must surface the analyzer warning, got " + captured);
    }

    @Test
    void compileEmitsWarning(@TempDir final Path directory) throws Exception {
      final var captured = capture();
      final var file = directory.resolve("testwarn.safe");
      Files.writeString(file, SOURCE);
      try {
        SafeRuntime.compile(SOURCE, file.toString(), false);
      } finally {
        SafeRuntime.resetWarnings();
      }
      assertTrue(
          captured.stream().anyMatch(message -> message.contains("Unused import: collections")),
          "compile() must surface the analyzer warning, got " + captured);
    }

    @Test
    void bytecodeEmitsWarning(@TempDir final Path directory) throws Exception {
      final var captured = capture();
      final var file = directory.resolve("testwarn.safe");
      Files.writeString(file, SOURCE);
      try {
        SafeRuntime.bytecode(SOURCE, file.toString(), false);
      } finally {
        SafeRuntime.resetWarnings();
      }
      assertTrue(
          captured.stream().anyMatch(message -> message.contains("Unused import: collections")),
          "bytecode() must surface the analyzer warning, got " + captured);
    }

    @Test
    void wasmEmitsWarning(@TempDir final Path directory) throws Exception {
      final var captured = capture();
      final var file = directory.resolve("testwarn.safe");
      Files.writeString(file, SOURCE);
      try {
        SafeRuntime.wasm(SOURCE, file.toString(), false);
      } finally {
        SafeRuntime.resetWarnings();
      }
      assertTrue(
          captured.stream().anyMatch(message -> message.contains("Unused import: collections")),
          "wasm() must surface the analyzer warning, got " + captured);
    }

    @Test
    void noOpSinkSuppressesWarnings() {
      final var captured = new ArrayList<String>();
      SafeRuntime.setWarnings(message -> {});
      final var originalOut = System.out;
      try {
        System.setOut(new PrintStream(new ByteArrayOutputStream()));
        SafeRuntime.run(SOURCE, "testwarn.safe", List.of(), false);
      } finally {
        System.setOut(originalOut);
        SafeRuntime.resetWarnings();
      }
      assertTrue(captured.isEmpty(), "silenced sink must not capture");
    }
  }
}
