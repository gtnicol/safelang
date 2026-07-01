package io.safelang.runtime;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link StreamHandle#readLine()} must be bounded (no OOM on a huge newline-less line) and honor
 * \n, \r, and \r\n terminators like {@code BufferedReader}.
 */
class StreamHandleTests {

  private long savedCap;

  @AfterEach
  void teardown() {
    if (savedCap != 0) {
      StreamHandle.MAX_READ = savedCap;
    }
  }

  @Test
  void testReadLineRejectsOverlongLine(@TempDir final Path dir) throws Exception {
    savedCap = StreamHandle.MAX_READ;
    StreamHandle.MAX_READ = 16; // tight cap for the test
    final var file = dir.resolve("huge.txt");
    Files.writeString(file, "a".repeat(1000)); // one newline-less line, far over the cap
    final var handle = new StreamHandle(0, file.toString(), "r");
    try {
      final var error = assertThrows(java.io.IOException.class, handle::readLine);
      assertTrue(error.getMessage().contains("exceeds"));
    } finally {
      handle.close();
    }
  }

  @Test
  void testReadLineTerminators(@TempDir final Path dir) throws Exception {
    final var file = dir.resolve("lines.txt");
    Files.writeString(file, "unix\nwindows\r\nmac\rlast");
    final var handle = new StreamHandle(0, file.toString(), "r");
    try {
      assertEquals("unix", handle.readLine());
      assertEquals("windows", handle.readLine());
      assertEquals("mac", handle.readLine());
      assertEquals("last", handle.readLine()); // final line, no terminator
      assertNull(handle.readLine()); // EOF
    } finally {
      handle.close();
    }
  }
}
