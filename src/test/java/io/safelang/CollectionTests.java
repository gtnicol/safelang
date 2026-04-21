package io.safelang;

import static org.junit.jupiter.api.Assertions.*;

import io.safelang.bytecode.BytecodeException;
import io.safelang.interpreter.InterpreterException;
import org.junit.jupiter.api.Test;

class CollectionTests {

  // --- List basics ---

  @Test
  void listLiteral() {
    final var output =
        TestHelper.run(
            """
                program test;
                import io;
                list<int> nums = [10, 20, 30];
                io:println(nums[0]);
                io:println(nums[1]);
                io:println(nums[2]);
                """);
    assertEquals("10\n20\n30", output);
  }

  @Test
  void listLen() {
    final var output =
        TestHelper.run(
            """
                program test;
                import io;
                import std;
                list<int> nums = [1, 2, 3];
                io:println(std:len(nums));
                """);
    assertEquals("3", output);
  }

  @Test
  void listAppend() {
    final var output =
        TestHelper.run(
            """
                program test;
                import io;
                import std;
                import collections;
                list<int> nums = [1, 2, 3];
                list<int> updated = collections:append(nums, 4);
                io:println(std:len(updated));
                io:println(updated[3]);
                """);
    assertEquals("4\n4", output);
  }

  @Test
  void listIndexAssignment() {
    final var output =
        TestHelper.run(
            """
                program test;
                import io;
                list<int> items = [10, 20, 30];
                items[1] = 99;
                io:println(items[1]);
                """);
    assertEquals("99", output);
  }

  @Test
  void emptyList() {
    final var output =
        TestHelper.run(
            """
                program test;
                import io;
                import std;
                list<int> empty = [];
                io:println(std:len(empty));
                """);
    assertEquals("0", output);
  }

  @Test
  void forOverList() {
    final var output =
        TestHelper.run(
            """
                program test;
                import io;
                list<int> nums = [10, 20, 30];
                int total = 0;
                for x in nums {
                    total = total + x;
                }
                io:println(total);
                """);
    assertEquals("60", output);
  }

  // --- Map basics ---

  @Test
  void mapLiteral() {
    final var output =
        TestHelper.run(
            """
                program test;
                import io;
                map<string, int> ages = {"alice": 30, "bob": 25};
                io:println(ages["alice"]);
                io:println(ages["bob"]);
                """);
    assertEquals("30\n25", output);
  }

  @Test
  void mapLen() {
    final var output =
        TestHelper.run(
            """
                program test;
                import io;
                import std;
                map<string, int> m = {"a": 1, "b": 2, "c": 3};
                io:println(std:len(m));
                """);
    assertEquals("3", output);
  }

  @Test
  void mapContains() {
    final var output =
        TestHelper.run(
            """
                program test;
                import io;
                import collections;
                map<string, int> m = {"a": 1, "b": 2};
                io:println(collections:contains(m, "a"));
                io:println(collections:contains(m, "z"));
                """);
    assertEquals("true\nfalse", output);
  }

  @Test
  void mapModification() {
    final var output =
        TestHelper.run(
            """
                program test;
                import io;
                import std;
                map<string, int> ages = {"alice": 30};
                ages["bob"] = 25;
                io:println(ages["bob"]);
                io:println(std:len(ages));
                """);
    assertEquals("25\n2", output);
  }

  @Test
  void emptyMap() {
    final var output =
        TestHelper.run(
            """
                program test;
                import io;
                import std;
                map<string, int> m = {};
                io:println(std:len(m));
                """);
    assertEquals("0", output);
  }

  // --- Nested lists ---

  @Test
  void nestedListAccess() {
    final var output =
        TestHelper.run(
            """
                program test;
                import io;
                list<list<int>> matrix = [[1, 2], [3, 4]];
                io:println(matrix[0][1]);
                io:println(matrix[1][0]);
                """);
    assertEquals("2\n3", output);
  }

  @Test
  void nestedListAssignment() {
    final var output =
        TestHelper.run(
            """
                program test;
                import io;
                list<list<int>> matrix = [[1, 2], [3, 4]];
                matrix[0][1] = 99;
                io:println(matrix[0][1]);
                """);
    assertEquals("99", output);
  }

  // --- Nested maps ---

