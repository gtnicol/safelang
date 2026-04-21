package io.safelang;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

/**
 * Phase 4 (fourth-round audit) regression tests for object printing.
 *
 * <p>Before this phase, objects stringified via raw {@code Map.toString()} leaked Java internals:
 * {@code Point { x: 1, y: 2 }} printed as {@code {__type__=Point, __fields__={x=1, y=2}}}. The fix:
 * LinkedHashMap storage keyed by declaration order, explicit {@code Type { field: value, ... }}
 * format in {@link io.safelang.runtime.SAFEValue#asString()}.
 */
class ObjectPrintTests {

  private static final String PREAMBLE =
      """
      program test;
      import io;

      type Point {
        int x;
        int y;
      }
      """;

  @Test
  void printMirrorsConstructionInterpreter() {
    assertEquals(
        "Point { x: 1, y: 2 }",
        TestHelper.run(
            PREAMBLE
                + """
            const Point p = Point { x: 1, y: 2 };
            io:println(`${p}`);
            """));
  }

  @Test
  void printMirrorsConstructionBytecode() {
    assertEquals(
        "Point { x: 1, y: 2 }",
        TestHelper.bytecode(
            PREAMBLE
                + """
            const Point p = Point { x: 1, y: 2 };
            io:println(`${p}`);
            """));
  }

  @Test
  void fieldOrderMatchesDeclarationInterpreter() {
    // Constructing with fields in reverse order still prints in declaration order.
    assertEquals(
        "Point { x: 1, y: 2 }",
        TestHelper.run(
            PREAMBLE
                + """
            const Point p = Point { y: 2, x: 1 };
            io:println(`${p}`);
            """));
  }

  @Test
  void fieldOrderMatchesDeclarationBytecode() {
    assertEquals(
        "Point { x: 1, y: 2 }",
        TestHelper.bytecode(
            PREAMBLE
                + """
            const Point p = Point { y: 2, x: 1 };
            io:println(`${p}`);
            """));
  }

  @Test
  void noInternalMarkersLeak() {
    final var output =
        TestHelper.run(
            PREAMBLE
                + """
        const Point p = Point { x: 3, y: 4 };
        io:println(`${p}`);
        """);
    assertFalse(output.contains("__type__"), output);
    assertFalse(output.contains("__fields__"), output);
  }

  @Test
  void nestedObjectPrintRecurses() {
    assertEquals(
        "Box { origin: Point { x: 1, y: 2 }, size: 5 }",
        TestHelper.run(
            """
            program test;
            import io;

            type Point {
              int x;
              int y;
            }

            type Box {
              Point origin;
              int size;
            }

            const Box b = Box { origin: Point { x: 1, y: 2 }, size: 5 };
            io:println(`${b}`);
            """));
  }
}
