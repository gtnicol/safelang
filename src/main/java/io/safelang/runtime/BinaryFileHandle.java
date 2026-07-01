package io.safelang.runtime;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Binary file handle backed by a {@link RandomAccessFile}, with a bounded per-handle read cache.
 *
 * <p>The on-disk stdlib databases ({@code page}/{@code dbm}/{@code lsm}/{@code btree}) read the
 * same fixed-size blocks repeatedly via {@code binary:seek} + {@code binary:read}; without a cache
 * every load is a physical seek+read. The cache keys recently read blocks by file offset
 * (access-ordered, capped) and is write-through with range-invalidation, so a single process that
 * reads and writes only through this handle always sees its own writes. Reads larger than {@link
 * #MAX_BLOCK} bypass the cache, so general binary I/O is unaffected.
 */
public class BinaryFileHandle {

  private static final int MAX_BLOCK = 16 * 1024; // only cache block-sized reads
  private static final int MAX_BLOCKS = 256; // ~4 MiB ceiling per handle

  // Upper bound on a single read so a guest cannot request an attacker-sized array and OOM the
  // host. Package-private and non-final so tests can shrink it.
  static long MAX_READ = 64L * 1024 * 1024; // 64 MiB

  /** The single-read byte cap, exposed for the {@code bread} builtin's pre-cast validation. */
  public static long maxRead() {
    return MAX_READ;
  }

  private final int id;
  private final String path;
  private final RandomAccessFile file;
  private final Map<Long, byte[]> cache;
  private long position; // logical file position; the RAF pointer is re-synced before each disk op
  private long filePos = -1; // last known PHYSICAL RAF pointer; -1 = unknown (force a seek)
  private boolean open;

  public BinaryFileHandle(final int id, final String path, final String mode) throws IOException {
    this.id = id;
    this.path = path;
    this.file = new RandomAccessFile(path, toMode(mode));
    if ("w".equals(mode)) {
      file.setLength(0);
    }
    this.cache =
        new LinkedHashMap<>(64, 0.75f, true) {
          @Override
          protected boolean removeEldestEntry(final Map.Entry<Long, byte[]> eldest) {
            return size() > MAX_BLOCKS;
          }
        };
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
    if (count < 0 || count > MAX_READ) {
      throw new IOException("invalid read count " + count);
    }
    final var offset = position;
    if (count > 0 && count <= MAX_BLOCK) {
      final var cached = cache.get(offset);
      if (cached != null && cached.length == count) {
        position += count;
        return cached.clone(); // hand out a copy — callers must not mutate the cached block
      }
    }
    if (offset != filePos) {
      file.seek(offset); // only re-sync when the physical pointer is not already there
    }
    final var buffer = new byte[count];
    final var read = file.read(buffer, 0, count);
    if (read < 0) {
      filePos = offset;
      return new byte[0];
    }
    filePos = offset + read;
    final var result = read < count ? java.util.Arrays.copyOf(buffer, read) : buffer;
    position = offset + read;
    if (read == count && count > 0 && count <= MAX_BLOCK) {
      cache.put(offset, result.clone());
    }
    return result;
  }

  public void write(final byte[] data) throws IOException {
    if (position != filePos) {
      file.seek(position);
    }
    file.write(data);
    filePos = position + data.length;
    invalidate(position, data.length);
    position += data.length;
  }

  public void seek(final long offset) throws IOException {
    position = offset;
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
      cache.clear();
      file.close();
    }
  }

  /**
   * Drop cached blocks whose byte range overlaps the just-written {@code [start, start+length)}.
   */
  private void invalidate(final long start, final int length) {
    if (cache.isEmpty()) {
      return;
    }
    final var end = start + length;
    cache
        .entrySet()
        .removeIf(
            entry -> entry.getKey() < end && start < entry.getKey() + entry.getValue().length);
  }
}
