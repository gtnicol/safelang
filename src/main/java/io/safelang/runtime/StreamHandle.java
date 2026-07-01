package io.safelang.runtime;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * A streaming text-file handle backed by a {@link BufferedReader} (mode {@code "r"}) or {@link
 * BufferedWriter} (mode {@code "w"} truncate / {@code "a"} append). Unlike {@link FileHandle}
 * (which buffers the whole file in memory), this reads and writes incrementally so large files can
 * be processed without loading them into the heap.
 */
public class StreamHandle {

  // Upper bound on a single sread so a guest cannot request an attacker-sized array and OOM the
  // host.
  static long MAX_READ = 64L * 1024 * 1024; // 64 MiB

  public static long maxRead() {
    return MAX_READ;
  }

  private final int id;
  private final String path;
  private BufferedReader reader;
  private BufferedWriter writer;
  private boolean open = true;

  public StreamHandle(final int id, final String path, final String mode) throws IOException {
    this.id = id;
    this.path = path;
    switch (mode) {
      case "r" -> reader = Files.newBufferedReader(Path.of(path), StandardCharsets.UTF_8);
      case "w" ->
          writer =
              Files.newBufferedWriter(
                  Path.of(path),
                  StandardCharsets.UTF_8,
                  StandardOpenOption.CREATE,
                  StandardOpenOption.TRUNCATE_EXISTING,
                  StandardOpenOption.WRITE);
      case "a" ->
          writer =
              Files.newBufferedWriter(
                  Path.of(path),
                  StandardCharsets.UTF_8,
                  StandardOpenOption.CREATE,
                  StandardOpenOption.APPEND,
                  StandardOpenOption.WRITE);
      default -> throw new IOException("Invalid stream mode: " + mode + ". Use r, w, or a");
    }
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

  /**
   * Read the next line (without the line terminator), or {@code null} at end of stream. Bounded at
   * {@link #MAX_READ} characters so a single attacker-controlled newline-less line cannot allocate
   * until the host OOMs — {@code BufferedReader.readLine()} has no such cap. Terminators {@code
   * \n}, {@code \r}, and {@code \r\n} are all consumed, matching {@code BufferedReader} semantics.
   */
  public String readLine() throws IOException {
    if (reader == null) {
      throw new IOException("stream is not open for reading");
    }
    final var line = new StringBuilder();
    var c = reader.read();
    if (c < 0) {
      return null; // EOF, no data
    }
    while (c >= 0 && c != '\n') {
      if (c == '\r') {
        reader.mark(1);
        final var next = reader.read();
        if (next != '\n' && next >= 0) {
          reader.reset(); // a lone \r terminates; put back the non-\n character
        }
        break;
      }
      if (line.length() >= MAX_READ) {
        throw new IOException("line exceeds the " + MAX_READ + "-character limit");
      }
      line.append((char) c);
      c = reader.read();
    }
    return line.toString();
  }

  /** Read up to {@code count} characters; an empty string signals end of stream. */
  public String read(final int count) throws IOException {
    if (reader == null) {
      throw new IOException("stream is not open for reading");
    }
    if (count < 0 || count > MAX_READ) {
      throw new IOException("invalid read count " + count);
    }
    final var buffer = new char[count];
    final var read = reader.read(buffer, 0, count);
    return read < 0 ? "" : new String(buffer, 0, read);
  }

  public void write(final String content) throws IOException {
    if (writer == null) {
      throw new IOException("stream is not open for writing");
    }
    writer.write(content);
  }

  public void flush() throws IOException {
    if (writer != null) {
      writer.flush();
    }
  }

  public void close() throws IOException {
    if (open) {
      open = false;
      if (writer != null) {
        writer.flush();
        writer.close();
      }
      if (reader != null) {
        reader.close();
      }
    }
  }
}
