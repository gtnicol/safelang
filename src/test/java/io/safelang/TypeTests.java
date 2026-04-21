package io.safelang;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class TypeTests {

  @Test
  void structDeclarationAndCreation() {
    final var output =
        TestHelper.run(
            """
                program test;
                import io;
                type Point { int x; int y; }
                Point p = Point { x: 3, y: 4 };
                io:println(p.x);
                io:println(p.y);
                """);
    assertEquals("3\n4", output);
  }

  @Test
  void structFieldAssignment() {
    final var output =
        TestHelper.run(
            """
                program test;
                import io;
                type Point { int x; int y; }
                Point p = Point { x: 3, y: 4 };
                p.x = 10;
                p.y = 20;
                io:println(p.x);
                io:println(p.y);
                """);
    assertEquals("10\n20", output);
  }

  @Test
  void structMultipleFields() {
    final var output =
        TestHelper.run(
            """
                program test;
                import io;
                type Rectangle { int width; int height; }
                Rectangle r = Rectangle { width: 10, height: 5 };
                io:println(r.width * r.height);
                """);
    assertEquals("50", output);
  }

  @Test
  void structPassedToFunction() {
    final var output =
        TestHelper.run(
            """
                program test;
                import io;
                type Rectangle { int width; int height; }
                int area(Rectangle r) {
                    return r.width * r.height;
                }
                Rectangle r = Rectangle { width: 10, height: 5 };
                io:println(area(r));
                """);
    assertEquals("50", output);
  }

  @Test
  void nestedStructFieldAccess() {
    final var output =
        TestHelper.run(
            """
                program test;
                import io;
                type Inner { int value; }
                type Outer { Inner nested; }
                Inner i = Inner { value: 42 };
                Outer o = Outer { nested: i };
                io:println(o.nested.value);
                """);
    assertEquals("42", output);
  }

  @Test
  void multipleStructInstances() {
    final var output =
        TestHelper.run(
            """
                program test;
                import io;
                type Point { int x; int y; }
                Point p1 = Point { x: 1, y: 2 };
                Point p2 = Point { x: 3, y: 4 };
                io:println(p1.x + p2.x);
                io:println(p1.y + p2.y);
                """);
    assertEquals("4\n6", output);
  }

  @Test
  void structWithFloatFields() {
    final var output =
        TestHelper.run(
            """
                program test;
                import io;
                type Point { float x; float y; }
                Point p = Point { x: 1.5, y: 2.5 };
                io:println(p.x);
                io:println(p.y);
                """);
    assertEquals("1.5\n2.5", output);
  }

  @Test
  void structDistanceCalculation() {
    final var output =
        TestHelper.run(
            """
                program test;
                import io;
                type Point { int x; int y; }
                int distance(Point a, Point b) {
                    int dx = a.x - b.x;
                    int dy = a.y - b.y;
                    return dx * dx + dy * dy;
                }
                Point p1 = Point { x: 3, y: 4 };
                Point p2 = Point { x: 7, y: 1 };
                io:println(distance(p1, p2));
                """);
    assertEquals("25", output);
  }
}
