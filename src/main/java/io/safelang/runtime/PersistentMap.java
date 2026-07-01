package io.safelang.runtime;

import java.util.AbstractMap;
import java.util.AbstractSet;
import java.util.Arrays;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

/**
 * A persistent (immutable) map with O(log₃₂ n) get/put via a hash array mapped trie, plus an
 * insertion-ordered key vector ({@link PersistentList}) so iteration order matches the historical
 * {@code LinkedHashMap} semantics. Structural sharing makes a single update O(log n) instead of the
 * O(n) full clone the old {@code LinkedHashMap}-copy-on-write incurred (which made N updates
 * O(N²)).
 *
 * <p>Extends {@link AbstractMap} (read-only) so it is a drop-in {@code Map} view: every consumer of
 * {@code SAFEValue.asMap()} reads via the {@code Map} interface and works unchanged. Mutation goes
 * through {@link #put}, which returns a new version.
 */
public final class PersistentMap extends AbstractMap<SAFEValue, SAFEValue> {

  private static final int BITS = 5;
  private static final int MASK = 31;
  private static final Object MISSING = new Object();

  private static final PersistentMap EMPTY = new PersistentMap(null, PersistentList.empty(), 0);

  private final Node root; // null when empty
  private final PersistentList<SAFEValue> order; // keys in insertion order
  private final int count;

  private PersistentMap(final Node root, final PersistentList<SAFEValue> order, final int count) {
    this.root = root;
    this.order = order;
    this.count = count;
  }

  public static PersistentMap empty() {
    return EMPTY;
  }

  public static PersistentMap from(final Map<SAFEValue, SAFEValue> source) {
    var result = EMPTY;
    if (source != null) {
      for (final var entry : source.entrySet()) {
        result = result.with(entry.getKey(), entry.getValue());
      }
    }
    return result;
  }

  @Override
  public int size() {
    return count;
  }

  @Override
  public boolean containsKey(final Object key) {
    return key instanceof SAFEValue k && root != null && root.find(0, k.hashCode(), k) != MISSING;
  }

  @Override
  public SAFEValue get(final Object key) {
    if (!(key instanceof SAFEValue k) || root == null) {
      return null;
    }
    final var value = root.find(0, k.hashCode(), k);
    return value == MISSING ? null : (SAFEValue) value;
  }

  /**
   * This map with {@code key} mapped to {@code value} (insert or replace); the receiver is
   * unchanged.
   */
  public PersistentMap with(final SAFEValue key, final SAFEValue value) {
    final var added = new boolean[1];
    final var base = root == null ? new BitmapNode(0, new Object[0]) : root;
    final var newRoot = base.put(0, key.hashCode(), key, value, added);
    final var newOrder = added[0] ? order.append(key) : order;
    return new PersistentMap(newRoot, newOrder, added[0] ? count + 1 : count);
  }

  @Override
  public Set<Entry<SAFEValue, SAFEValue>> entrySet() {
    return new AbstractSet<>() {
      @Override
      public int size() {
        return count;
      }

      @Override
      public Iterator<Entry<SAFEValue, SAFEValue>> iterator() {
        final var keys = order.iterator();
        return new Iterator<>() {
          @Override
          public boolean hasNext() {
            return keys.hasNext();
          }

          @Override
          public Entry<SAFEValue, SAFEValue> next() {
            final var key = keys.next();
            return new SimpleImmutableEntry<>(key, get(key));
          }
        };
      }
    };
  }

  // ---- Hash array mapped trie ----

  private interface Node {
    Object find(int shift, int hash, SAFEValue key); // MISSING when absent

    Node put(int shift, int hash, SAFEValue key, SAFEValue value, boolean[] added);
  }

  /**
   * Bitmap-indexed node. {@code array} holds packed {@code (key, value)} pairs; a pair whose key
   * slot is {@code null} stores a child {@link Node} in its value slot.
   */
  private static final class BitmapNode implements Node {
    private final int bitmap;
    private final Object[] array;

    BitmapNode(final int bitmap, final Object[] array) {
      this.bitmap = bitmap;
      this.array = array;
    }

    private int index(final int bit) {
      return Integer.bitCount(bitmap & (bit - 1));
    }

