package io.safelang.bytecode;

import java.util.*;

/**
 * Constant pool for the bytecode module. Stores integers, floats, strings, and names with
 * deduplication.
 */
public class ConstantPool {

  public static final int TAG_INT = 1;
  public static final int TAG_FLOAT = 2;
  public static final int TAG_STRING = 3;
  public static final int TAG_NAME = 4;
  private final List<Entry> entries = new ArrayList<>();
  private final Map<Entry, Integer> dedup = new HashMap<>();

  /** Add an integer constant, returns its index */
  public int addInt(final long value) {
    return add(new IntEntry(value));
  }

  /** Add a float constant, returns its index */
  public int addFloat(final double value) {
    return add(new FloatEntry(value));
  }

  /** Add a string constant, returns its index */
  public int addString(final String value) {
    return add(new StringEntry(value));
  }

  /** Add a name (identifier) constant, returns its index */
  public int addName(final String value) {
    return add(new NameEntry(value));
  }

  private int add(final Entry entry) {
    final var existing = dedup.get(entry);
    if (existing != null) return existing;
    final var index = entries.size();
    entries.add(entry);
    dedup.put(entry, index);
    return index;
  }

  /** Get entry at index */
  public Entry get(final int index) {
    return entries.get(index);
  }

  /** Get integer value at index */
  public long getInt(final int index) {
    return ((IntEntry) entries.get(index)).value();
  }

  /** Get float value at index */
  public double getFloat(final int index) {
    return ((FloatEntry) entries.get(index)).value();
  }

  /** Get string value at index */
  public String getString(final int index) {
    final var entry = entries.get(index);
    if (entry instanceof StringEntry(String value1)) return value1;
    if (entry instanceof NameEntry(String value)) return value;
    throw new BytecodeException(
        "Expected string/name at pool index "
            + index
            + " but got "
            + entry.getClass().getSimpleName());
  }

  /** Number of entries */
  public int size() {
    return entries.size();
  }

  /** Get all entries (for serialization) */
  public List<Entry> entries() {
    return Collections.unmodifiableList(entries);
  }

  /** A single constant pool entry */
  public sealed interface Entry {
    int tag();
  }

  public record IntEntry(long value) implements Entry {
    @Override
    public int tag() {
      return TAG_INT;
    }
  }

  public record FloatEntry(double value) implements Entry {
    @Override
    public int tag() {
      return TAG_FLOAT;
    }
  }

  public record StringEntry(String value) implements Entry {
    @Override
    public int tag() {
      return TAG_STRING;
    }
  }

  public record NameEntry(String value) implements Entry {
    @Override
    public int tag() {
      return TAG_NAME;
    }
  }
}
