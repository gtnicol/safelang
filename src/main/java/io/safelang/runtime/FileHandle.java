package io.safelang.runtime;

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

  public void close() {
    this.open = false;
  }
}
