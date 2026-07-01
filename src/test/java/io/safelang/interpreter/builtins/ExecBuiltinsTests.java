package io.safelang.interpreter.builtins;

import static org.junit.jupiter.api.Assertions.*;

import io.safelang.runtime.BuiltinExecutors;
import io.safelang.runtime.SAFEValue;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Direct tests of the {@code system:exec} executor (the {@code system_exec} builtin), including the
 * timeout and capture-cap guards. Lives in the builtin package so it can shrink the package-private
 * limit fields for fast, deterministic coverage; {@link #teardown} restores the defaults.
 */
class ExecBuiltinsTests {

  private BuiltinExecutors executors;
  private long savedTimeout;
  private int savedCapture;

  @BeforeEach
  void setup() {
    executors = new BuiltinExecutors();
    ExecBuiltins.register(executors, io.safelang.runtime.HostPolicy.trusted());
    savedTimeout = ExecBuiltins.TIMEOUT_SECONDS;
    savedCapture = ExecBuiltins.MAX_CAPTURE;
  }

  @AfterEach
  void teardown() {
    ExecBuiltins.TIMEOUT_SECONDS = savedTimeout;
    ExecBuiltins.MAX_CAPTURE = savedCapture;
  }

  private SAFEValue exec(final String... argv) {
    final var args = new ArrayList<SAFEValue>();
    for (final var arg : argv) {
      args.add(SAFEValue.ofString(arg));
    }
    return executors.get("system_exec").execute(List.of(SAFEValue.ofList(args)));
  }

  @Test
  void testEchoCapturesStdout() {
    final var result = exec("echo", "hello");
    assertEquals("RunResult", result.enumType());
    assertEquals("Ok", result.variant());
    final var output = result.data().get(0);
    assertEquals(0L, output.fields().get("exit").asInt());
    assertEquals("hello\n", output.fields().get("stdout").asString());
  }

  @Test
  void testExitCodePropagates() {
    final var result = exec("sh", "-c", "exit 7");
    assertEquals("Ok", result.variant());
    assertEquals(7L, result.data().get(0).fields().get("exit").asInt());
  }

  @Test
  void testStderrCaptured() {
    final var result = exec("sh", "-c", "echo boom 1>&2");
    assertEquals("Ok", result.variant());
    assertEquals("boom\n", result.data().get(0).fields().get("stderr").asString());
  }

  @Test
  void testMissingCommandReturnsErr() {
    assertEquals("Err", exec("__no_such_command_xyz__").variant());
  }

  @Test
  void testEmptyCommandReturnsErr() {
    final var result = executors.get("system_exec").execute(List.of(SAFEValue.ofList(List.of())));
    assertEquals("Err", result.variant());
  }

  @Test
  void testTimeoutKillsAndReturnsErr() {
    ExecBuiltins.TIMEOUT_SECONDS = 1;
    final var start = System.nanoTime();
    final var result = exec("sleep", "30");
    final var elapsedSeconds = (System.nanoTime() - start) / 1_000_000_000.0;
    assertEquals("Err", result.variant());
    assertTrue(result.data().get(0).asString().contains("timed out"), "should report a timeout");
    assertTrue(elapsedSeconds < 10, "should give up near the 1s limit, not wait for the child");
  }

  @Test
  void testCaptureCapReturnsErrPromptly() {
    ExecBuiltins.MAX_CAPTURE = 1024;
    // yes streams forever — the cap must trip, kill the child, and return at once. Before the kill
    // hook, the flooded pipe blocked the child and waitFor wedged for the full timeout.
    final var start = System.nanoTime();
    final var result = exec("yes");
    final var elapsedSeconds = (System.nanoTime() - start) / 1_000_000_000.0;
    assertEquals("Err", result.variant());
    assertTrue(result.data().get(0).asString().contains("exceeds"), "should report a capture cap");
    assertTrue(
        elapsedSeconds < 10, "cap must kill the child immediately, not wait for the timeout");
  }
}
