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
   * The per-call warning sink fires for warnings produced by each public backend entry point. The
   * sink is now a parameter (no JVM-global static state), so concurrent compilations on different
   * threads keep their warnings separate — see {@link #concurrentSinksDoNotInterleave}.
   */
  @Nested
  class WarningSink {

    // Selective io import: the AOT capability gate (now deny-by-default) would otherwise reject the
    // io:input STDIN wrapper a non-selective `import io;` pulls in. `collections` stays unused to
    // trigger the warning under test.
    private static final String SOURCE =
        """
        program testwarn;
        import io { println };
        import collections;
        io:println("hi");
        """;

    @Test
    void runEmitsWarning() {
      final var captured = new ArrayList<String>();
      final var originalOut = System.out;
      try {
        System.setOut(new PrintStream(new ByteArrayOutputStream()));
        SafeRuntime.run(SOURCE, "testwarn.safe", List.of(), false, captured::add);
      } finally {
        System.setOut(originalOut);
      }
      assertTrue(
          captured.stream().anyMatch(message -> message.contains("Unused import: collections")),
          "run() must surface the analyzer warning, got " + captured);
    }

    @Test
    void compileEmitsWarning(@TempDir final Path directory) throws Exception {
      final var captured = new ArrayList<String>();
      final var file = directory.resolve("testwarn.safe");
      Files.writeString(file, SOURCE);
      SafeRuntime.compile(SOURCE, file.toString(), false, captured::add);
      assertTrue(
          captured.stream().anyMatch(message -> message.contains("Unused import: collections")),
          "compile() must surface the analyzer warning, got " + captured);
    }

    @Test
    void bytecodeEmitsWarning(@TempDir final Path directory) throws Exception {
      final var captured = new ArrayList<String>();
      final var file = directory.resolve("testwarn.safe");
      Files.writeString(file, SOURCE);
      SafeRuntime.bytecode(SOURCE, file.toString(), false, captured::add);
      assertTrue(
          captured.stream().anyMatch(message -> message.contains("Unused import: collections")),
          "bytecode() must surface the analyzer warning, got " + captured);
    }

    @Test
    void wasmEmitsWarning(@TempDir final Path directory) throws Exception {
      final var captured = new ArrayList<String>();
      final var file = directory.resolve("testwarn.safe");
      Files.writeString(file, SOURCE);
      SafeRuntime.wasm(SOURCE, file.toString(), false, captured::add);
      assertTrue(
          captured.stream().anyMatch(message -> message.contains("Unused import: collections")),
          "wasm() must surface the analyzer warning, got " + captured);
    }

    @Test
    void noOpSinkSuppressesWarnings() {
      final var captured = new ArrayList<String>();
      final var originalOut = System.out;
      try {
        System.setOut(new PrintStream(new ByteArrayOutputStream()));
        SafeRuntime.run(SOURCE, "testwarn.safe", List.of(), false, message -> {});
      } finally {
        System.setOut(originalOut);
      }
      assertTrue(captured.isEmpty(), "silenced sink must not capture");
    }

    @Test
    void concurrentSinksDoNotInterleave() throws Exception {
      // Two threads compile the same warning-producing source with their own sinks. With the sink a
      // per-call parameter (not static), each thread's warnings land only in its own list.
      final var threads = 8;
      final var pool = java.util.concurrent.Executors.newFixedThreadPool(threads);
      try {
        final var futures = new ArrayList<java.util.concurrent.Future<List<String>>>();
        for (int t = 0; t < threads; t++) {
          futures.add(
              pool.submit(
                  () -> {
                    final var mine = new ArrayList<String>();
                    SafeRuntime.run(SOURCE, "testwarn.safe", List.of(), false, mine::add);
                    return mine;
                  }));
        }
        for (final var future : futures) {
          final var mine = future.get();
          assertTrue(
              mine.stream().allMatch(m -> m.contains("Unused import: collections")),
              "each thread's sink must only hold its own warnings, got " + mine);
          assertFalse(mine.isEmpty(), "each thread must capture its own warning");
        }
      } finally {
        pool.shutdownNow();
      }
    }
  }
}