  @Test
  void nestedMapAccess() {
    final var output =
        TestHelper.run(
            """
                program test;
                import io;
                map<string, map<string, int>> outer = {"a": {"x": 1, "y": 2}, "b": {"x": 3}};
                io:println(outer["a"]["x"]);
                io:println(outer["a"]["y"]);
                io:println(outer["b"]["x"]);
                """);
    assertEquals("1\n2\n3", output);
  }

  @Test
  void nestedMapAssignment() {
    final var output =
        TestHelper.run(
            """
                program test;
                import io;
                map<string, map<string, int>> m = {"a": {"x": 1}};
                m["a"]["x"] = 42;
                io:println(m["a"]["x"]);
                """);
    assertEquals("42", output);
  }

  // --- Map of lists ---

  @Test
  void mapOfListsAccess() {
    final var output =
        TestHelper.run(
            """
                program test;
                import io;
                map<string, list<int>> data = {"nums": [1, 2, 3]};
                io:println(data["nums"][0]);
                io:println(data["nums"][2]);
                """);
    assertEquals("1\n3", output);
  }

  // --- List of maps ---

  @Test
  void listOfMapsAccess() {
    final var output =
        TestHelper.run(
            """
                program test;
                import io;
                list<map<string, int>> items = [{"a": 1}, {"b": 2}];
                io:println(items[0]["a"]);
                io:println(items[1]["b"]);
                """);
    assertEquals("1\n2", output);
  }

  // --- Built-in collection functions ---

  @Test
  void mapKeys() {
    final var output =
        TestHelper.run(
            """
                program test;
                import io;
                import collections;
                map<string, int> m = {"a": 1, "b": 2};
                io:println(collections:keys(m));
                """);
    assertTrue(output.equals("[a, b]") || output.equals("[b, a]"));
  }

  @Test
  void mapValues() {
    final var output =
        TestHelper.run(
            """
                program test;
                import io;
                import collections;
                map<string, int> m = {"a": 1, "b": 2};
                io:println(collections:values(m));
                """);
    assertTrue(output.equals("[1, 2]") || output.equals("[2, 1]"));
  }

  @Test
  void range() {
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
  void stringLen() {
    final var output =
        TestHelper.run(
            """
                program test;
                import io;
                import std;
                io:println(std:len("hello"));
                """);
    assertEquals("5", output);
  }

  @Test
  void stringIndex() {
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

  // ======================== in operator (Finding #1) ========================

  @Test
  void inOperatorOnList() {
    final var output =
        TestHelper.run(
            """
                program test;
                import io;
                io:println(2 in [1, 2, 3]);
                io:println(5 in [1, 2, 3]);
                """);
    assertEquals("true\nfalse", output);
  }

  @Test
  void inOperatorOnMap() {
    final var output =
        TestHelper.run(
            """
                program test;
                import io;
                io:println("a" in {"a": 1, "b": 2});
                io:println("c" in {"a": 1, "b": 2});
                """);
    assertEquals("true\nfalse", output);
  }

  @Test
  void inOperatorBooleanResult() {
    final var output =
        TestHelper.run(
            """
                program test;
                import io;
                boolean found = 1 in [1, 2, 3];
                io:println(found);
                """);
    assertEquals("true", output);
  }

  // ======================== Deterministic map ordering (Finding #12) ========================

  @Test
  void mapInsertionOrder() {
    final var output =
        TestHelper.run(
            """
                program test;
                import io;
                import collections;
                map<string, int> m = {"x": 1, "y": 2, "z": 3};
                io:println(collections:keys(m));
                """);
    assertEquals("[x, y, z]", output);
  }

  // --- Range overflow safety ---

  @Test
  void rangeOverflowSafeInterpreter() {
    assertThrows(
        InterpreterException.class,
        () ->
            TestHelper.run(
                """
                program test;
                import io;
                list<int> r = 0..9223372036854775807 step 1;
                io:println(r);
                """));
  }

  @Test
  void rangeOverflowSafeBytecode() {
    assertThrows(
        BytecodeException.class,
        () ->
            TestHelper.bytecode(
                """
                program test;
                import io;
                list<int> r = 0..9223372036854775807 step 1;
                io:println(r);
                """));
  }

  @Test
  void rangeEmptyWhenDirectionMismatches() {
    final var output =
        TestHelper.run(
            """
                program test;
                import io;
                import std;
                list<int> r = 10..1 step 1;
                io:println(std:len(r));
                """);
    assertEquals("0", output);
  }
}
