package io.safelang;

import static org.junit.jupiter.api.Assertions.*;

import java.io.*;
import java.nio.file.*;
import org.junit.jupiter.api.*;

class TestRunnerTests {

  /**
   * Check if a backend is enabled. When no profiles are active (all properties empty), all backends
   * are enabled. When at least one profile sets a property to "true", only the explicitly enabled
   * backends run.
   */
  private static boolean enabled(final String backend) {
    final var property = System.getProperty("safe.test." + backend, "");
    if ("true".equalsIgnoreCase(property)) return true;
    // If no profile has set any property to "true", default to all enabled
    return !anyProfileActive();
  }

  private static boolean anyProfileActive() {
    return "true".equalsIgnoreCase(System.getProperty("safe.test.interpreter", ""))
        || "true".equalsIgnoreCase(System.getProperty("safe.test.bytecode", ""))
        || "true".equalsIgnoreCase(System.getProperty("safe.test.native", ""))
        || "true".equalsIgnoreCase(System.getProperty("safe.test.wasm", ""));
  }

  private static boolean wasmtime() {
    try {
      return new ProcessBuilder("wasmtime", "--version").start().waitFor() == 0;
    } catch (Exception exception) {
      return false;
    }
  }

  private static boolean gcc() {
    try {
      return new ProcessBuilder("gcc", "--version").start().waitFor() == 0;
    } catch (Exception exception) {
      return false;
    }
  }

  // ========== Interpreter Backend ==========

  @Test
  void interpreterDirectory() {
    Assumptions.assumeTrue(enabled("interpreter"), "interpreter backend not enabled");
    final var runner = new TestRunner(false, false, false, false);
    assertEquals(0, runner.execute("tests/"));
  }

  @Test
  void interpreterSingleTest() {
    Assumptions.assumeTrue(enabled("interpreter"), "interpreter backend not enabled");
    final var runner = new TestRunner(false, false, false, false);
    assertEquals(0, runner.execute("tests/test_option.safe"));
  }

  @Test
  void interpreterFailingTest() throws Exception {
    Assumptions.assumeTrue(enabled("interpreter"), "interpreter backend not enabled");
    final var temp = Files.createTempFile("safe_test_fail_", ".safe");
    Files.writeString(
        temp,
        """
        program test_fail;
        import test;
        test:equal("a", "b", "should fail");
        test:done();
        """);
    try {
      final var runner = new TestRunner(false, false, false, false);
      assertEquals(1, runner.execute(temp.toString()));
    } finally {
      Files.deleteIfExists(temp);
    }
  }

  // ========== Bytecode Backend ==========

  @Test
  void bytecodeDirectory() {
    Assumptions.assumeTrue(enabled("bytecode"), "bytecode backend not enabled");
    final var runner = new TestRunner(false, false, true, false);
    assertEquals(0, runner.execute("tests/"));
  }

  @Test
  void bytecodeSingleTest() {
    Assumptions.assumeTrue(enabled("bytecode"), "bytecode backend not enabled");
    final var runner = new TestRunner(false, false, true, false);
    assertEquals(0, runner.execute("tests/test_math.safe"));
  }

  // ========== C/Native Backend ==========

  @Test
  void nativeDirectory() {
    Assumptions.assumeTrue(enabled("native"), "native backend not enabled");
    Assumptions.assumeTrue(gcc(), "gcc not available");
    final var runner = new TestRunner(false, true, false, false);
    assertEquals(0, runner.execute("tests/"));
  }

  @Test
  void nativeSingleTest() {
    Assumptions.assumeTrue(enabled("native"), "native backend not enabled");
    Assumptions.assumeTrue(gcc(), "gcc not available");
    final var runner = new TestRunner(false, true, false, false);
    assertEquals(0, runner.execute("tests/test_strings.safe"));
  }

  // ========== Wasm Backend ==========

  @Test
  void wasmDirectory() {
    Assumptions.assumeTrue(enabled("wasm"), "wasm backend not enabled");
    Assumptions.assumeTrue(wasmtime(), "wasmtime not available");
    final var runner = new TestRunner(false, false, false, true);
    assertEquals(0, runner.execute("tests/"));
  }

  @Test
  void wasmSingleTest() {
    Assumptions.assumeTrue(enabled("wasm"), "wasm backend not enabled");
    Assumptions.assumeTrue(wasmtime(), "wasmtime not available");
    final var runner = new TestRunner(false, false, false, true);
    assertEquals(0, runner.execute("tests/test_option.safe"));
  }
}
