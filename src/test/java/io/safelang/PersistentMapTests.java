package io.safelang;

import static org.junit.jupiter.api.Assertions.*;

import io.safelang.runtime.PersistentMap;
import io.safelang.runtime.SAFEValue;
import java.util.ArrayList;
import org.junit.jupiter.api.Test;

/** Structural-sharing map: insertion order, value semantics, and scale (no O(N²) clone). */
class PersistentMapTests {

  private static SAFEValue k(final long n) {
    return SAFEValue.ofInt(n);
  }

  private static SAFEValue v(final String s) {
    return SAFEValue.ofString(s);
  }

  @Test
  void testGetAndUpdate() {
    final var m = PersistentMap.empty().with(k(1), v("a")).with(k(2), v("b"));
    assertEquals(v("a"), m.get(k(1)));
    assertEquals(v("b"), m.get(k(2)));
    assertNull(m.get(k(99)));
    final var m2 = m.with(k(1), v("z"));
    assertEquals(v("z"), m2.get(k(1)));
    assertEquals(v("a"), m.get(k(1)), "original version is unchanged (structural sharing)");
    assertEquals(2, m2.size());
  }

  @Test
  void testInsertionOrderPreserved() {
    var m = PersistentMap.empty();
    for (final long key : new long[] {5, 3, 9, 1, 7}) {
      m = m.with(k(key), v("x" + key));
    }
    // Updating an existing key must NOT change its position.
    m = m.with(k(9), v("updated"));
    final var keys = new ArrayList<SAFEValue>();
    m.keySet().forEach(keys::add);
    assertEquals(java.util.List.of(k(5), k(3), k(9), k(1), k(7)), keys);
  }

  @Test
  void testScaleNoQuadraticClone() {
    // 100k sequential inserts: would be O(N²) under the old whole-map clone-per-write. With
    // structural sharing this is O(N log N) and completes well under the timeout.
    final var n = 100_000;
    var m = PersistentMap.empty();
    for (int i = 0; i < n; i++) {
      m = m.with(k(i), v("v"));
    }
    assertEquals(n, m.size());
    assertEquals(v("v"), m.get(k(0)));
    assertEquals(v("v"), m.get(k(n - 1)));
    assertNull(m.get(k(n)));
  }

  @Test
  void testMapValueSemanticsViaCopy() {
    // A SAFE map copy is unaffected by a mutation of the original (value semantics).
    final var original = SAFEValue.ofMap(new java.util.LinkedHashMap<>());
    original.setEntry(k(1), v("a"));
    final var copy = original.copy();
    original.setEntry(k(1), v("mutated"));
    assertEquals(v("a"), copy.entry(k(1)), "the copy must not see the original's later mutation");
    assertEquals(v("mutated"), original.entry(k(1)));
  }

  @Test
  void testEqualityIsOrderIndependent() {
    final var a = PersistentMap.empty().with(k(1), v("x")).with(k(2), v("y"));
    final var b = PersistentMap.empty().with(k(2), v("y")).with(k(1), v("x"));
    assertEquals(a, b);
    assertEquals(a.hashCode(), b.hashCode());
  }
}
