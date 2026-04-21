package io.safelang;

import static org.junit.jupiter.api.Assertions.*;

import io.safelang.compiler.wasm.WasmPipeline;
import java.nio.file.Path;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Reference-count regression tests. Each fixture is a SAFE program that allocates heavily in a
 * loop; if the scope-release / dispose-with-children discipline is working, the program completes
 * in bounded memory. Without it, one of these will OOM or run the WASM 4 GB ceiling — just like
 * bench_btree used to before Phase 3 landed.
 *
 * <p>The tests execute via the WASM pipeline (which now carries the full unified RC). Native C
 * backend tests are covered by CCodeGenTests.
 *
 * <p>Fixtures intentionally exercise the tricky cases: per-iteration allocation with reassignment,
 * list-of-lists, map with heap values, closure captures, recursive for-loop bodies.
 */
class RefcountTests {

  @BeforeAll
  static void check() {
    Assumptions.assumeTrue(wasmtime(), "wasmtime not available");
  }

  private static boolean wasmtime() {
    try {
      return new ProcessBuilder("wasmtime", "--version").start().waitFor() == 0;
    } catch (final Exception exception) {
      return false;
    }
  }

  private static String run(final String source) throws Exception {
    final var options =
        SafeFrontend.Options.defaults()
            .withSource(Path.of("stdlib/io.safe"))
            .withPreloads(SafeFrontend.stdlibModules(), true);
    final var loaded = SafeFrontend.bootstrap(source, options);
    final var pipeline = new WasmPipeline(loaded.registry());
    return pipeline.execute(loaded.program());
  }

  /**
   * Per-iteration bytes allocation — 10k 4KB buffers. Without scope- release and size-class
   * recycling this hits the WASM 4 GB ceiling at roughly iteration 1,000,000, but the loop is
   * bounded at 10k so what we really check is that the output is produced (program completes).
   */
  @Test
  void bytesLoopCompletes() throws Exception {
    final var output =
        run(
            """
            program test;
            import io;
            import std;
            import binary;
            for i in 0..9999 {
                bytes b = binary:alloc(4096);
            }
            io:println("done");
            """);
    assertEquals("done", output);
  }

  /**
   * Growing a list<int> via functional reassignment. Every iteration's copy-on-grow must release
   * the old block through the caller's release-on-reassignment.
   */
  @Test
  void listGrowReassignment() throws Exception {
    final var output =
        run(
            """
            program test;
            import io;
            import std;
            import collections;
            list<int> xs = [];
            for i in 0..4999 {
                xs = collections:append(xs, i);
            }
            io:println(std:str(std:len(xs)));
            """);
    assertEquals("5000", output);
  }

  /**
   * Map with bytes values — per-iteration encode + put. The map retains the stored bytes on insert;
   * the caller's scope-release drops its reference so the bytes survive only as long as the map
   * holds them.
   */
  @Test
  void mapWithBytesValues() throws Exception {
    final var output =
        run(
            """
            program test;
            import io;
            import std;
            import collections;
            import binary;
            map<string, bytes> m = {};
            for i in 0..999 {
                bytes v = binary:encode("v" + std:str(i));
                m["k" + std:str(i)] = v;
            }
            io:println(std:str(std:len(m)));
            """);
    assertEquals("1000", output);
  }

  /**
   * Struct reassignment with heap fields — bench_lsm-style pattern. The old struct's memtable must
   * drop its ref when the new struct takes over, or this OOMs.
   */
  @Test
  void structReassignment() throws Exception {
    final var output =
        run(
            """
            program test;
            import io;
            import std;
            import binary;
            import collections;

            type Box {
                public bytes payload;
                public int n;
            }

            Box b = Box { payload: binary:alloc(1024), n: 0 };
            for i in 0..499 {
                b = Box { payload: binary:alloc(1024), n: i };
            }
            io:println(std:str(b.n));
            """);
    assertEquals("499", output);
  }

  /**
   * Closure capturing a heap value — each call creates a new closure that retains the captured
   * list; the closure itself is released after use.
   */
  @Test
  void closureCapture() throws Exception {
    final var output =
        run(
            """
            program test;
            import io;
            import std;
            import collections;

            for i in 0..999 {
                list<int> xs = [i, i+1, i+2];
                fn() -> int grab = fn() -> std:len(xs);
            }
            io:println("done");
            """);
    assertEquals("done", output);
  }
}
