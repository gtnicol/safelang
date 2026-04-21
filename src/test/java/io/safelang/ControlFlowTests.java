package io.safelang;

import static org.junit.jupiter.api.Assertions.*;

import io.safelang.bytecode.BytecodeException;
import io.safelang.interpreter.InterpreterException;
import org.junit.jupiter.api.Test;

class ControlFlowTests {

  @Test
  void ifThenElseTrue() {
    final var output =
        TestHelper.run(
            """
                program test;
                import io;
                int x = if (5 > 3) then 1 else 2;
                io:println(x);
                """);
    assertEquals("1", output);
  }

  @Test
  void ifThenElseFalse() {
    final var output =
        TestHelper.run(
            """
                program test;
                import io;
                int x = if (1 > 10) then 100 else 200;
                io:println(x);
                """);
    assertEquals("200", output);
  }

  @Test
  void nestedIfThenElse() {
    final var output =
        TestHelper.run(
            """
                program test;
                import io;
                int x = 5;
                int result = if (x > 3) then (if (x > 10) then 100 else 50) else 0;
                io:println(result);
                """);
    assertEquals("50", output);
  }

  @Test
  void forInRange() {
    final var output =
        TestHelper.run(
            """
                program test;
                import io;
                import std;
                int total = 0;
                for i in std:range(5) {
                    total = total + i;
                }
                io:println(total);
                """);
    assertEquals("10", output);
  }

  @Test
  void forInList() {
    final var output =
        TestHelper.run(
            """
                program test;
                import io;
                list<int> items = [10, 20, 30];
                int total = 0;
                for x in items {
                    total = total + x;
                }
                io:println(total);
                """);
    assertEquals("60", output);
  }

  @Test
  void forInRangeWithPrint() {
    final var output =
        TestHelper.run(
            """
                program test;
                import io;
                import std;
                for i in std:range(4) {
                    io:println(i);
                }
                """);
    assertEquals("0\n1\n2\n3", output);
  }

  @Test
  void caseOfLiterals() {
    final var output =
        TestHelper.run(
            """
                program test;
                import io;
                int x = 2;
                string result = case x of {
                    0: "zero";
                    1: "one";
                    2: "two";
                    default: "other";
                };
                io:println(result);
                """);
    assertEquals("two", output);
  }

  @Test
  void caseOfDefault() {
    final var output =
        TestHelper.run(
            """
                program test;
                import io;
                int x = 99;
                string result = case x of {
                    0: "zero";
                    1: "one";
                    default: "other";
                };
                io:println(result);
                """);
    assertEquals("other", output);
  }

  @Test
  void caseOfReturningValue() {
    final var output =
        TestHelper.run(
            """
                program test;
                import io;
                string day(int d) {
                    return case d of {
                        0: "Sunday";
                        1: "Monday";
                        6: "Saturday";
                        default: "Weekday";
                    };
                }
                io:println(day(0));
                io:println(day(1));
                io:println(day(3));
                """);
    assertEquals("Sunday\nMonday\nWeekday", output);
  }

  @Test
  void whileNegativeBoundThrowsInterpreter() {
    assertThrows(
        InterpreterException.class,
        () ->
            TestHelper.run(
                """
                program test;
                int x = 0;
                while (x < 10) bound (0 - 1) {
                    x = x + 1;
                }
                """));
  }

  @Test
  void whileNegativeBoundThrowsBytecode() {
    assertThrows(
        BytecodeException.class,
        () ->
            TestHelper.bytecode(
                """
                program test;
                int x = 0;
                while (x < 10) bound (0 - 1) {
                    x = x + 1;
                }
                """));
  }
}