    @Override
    public Object find(final int shift, final int hash, final SAFEValue key) {
      final var bit = 1 << ((hash >>> shift) & MASK);
      if ((bitmap & bit) == 0) {
        return MISSING;
      }
      final var i = index(bit);
      final var k = array[2 * i];
      final var v = array[2 * i + 1];
      if (k == null) {
        return ((Node) v).find(shift + BITS, hash, key);
      }
      return key.equals(k) ? v : MISSING;
    }

    @Override
    public Node put(
        final int shift,
        final int hash,
        final SAFEValue key,
        final SAFEValue value,
        final boolean[] added) {
      final var bit = 1 << ((hash >>> shift) & MASK);
      final var i = index(bit);
      if ((bitmap & bit) != 0) {
        final var k = array[2 * i];
        final var v = array[2 * i + 1];
        if (k == null) {
          final var sub = ((Node) v).put(shift + BITS, hash, key, value, added);
          if (sub == v) {
            return this;
          }
          final var copy = array.clone();
          copy[2 * i + 1] = sub;
          return new BitmapNode(bitmap, copy);
        }
        if (key.equals(k)) {
          if (value.equals(v)) {
            return this; // identical mapping — share
          }
          final var copy = array.clone();
          copy[2 * i + 1] = value;
          return new BitmapNode(bitmap, copy);
        }
        // Two distinct keys collide at this slot — split into a child node.
        added[0] = true;
        final var sub = merge(shift + BITS, (SAFEValue) k, (SAFEValue) v, hash, key, value);
        final var copy = array.clone();
        copy[2 * i] = null;
        copy[2 * i + 1] = sub;
        return new BitmapNode(bitmap, copy);
      }
      // Empty slot — insert the pair inline.
      added[0] = true;
      final var copy = new Object[array.length + 2];
      System.arraycopy(array, 0, copy, 0, 2 * i);
      copy[2 * i] = key;
      copy[2 * i + 1] = value;
      System.arraycopy(array, 2 * i, copy, 2 * i + 2, array.length - 2 * i);
      return new BitmapNode(bitmap | bit, copy);
    }
  }

  /** Build a child node holding two entries, descending until their hash bits differ. */
  private static Node merge(
      final int shift,
      final SAFEValue k1,
      final SAFEValue v1,
      final int h2,
      final SAFEValue k2,
      final SAFEValue v2) {
    final var h1 = k1.hashCode();
    if (shift >= 32) {
      // Hash fully consumed: distinct keys with equal 32-bit hashes live in a collision node.
      return new CollisionNode(h1, new SAFEValue[] {k1, k2}, new SAFEValue[] {v1, v2});
    }
    final var p1 = (h1 >>> shift) & MASK;
    final var p2 = (h2 >>> shift) & MASK;
    if (p1 == p2) {
      final var sub = merge(shift + BITS, k1, v1, h2, k2, v2);
      return new BitmapNode(1 << p1, new Object[] {null, sub});
    }
    final var array = p1 < p2 ? new Object[] {k1, v1, k2, v2} : new Object[] {k2, v2, k1, v1};
    return new BitmapNode((1 << p1) | (1 << p2), array);
  }

  /** Leaf for distinct keys sharing a full 32-bit hash (only created at maximum trie depth). */
  private static final class CollisionNode implements Node {
    private final int hash;
    private final SAFEValue[] keys;
    private final SAFEValue[] values;

    CollisionNode(final int hash, final SAFEValue[] keys, final SAFEValue[] values) {
      this.hash = hash;
      this.keys = keys;
      this.values = values;
    }

    @Override
    public Object find(final int shift, final int h, final SAFEValue key) {
      if (h != hash) {
        return MISSING;
      }
      for (int i = 0; i < keys.length; i++) {
        if (key.equals(keys[i])) {
          return values[i];
        }
      }
      return MISSING;
    }

    @Override
    public Node put(
        final int shift,
        final int h,
        final SAFEValue key,
        final SAFEValue value,
        final boolean[] added) {
      // Reached only with h == hash (collision nodes sit at full trie depth).
      for (int i = 0; i < keys.length; i++) {
        if (key.equals(keys[i])) {
          if (value.equals(values[i])) {
            return this;
          }
          final var nv = values.clone();
          nv[i] = value;
          return new CollisionNode(hash, keys, nv);
        }
      }
      added[0] = true;
      final var nk = Arrays.copyOf(keys, keys.length + 1);
      final var nv = Arrays.copyOf(values, values.length + 1);
      nk[keys.length] = key;
      nv[values.length] = value;
      return new CollisionNode(hash, nk, nv);
    }
  }
}
