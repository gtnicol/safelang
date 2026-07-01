package io.safelang.runtime;

import io.safelang.SAFEException;
import java.util.AbstractList;
import java.util.Collection;
import java.util.Iterator;
import java.util.NoSuchElementException;

/**
 * A persistent (immutable) vector backed by a 32-way bitmapped trie with tail optimization.
 * Provides O(log32 n) append, get, and update via structural sharing. Extends AbstractList for
 * drop-in compatibility with List<T> consumers.
 */
public final class PersistentList<T> extends AbstractList<T> {

  private static final int BITS = 5;
  private static final int WIDTH = 1 << BITS; // 32
  private static final int MASK = WIDTH - 1; // 0x1F

  @SuppressWarnings("rawtypes")
  private static final PersistentList EMPTY =
      new PersistentList<>(0, BITS, new Object[0], new Object[0]);

  private final int count;
  private final int shift;
  private final Object[] root;
  private final Object[] tail;

  private PersistentList(
      final int count, final int shift, final Object[] root, final Object[] tail) {
    this.count = count;
    this.shift = shift;
    this.root = root;
    this.tail = tail;
  }

  @SuppressWarnings("unchecked")
  public static <T> PersistentList<T> empty() {
    return (PersistentList<T>) EMPTY;
  }

  public static <T> PersistentList<T> from(final Collection<? extends T> source) {
    PersistentList<T> result = empty();
    for (final T element : source) {
      result = result.append(element);
    }
    return result;
  }

  private static Object[] newPath(final int level, final Object[] leaf) {
    if (level == 0) {
      return leaf;
    }
    final var node = new Object[1];
    node[0] = newPath(level - BITS, leaf);
    return node;
  }

  @Override
  public int size() {
    return count;
  }

  @SuppressWarnings("unchecked")
  @Override
  public T get(final int index) {
    if (index < 0 || index >= count) {
      throw new IndexOutOfBoundsException("Index: " + index + ", Size: " + count);
    }
    final var leaf = leaf(index);
    return (T) leaf[index & MASK];
  }

  public PersistentList<T> append(final T element) {
    // Single choke point for list growth on every Java backend (interpreter, bytecode VM, JVM):
    // enforce the advertised MAX_LIST_SIZE so a "terminating" program cannot exhaust the heap.
    if (count + 1L > SAFEValue.MAX_LIST_SIZE) {
      throw new SAFEException("list size exceeds the maximum of " + SAFEValue.MAX_LIST_SIZE);
    }
    // Room in tail?
    if (count - offset() < WIDTH) {
      final var extended = new Object[tail.length + 1];
      System.arraycopy(tail, 0, extended, 0, tail.length);
      extended[tail.length] = element;
      return new PersistentList<>(count + 1, shift, root, extended);
    }
    // Tail is full — push it into the trie
    final Object[] pushed = tail;
    var newshift = shift;
    Object[] newroot;
    if ((count >>> BITS) > (1 << shift)) {
      // Root overflow — add a new level
      newroot = new Object[2];
      newroot[0] = root;
      newroot[1] = newPath(shift, pushed);
      newshift += BITS;
    } else {
      newroot = push(shift, root, pushed);
    }
    return new PersistentList<>(count + 1, newshift, newroot, new Object[] {element});
  }

  public PersistentList<T> update(final int index, final T element) {
    if (index < 0 || index >= count) {
      throw new IndexOutOfBoundsException("Index: " + index + ", Size: " + count);
    }
    if (index >= offset()) {
      final var position = index & MASK;
      final var updated = new Object[tail.length];
      System.arraycopy(tail, 0, updated, 0, tail.length);
      updated[position] = element;
      return new PersistentList<>(count, shift, root, updated);
    }
    return new PersistentList<>(count, shift, replace(shift, root, index, element), tail);
  }

  // -- Internal helpers --

  @Override
  public Iterator<T> iterator() {
    return new PersistentListIterator();
  }

  private int offset() {
    if (count < WIDTH) {
      return 0;
    }
    return ((count - 1) >>> BITS) << BITS;
  }

  private Object[] leaf(final int index) {
    if (index >= offset()) {
      return tail;
    }
    var node = root;
    for (int level = shift; level > 0; level -= BITS) {
      node = (Object[]) node[(index >>> level) & MASK];
    }
    return node;
  }

  private Object[] push(final int level, final Object[] parent, final Object[] pushed) {
    final var subindex = ((count - 1) >>> level) & MASK;
    final var result = new Object[Math.max(parent.length, subindex + 1)];
    System.arraycopy(parent, 0, result, 0, parent.length);
    if (level == BITS) {
      result[subindex] = pushed;
    } else {
      final var child = subindex < parent.length ? (Object[]) parent[subindex] : null;
      result[subindex] =
          child != null ? push(level - BITS, child, pushed) : newPath(level - BITS, pushed);
    }
    return result;
  }

  private Object[] replace(final int level, final Object[] node, final int index, final T element) {
    final var result = new Object[node.length];
    System.arraycopy(node, 0, result, 0, node.length);
    if (level == 0) {
      result[index & MASK] = element;
    } else {
      final var subindex = (index >>> level) & MASK;
      result[subindex] = replace(level - BITS, (Object[]) node[subindex], index, element);
    }
    return result;
  }

  private final class PersistentListIterator implements Iterator<T> {
    private int position;
    private Object[] leaf;
    private int leafStart;

    PersistentListIterator() {
      this.position = 0;
      if (count > 0) {
        this.leaf = leaf(0);
        this.leafStart = 0;
      }
    }

    @Override
    public boolean hasNext() {
      return position < count;
    }

    @SuppressWarnings("unchecked")
    @Override
    public T next() {
      if (position >= count) {
        throw new NoSuchElementException();
      }
      if (position - leafStart >= leaf.length) {
        leaf = leaf(position);
        leafStart = position - (position & MASK);
      }
      return (T) leaf[position++ - leafStart];
    }
  }
}
