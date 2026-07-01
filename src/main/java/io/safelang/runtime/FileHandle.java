package io.safelang.runtime;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Holds per-handle Java state for SAFE file I/O. Modes: "r" (read), "w" (write/truncate), "a"
 * (append).
 */
public class FileHandle {

  private final int id;
  private final String path;
  private final String mode;
  private boolean open;
  private String content;
  private StringBuilder buffer;

  public FileHandle(final int id, final String path, final String mode) {
    this.id = id;
    this.path = path;
    this.mode = mode;
    this.open = true;
    this.content = null;
    this.buffer = null;
  }

  public int id() {
    return id;
  }

  public String path() {
    return path;
  }

  public String mode() {
    return mode;
  }

  public boolean isOpen() {
    return open;
  }

  public String content() {
    return content;
  }

  public StringBuilder buffer() {
    return buffer;
  }

  public void setContent(final String content) {
    this.content = content;
  }

  public void setBuffer(final StringBuilder buffer) {
    this.buffer = buffer;
  }

  /**
   * Flush a write/append handle's buffered contents to disk, then mark it closed. Centralizing the
   * flush here (rather than only in the {@code fileclose} builtin) means the interpreter/VM cleanup
   * path persists buffered writes too, so a script that errors with an open write handle does not
   * silently lose data. {@code path} is already the jail-confined path resolved at open time. The
   * buffer is cleared after a successful flush so a double {@code close} cannot re-append.
   */
  public void close() throws IOException {
    if (open && buffer != null && ("w".equals(mode) || "a".equals(mode))) {
      if ("a".equals(mode)) {
        Files.writeString(
            Path.of(path), buffer.toString(), StandardOpenOption.CREATE, StandardOpenOption.APPEND);
      } else {
        Files.writeString(Path.of(path), buffer.toString());
      }
      buffer = null;
    }
    this.open = false;
  }
}
