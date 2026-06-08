package io.safelang;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

/**
 * Cross-backend parity: run one SAFE snippet through the interpreter, bytecode VM, and JVM backend
 * and assert they agree — same output, or the same trap. This is the safety net that pins shared
 * semantics (ranges, enums, collection mutation) before those implementations are centralized.
 */
class BackendParityTests {

  /** Assert all three backends produce identical output for {@code source}. */
  private static void parity(final String source) {
    final var interpreted = TestHelper.run(source);
    assertEquals(interpreted, TestHelper.bytecode(source), "bytecode VM diverged from interpreter");
    assertEquals(interpreted, TestHelper.jvm(source), "JVM backend diverged from interpreter");
  }

  /** Assert all three backends reject {@code source} by trapping rather than producing a value. */
  private static void parityTraps(final String source) {
    assertThrows(RuntimeException.class, () -> TestHelper.run(source), "interpreter did not trap");
    assertThrows(
        RuntimeException.class, () -> TestHelper.bytecode(source), "bytecode VM did not trap");
    assertThrows(RuntimeException.class, () -> TestHelper.jvm(source), "JVM backend did not trap");
  }

  // --- Ranges ---

  @Test
  void testRangeInclusive() {
    parity(
        """
        program test;
        import io;
        list<int> r = 1..5;
        io:println(r);
        """);
  }

  @Test
  void testRangeStepPositive() {
    parity(
        """
        program test;
        import io;
        list<int> r = 0..10 step 2;
        io:println(r);
        """);
  }

  @Test
  void testRangeStepNegative() {
    parity(
        """
        program test;
        import io;
        list<int> r = 10..0 step -3;
        io:println(r);
        """);
  }

  @Test
  void testRangeEmptyDirection() {
    parity(
        """
        program test;
        import io;
        import std;
        list<int> r = 10..1 step 1;
        io:println(std:len(r));
        """);
  }

  @Test
  void testRangeNearMaxStepOne() {
    // Span exceeds MAX_LIST_SIZE without wrapping: all backends must trap identically.
    parityTraps(
        """
        program test;
        import io;
        list<int> r = 0..9223372036854775807 step 1;
        io:println(r);
        """);
  }

  @Test
  void testRangeStepSpanWraparound() {
    // end - start overflows Long: the guarded size calc must trap, not wrap to a small
    // count and materialize a quintillion-element list. Reproduces the bytecode VM bug.
    parityTraps(
        """
        program test;
        import io;
        list<int> r = -9223372036854775808..9223372036854775807 step 2;
        io:println(r);
        """);
  }

  // --- Enums ---

  @Test
  void testEnumCase() {
    parity(
        """
        program test;
        import io;
        enum Color { Red, Green, Blue }
        Color c = Green;
        string result = case c of {
            Red: "red";
            Green: "green";
            Blue: "blue";
        };
        io:println(result);
        """);
  }

  @Test
  void testEnumWithData() {
    parity(
        """
        program test;
        import io;
        enum Shape { Circle(int), Square(int) }
        int area(Shape s) {
            return case s of {
                Circle(r): r * r * 3;
                Square(side): side * side;
            };
        }
        io:println(area(Circle(5)));
        io:println(area(Square(4)));
        """);
  }

  // --- decreases discipline (shared Measures helper) ---

  @Test
  void testDecreasesRecursionParity() {
    parity(
        """
        program test;
        import io;
        int countdown(int n) decreases(n) {
            return if (n <= 0) then 0 else countdown(n - 1);
        }
        io:println(countdown(6));
        """);
  }

  // --- Collection mutation ---

  @Test
  void testListIndexAssignment() {
    parity(
        """
        program test;
        import io;
        list<int> items = [1, 2, 3];
        items[1] = 99;
        io:println(items);
        """);
  }

  @Test
  void testMapMutation() {
    parity(
        """
        program test;
        import io;
        map<string, int> ages = {"alice": 30};
        ages["bob"] = 25;
        io:println(ages["bob"]);
        """);
  }
}
