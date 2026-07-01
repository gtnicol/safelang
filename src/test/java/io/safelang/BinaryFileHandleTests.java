package io.safelang;

import static org.junit.jupiter.api.Assertions.*;

import io.safelang.runtime.BinaryFileHandle;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Lazy-seek correctness: the handle only re-seeks when the physical pointer moved, so sequential
 * and random access must both still return the right bytes.
 */
class BinaryFileHandleTests {

  private static byte[] bytes(final int... values) {
    final var out = new byte[values.length];
    for (int i = 0; i < values.length; i++) {
      out[i] = (byte) values[i];
    }
    return out;
  }

  @Test
  void testSequentialReads(@TempDir final Path dir) throws Exception {
    final var file = dir.resolve("seq.bin");
    Files.write(file, bytes(1, 2, 3, 4, 5, 6));
    final var handle = new BinaryFileHandle(0, file.toString(), "r");
    try {
      assertArrayEquals(bytes(1, 2), handle.read(2)); // first read seeks to 0
      assertArrayEquals(bytes(3, 4), handle.read(2)); // sequential — pointer already at 2
      assertArrayEquals(bytes(5, 6), handle.read(2));
      assertEquals(0, handle.read(2).length, "EOF returns empty");
    } finally {
      handle.close();
    }
  }

  @Test
  void testSeekBackThenRead(@TempDir final Path dir) throws Exception {
    final var file = dir.resolve("rand.bin");
    Files.write(file, bytes(10, 20, 30, 40));
    final var handle = new BinaryFileHandle(0, file.toString(), "r");
    try {
      assertArrayEquals(bytes(10, 20, 30), handle.read(3));
      handle.seek(1); // jump backward — next read must re-seek
      assertArrayEquals(bytes(20, 30), handle.read(2));
      handle.seek(0);
      assertArrayEquals(bytes(10), handle.read(1));
    } finally {
      handle.close();
    }
  }

  @Test
  void testWriteThenReadAtNewPosition(@TempDir final Path dir) throws Exception {
    final var file = dir.resolve("rw.bin");
    final var handle = new BinaryFileHandle(0, file.toString(), "rw");
    try {
      handle.write(bytes(1, 2, 3, 4));
      handle.seek(1);
      assertArrayEquals(bytes(2, 3), handle.read(2)); // read after a backward seek
      handle.write(bytes(99)); // overwrite byte at position 3
      handle.seek(0);
      assertArrayEquals(bytes(1, 2, 3, 99), handle.read(4));
    } finally {
      handle.close();
    }
  }
}
