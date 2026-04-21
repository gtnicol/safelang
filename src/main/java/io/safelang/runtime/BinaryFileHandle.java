package io.safelang.runtime;

import java.io.IOException;
import java.io.RandomAccessFile;

public class BinaryFileHandle {

  private final int id;
  private final String path;
  private final RandomAccessFile file;
  private boolean open;

  public BinaryFileHandle(final int id, final String path, final String mode) throws IOException {
    this.id = id;
    this.path = path;
    this.file = new RandomAccessFile(path, toMode(mode));
    if ("w".equals(mode)) {
      file.setLength(0);
    }
    this.open = true;
  }

  private static String toMode(final String mode) {
    return switch (mode) {
      case "r" -> "r";
      case "w", "rw" -> "rw";
      default -> throw new IllegalArgumentException("Invalid mode: " + mode + ". Use r, w, or rw");
    };
  }

  public int id() {
    return id;
  }

  public String path() {
    return path;
  }

  public boolean isOpen() {
    return open;
  }

  public byte[] read(final int count) throws IOException {
    final var buffer = new byte[count];
    final var read = file.read(buffer, 0, count);
    if (read < 0) return new byte[0];
    if (read < count) {
      final var result = new byte[read];
      System.arraycopy(buffer, 0, result, 0, read);
      return result;
    }
    return buffer;
  }

  public void write(final byte[] data) throws IOException {
    file.write(data);
  }

  public void seek(final long offset) throws IOException {
    file.seek(offset);
  }

  public long size() throws IOException {
    return file.length();
  }

  public void flush() throws IOException {
    file.getFD().sync();
  }

  public void close() throws IOException {
    if (open) {
      open = false;
      file.close();
    }
  }
}
