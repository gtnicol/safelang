package io.safelang;

import static org.junit.jupiter.api.Assertions.*;

import io.safelang.runtime.FileHandle;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** close() must flush a write/append handle's buffer so cleanup-on-error does not lose data. */
class FileHandleTests {

  @Test
  void testWriteHandleFlushesOnClose(@TempDir final Path dir) throws Exception {
    final var file = dir.resolve("out.txt");
    final var handle = new FileHandle(1, file.toString(), "w");
    handle.setBuffer(new StringBuilder("important data"));
    handle.close(); // the cleanup path calls only close() — it must flush
    assertEquals("important data", Files.readString(file));
  }

  @Test
  void testAppendHandleFlushesOnClose(@TempDir final Path dir) throws Exception {
    final var file = dir.resolve("log.txt");
    Files.writeString(file, "existing\n");
    final var handle = new FileHandle(1, file.toString(), "a");
    handle.setBuffer(new StringBuilder("appended"));
    handle.close();
    assertEquals("existing\nappended", Files.readString(file));
  }

  @Test
  void testDoubleCloseDoesNotReappend(@TempDir final Path dir) throws Exception {
    final var file = dir.resolve("log.txt");
    final var handle = new FileHandle(1, file.toString(), "a");
    handle.setBuffer(new StringBuilder("once"));
    handle.close();
    handle.close(); // second close (e.g. fileclose then cleanup) must not append twice
    assertEquals("once", Files.readString(file));
  }

  @Test
  void testReadHandleCloseWritesNothing(@TempDir final Path dir) throws Exception {
    final var file = dir.resolve("in.txt");
    Files.writeString(file, "original");
    final var handle = new FileHandle(1, file.toString(), "r");
    handle.setContent("original");
    handle.close();
    assertEquals("original", Files.readString(file));
  }
}
