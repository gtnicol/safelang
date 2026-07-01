package io.safelang.interpreter.builtins;

import static org.junit.jupiter.api.Assertions.*;

import io.safelang.runtime.BuiltinExecutors;
import io.safelang.runtime.SAFEValue;
import io.safelang.runtime.StreamHandle;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Streaming file builtins: incremental read/write round-trips, EOF, jail, and cleanup-flush. */
class StreamBuiltinsTests {

  private BuiltinExecutors executors;
  private Map<Integer, StreamHandle> streams;

  @BeforeEach
  void setup() {
    executors = new BuiltinExecutors();
    streams = new HashMap<>();
    StreamBuiltins.register(
        executors, streams, new AtomicInteger(0), io.safelang.runtime.HostPolicy.trusted());
  }

  @AfterEach
  void teardown() throws Exception {
    io.safelang.interpreter.builtins.BuiltinRegistration.closeHandles(
        new HashMap<>(), new HashMap<>(), streams);
  }

  private SAFEValue call(final String name, final SAFEValue... args) {
    return executors.get(name).execute(List.of(args));
  }

  private SAFEValue openStream(final String path, final String mode) {
    final var result = call("sopen", SAFEValue.ofString(path), SAFEValue.ofString(mode));
    assertEquals("Ok", result.variant(), "sopen failed: " + result.data());
    return result.data().getFirst(); // the Stream object
  }

  @Test
  void testWriteThenReadLines(@TempDir final Path dir) {
    final var path = dir.resolve("out.txt").toString();
    final var w = openStream(path, "w");
    call("swrite", w, SAFEValue.ofString("alpha\n"));
    call("swrite", w, SAFEValue.ofString("beta\n"));
    call("sflush", w);
    call("sclose", w);

    final var r = openStream(path, "r");
    final var l1 = call("sline", r);
    final var l2 = call("sline", r);
    final var l3 = call("sline", r);
    call("sclose", r);

    assertEquals("Line", l1.variant());
    assertEquals("alpha", l1.data().getFirst().asString());
    assertEquals("beta", l2.data().getFirst().asString());
    assertEquals("End", l3.variant(), "third sline is EOF");
  }

  @Test
  void testReadChunks(@TempDir final Path dir) throws Exception {
    final var path = dir.resolve("chunks.txt");
    Files.writeString(path, "abcdef");
    final var r = openStream(path.toString(), "r");
    assertEquals("abc", call("sread", r, SAFEValue.ofInt(3)).data().getFirst().asString());
    assertEquals("def", call("sread", r, SAFEValue.ofInt(3)).data().getFirst().asString());
    assertEquals("", call("sread", r, SAFEValue.ofInt(3)).data().getFirst().asString(), "EOF");
    call("sclose", r);
  }

  @Test
  void testCleanupFlushesOpenWriteStream(@TempDir final Path dir) throws Exception {
    // A write stream left open on early exit must be flushed by the cleanup path.
    final var path = dir.resolve("unclosed.txt");
    final var w = openStream(path.toString(), "w");
    call("swrite", w, SAFEValue.ofString("persisted"));
    io.safelang.interpreter.builtins.BuiltinRegistration.closeHandles(
        new HashMap<>(), new HashMap<>(), streams);
    assertEquals("persisted", Files.readString(path));
  }

  @Test
  void testJailRejectsEscape(@TempDir final Path dir) {
    final var jailed =
        io.safelang.runtime.HostPolicy.sandbox().toBuilder()
            .capabilities(io.safelang.runtime.Capabilities.all())
            .fsRoot(dir)
            .build();
    final var jailedExecutors = new BuiltinExecutors();
    StreamBuiltins.register(jailedExecutors, new HashMap<>(), new AtomicInteger(0), jailed);
    final var result =
        jailedExecutors
            .get("sopen")
            .execute(List.of(SAFEValue.ofString("../escape.txt"), SAFEValue.ofString("w")));
    assertEquals("Err", result.variant(), "an escaping path must be rejected");
  }
}
