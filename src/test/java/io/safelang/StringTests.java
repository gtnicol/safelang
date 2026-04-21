package io.safelang;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class StringTests {

  @Test
  void interpolation() {
    final var output =
        TestHelper.run(
            """
                program test;
                import io;
                string name = "World";
                io:println(`Hello, ${name}!`);
                """);
    assertEquals("Hello, World!", output);
  }

  @Test
  void interpolationWithExpression() {
    final var output =
        TestHelper.run(
            """
                program test;
                import io;
                io:println(`Sum: ${10 + 20}`);
                """);
    assertEquals("Sum: 30", output);
  }

  @Test
  void multipleInterpolations() {
    final var output =
        TestHelper.run(
            """
                program test;
                import io;
                string name = "Alice";
                int age = 30;
                io:println(`${name} is ${age} years old`);
                """);
    assertEquals("Alice is 30 years old", output);
  }

  @Test
  void concatenation() {
    final var output =
        TestHelper.run(
            """
                program test;
                import io;
                string a = "hello";
                string b = " world";
                io:println(a + b);
                """);
    assertEquals("hello world", output);
  }

  @Test
  void stringIndexing() {
    final var output =
        TestHelper.run(
            """
                program test;
                import io;
                string msg = "SAFE";
                io:println(msg[0]);
                io:println(msg[3]);
                """);
    assertEquals("S\nE", output);
  }

  @Test
  void interpolationWithVariable() {
    final var output =
        TestHelper.run(
            """
                program test;
                import io;
                int count = 42;
                io:println(`The answer is ${count}`);
                """);
    assertEquals("The answer is 42", output);
  }

  @Test
  void emptyString() {
    final var output =
        TestHelper.run(
            """
                program test;
                import io;
                import std;
                string s = "";
                io:println(std:len(s));
                """);
    assertEquals("0", output);
  }

  @Test
  void strBuiltin() {
    final var output =
        TestHelper.run(
            """
                program test;
                import io;
                import std;
                io:println(std:str(42));
                io:println(std:str(true));
                io:println(std:str(3.14));
                """);
    assertEquals("42\ntrue\n3.14", output);
  }

  // ======================== String iteration (Finding #2) ========================

  @Test
  void forLoopOverString() {
    final var output =
        TestHelper.run(
            """
                program test;
                import io;
                for c in "abc" {
                    io:print(c);
                }
                """);
    assertEquals("abc", output);
  }

  @Test
  void forLoopOverEmptyString() {
    final var output =
        TestHelper.run(
            """
                program test;
                import io;
                for c in "" {
                    io:print(c);
                }
                io:print("done");
                """);
    assertEquals("done", output);
  }

  // ======================== String in operator (Finding #1) ========================

  @Test
  void inOperatorOnString() {
    final var output =
        TestHelper.run(
            """
                program test;
                import io;
                io:println("bc" in "abcd");
                io:println("xyz" in "abcd");
                """);
    assertEquals("true\nfalse", output);
  }

  // ======================== Escape sequences (Finding #7) ========================

  @Test
  void verticalTabEscape() {
    final var output =
        TestHelper.run(
            """
                program test;
                import io;
                import std;
                string s = "a\\vb";
                io:println(std:len(s));
                """);
    assertEquals("3", output);
  }
}
