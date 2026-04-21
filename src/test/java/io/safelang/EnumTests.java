package io.safelang;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class EnumTests {

  @Test
  void simpleEnum() {
    final var output =
        TestHelper.run(
            """
                program test;
                import io;
                enum Color { Red, Green, Blue }
                io:println(Red);
                io:println(Green);
                io:println(Blue);
                """);
    assertEquals("Color.Red\nColor.Green\nColor.Blue", output);
  }

  @Test
  void enumAssignment() {
    final var output =
        TestHelper.run(
            """
                program test;
                import io;
                enum Color { Red, Green, Blue }
                Color c = Red;
                io:println(c);
                """);
    assertEquals("Color.Red", output);
  }

  @Test
  void enumCaseMatching() {
    final var output =
        TestHelper.run(
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
    assertEquals("green", output);
  }

  @Test
  void caseWithIntLiterals() {
    final var output =
        TestHelper.run(
            """
                program test;
                import io;
                int code(int c) {
                    int result = case c of {
                        0: 255;
                        1: 65280;
                        2: 16711680;
                        default: 0;
                    };
                    return result;
                }
                io:println(code(0));
                io:println(code(1));
                io:println(code(2));
                io:println(code(99));
                """);
    assertEquals("255\n65280\n16711680\n0", output);
  }

  @Test
  void caseReturningFromFunction() {
    final var output =
        TestHelper.run(
            """
                program test;
                import io;
                string describe(int x) {
                    return case x of {
                        0: "zero";
                        1: "one";
                        default: "many";
                    };
                }
                io:println(describe(0));
                io:println(describe(1));
                io:println(describe(42));
                """);
    assertEquals("zero\none\nmany", output);
  }

  @Test
  void enumDeclarationWithData() {
    final var output =
        TestHelper.run(
            """
                program test;
                import io;
                enum Shape {
                    Circle(float),
                    Rectangle(float, float),
                    Point
                }
                io:println(Point);
                """);
    assertEquals("Shape.Point", output);
  }

  @Test
  void multipleEnums() {
    final var output =
        TestHelper.run(
            """
                program test;
                import io;
                enum Color { Red, Green, Blue }
                enum Size { Small, Medium, Large }
                io:println(Red);
                io:println(Large);
                """);
    assertEquals("Color.Red\nSize.Large", output);
  }

  @Test
  void enumVariantConstructorWithData() {
    final var output =
        TestHelper.run(
            """
                program test;
                import io;
                enum Option {
                    Some(int),
                    None
                }
                Option x = Some(42);
                io:println(x);
                """);
    assertEquals("Option.Some(42)", output);
  }

  @Test
  void enumVariantPatternMatchWithData() {
    final var output =
        TestHelper.run(
            """
                program test;
                import io;
                enum Option {
                    Some(int),
                    None
                }
                Option x = Some(42);
                int result = case x of {
                    Some(v): v;
                    None: 0;
                };
                io:println(result);
                """);
    assertEquals("42", output);
  }

  @Test
  void enumNoneVariantPatternMatch() {
    final var output =
        TestHelper.run(
            """
                program test;
                import io;
                enum Option {
                    Some(int),
                    None
                }
                Option x = None;
                int result = case x of {
                    Some(v): v;
                    None: 0;
                };
                io:println(result);
                """);
    assertEquals("0", output);
  }
}
