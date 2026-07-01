package io.safelang.interpreter.builtins;

import static org.junit.jupiter.api.Assertions.*;

import io.safelang.interpreter.InterpreterException;
import io.safelang.runtime.BuiltinExecutors;
import io.safelang.runtime.FileHandle;
import io.safelang.runtime.SAFEValue;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Tests the whole-file read/write size cap in the file builtins. */
class FileBuiltinsTests {

  private BuiltinExecutors executors;
  private Path file;
  private long savedCap;

  @BeforeEach
  void setup() throws Exception {
    executors = new BuiltinExecutors();
    FileBuiltins.register(
        executors,
        new HashMap<Integer, FileHandle>(),
        new AtomicInteger(0),
        io.safelang.runtime.HostPolicy.trusted());
    savedCap = FileBuiltins.MAX_FILE_BYTES;
    file = Files.createTempFile("safe-cap", ".txt");
    Files.writeString(file, "0123456789"); // 10 bytes
  }

  @AfterEach
  void teardown() throws Exception {
    FileBuiltins.MAX_FILE_BYTES = savedCap;
    Files.deleteIfExists(file);
  }

  private SAFEValue call(final String name, final SAFEValue... args) {
    return executors.get(name).execute(List.of(args));
  }

  @Test
  void testReadUnderCapSucceeds() {
    FileBuiltins.MAX_FILE_BYTES = 1024;
    assertEquals("0123456789", call("read", SAFEValue.ofString(file.toString())).asString());
  }

  @Test
  void testReadOverCapThrows() {
    FileBuiltins.MAX_FILE_BYTES = 5; // file is 10 bytes
    assertThrows(
        InterpreterException.class, () -> call("read", SAFEValue.ofString(file.toString())));
  }

  @Test
  void testFileOpenOverCapReturnsErr() {
    FileBuiltins.MAX_FILE_BYTES = 5;
    final var result =
        call("fileopen", SAFEValue.ofString(file.toString()), SAFEValue.ofString("r"));
    assertEquals("Err", result.variant());
    assertTrue(result.data().get(0).asString().contains("read limit"));
  }

  @Test
  void testFileLoadOverCapReturnsErr() {
    FileBuiltins.MAX_FILE_BYTES = 5;
    final var result = call("fileload", SAFEValue.ofString(file.toString()));
    assertEquals("Err", result.variant());
  }
}
