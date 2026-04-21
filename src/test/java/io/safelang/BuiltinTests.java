package io.safelang;

import static org.junit.jupiter.api.Assertions.*;

import io.safelang.analyzer.SemanticAnalyzer;
import io.safelang.analyzer.SemanticException;
import io.safelang.interpreter.Interpreter;
import io.safelang.parser.SAFEParser;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;

/** Tests for all 38 new builtins through the tree-walking interpreter. */
class BuiltinTests {

  private static final String[] STDLIB_MODULES = {
    "io",
    "std",
    "math",
    "strings",
    "file",
    "collections",
    "option",
    "result",
    "stack",
    "queue",
    "sorting",
    "tree",
    "functional"
  };

  private String run(final String source) {
    final var program = SAFEParser.parse(source);
    final var loader = new ModuleLoader(Path.of("stdlib/io.safe"));
    final var registry = new ModuleRegistry();
    for (final var name : STDLIB_MODULES) {
      try {
        final var module = loader.load(name);
        registry.register(name, module);
      } catch (Exception ignored) {
      }
    }
    for (final var imported : program.imports()) {
      if (!registry.has(imported.module())) {
        try {
          final var module = loader.load(imported.module());
          registry.register(imported.module(), module);
        } catch (Exception ignored) {
        }
      }
    }
    final var analyzer = new SemanticAnalyzer(registry);
    analyzer.analyze(program);
    final var interpreter = new Interpreter();
    interpreter.setRegistry(registry);

    final var old = System.out;
    final var buffer = new ByteArrayOutputStream();
    System.setOut(new PrintStream(buffer));
    try {
      interpreter.interpret(program);
    } finally {
      System.setOut(old);
    }
    return buffer.toString().trim();
  }

  // ========== Math Builtins ==========

  @Test
  void testSqrt() {
    assertEquals("4.0", run("program t; import io; import math; io:println(math:sqrt(16.0));"));
  }

  @Test
  void testPow() {
    assertEquals("8.0", run("program t; import io; import math; io:println(math:pow(2.0, 3.0));"));
  }

  @Test
  void testAbsInt() {
    assertEquals("5", run("program t; import io; import math; io:println(math:abs(0 - 5));"));
  }

  @Test
  void testAbsFloat() {
    assertEquals(
        "3.14", run("program t; import io; import math; io:println(math:abs(0.0 - 3.14));"));
  }

  @Test
  void testMin() {
    assertEquals("3", run("program t; import io; import math; io:println(math:min(3, 7));"));
  }

  @Test
  void testMax() {
    assertEquals("7", run("program t; import io; import math; io:println(math:max(3, 7));"));
  }

  @Test
  void testFloor() {
    assertEquals("3", run("program t; import io; import math; io:println(math:floor(3.7));"));
  }

  @Test
  void testCeil() {
    assertEquals("4", run("program t; import io; import math; io:println(math:ceil(3.2));"));
  }

  @Test
  void testRound() {
    assertEquals("4", run("program t; import io; import math; io:println(math:round(3.5));"));
  }

  @Test
  void testLog() {
    assertEquals("0.0", run("program t; import io; import math; io:println(math:log(1.0));"));
  }

  @Test
  void testSin() {
    assertEquals("0.0", run("program t; import io; import math; io:println(math:sin(0.0));"));
  }

  @Test
  void testCos() {
    assertEquals("1.0", run("program t; import io; import math; io:println(math:cos(0.0));"));
  }

  @Test
  void testAbsFloatViaUnion() {
    assertEquals(
        "3.14", run("program t; import io; import math; io:println(math:abs(0.0 - 3.14));"));
  }

  @Test
  void testMinFloat() {
    assertEquals("1.5", run("program t; import io; import math; io:println(math:min(1.5, 2.5));"));
  }

  @Test
  void testMaxFloat() {
    assertEquals("2.5", run("program t; import io; import math; io:println(math:max(1.5, 2.5));"));
  }

  @Test
  void testClampFloat() {
    assertEquals(
        "3.0", run("program t; import io; import math; io:println(math:clamp(5.0, 1.0, 3.0));"));
  }

  // ========== Union Narrowing ==========

  @Test
  void testMaxIntNarrowing() {
    assertEquals(
        "7", run("program t; import io; import math; int x = math:max(3, 7); io:println(x);"));
  }

  @Test
  void testMaxFloatNarrowing() {
    assertEquals(
        "7.0",
        run("program t; import io; import math; float x = math:max(3.0, 7.0); io:println(x);"));
  }

  @Test
  void testAbsIntNarrowing() {
    assertEquals(
        "5", run("program t; import io; import math; int x = math:abs(0 - 5); io:println(x);"));
  }

  @Test
  void testMinIntNarrowing() {
    assertEquals(
        "3", run("program t; import io; import math; int x = math:min(3, 7); io:println(x);"));
  }

  @Test
  void testMinIntNarrowingAssignment() {
    assertThrows(
        SemanticException.class,
        () -> run("program t; import io; import math; int x = math:min(3.0, 7); io:println(x);"));
  }

  // ========== String Builtins ==========

  @Test
  void testSubstring() {
    assertEquals(
        "ell",
        run(
            "program t; import io; import strings; io:println(strings:substring(\"hello\", 1, 4));"));
  }

  @Test
  void testIndexOf() {
    assertEquals(
        "2",
        run(
            "program t; import io; import strings; io:println(strings:indexOf(\"hello\", \"llo\"));"));
  }

  @Test
  void testCharAt() {
    assertEquals(
        "e",
        run("program t; import io; import strings; io:println(strings:charAt(\"hello\", 1));"));
  }

  @Test
  void testSplit() {
    assertEquals(
        "3",
        run(
            "program t; import io; import std; import strings; list<string> parts = strings:split(\"a,b,c\", \",\"); io:println(std:len(parts));"));
  }

  @Test
  void testTrim() {
    assertEquals(
        "hello",
        run("program t; import io; import strings; io:println(strings:trim(\"  hello  \"));"));
  }

  @Test
  void testUpper() {
    assertEquals(
        "HELLO",
        run("program t; import io; import strings; io:println(strings:upper(\"hello\"));"));
  }

  @Test
  void testLower() {
    assertEquals(
        "hello",
        run("program t; import io; import strings; io:println(strings:lower(\"HELLO\"));"));
  }

  @Test
  void testReplace() {
    assertEquals(
        "hxllo",
        run(
            "program t; import io; import strings; io:println(strings:replace(\"hello\", \"e\", \"x\"));"));
  }

  @Test
  void testStarts() {
    assertEquals(
        "true",
        run(
            "program t; import io; import strings; io:println(strings:starts(\"hello\", \"hel\"));"));
  }

  @Test
  void testEnds() {
    assertEquals(
        "true",
        run("program t; import io; import strings; io:println(strings:ends(\"hello\", \"llo\"));"));
  }

  @Test
  void testJoin() {
    assertEquals(
        "a-b-c",
        run(
            "program t; import io; import strings; list<string> items = [\"a\", \"b\", \"c\"]; io:println(strings:join(items, \"-\"));"));
  }

  @Test
  void testChars() {
    assertEquals(
        "3",
        run(
            "program t; import io; import std; import strings; list<string> c = strings:chars(\"abc\"); io:println(std:len(c));"));
  }

  // ========== File I/O Builtins ==========

  @Test
  void testSpitAndSlurp() throws IOException {
    final var temp = Files.createTempFile("safe_test_", ".txt");
    final var path = temp.toAbsolutePath().toString();
    try {
      run("program t; import file; file:write(\"" + path + "\", \"hello world\");");
      assertEquals(
          "WriteResult.Done",
          run(
              "program t; import io; import file; io:println(file:write(\""
                  + path
                  + "\", \"hello world\"));"));
      assertEquals(
          "ReadResult.Ok(hello world)",
          run("program t; import io; import file; io:println(file:read(\"" + path + "\"));"));
    } finally {
      Files.deleteIfExists(temp);
    }
  }

  @Test
  void testExists() throws IOException {
    final var temp = Files.createTempFile("safe_test_", ".txt");
    final var path = temp.toAbsolutePath().toString();
    try {
      assertEquals(
          "true",
          run("program t; import io; import file; io:println(file:exists(\"" + path + "\"));"));
      assertEquals(
          "false",
          run(
              "program t; import io; import file; io:println(file:exists(\"/nonexistent/file.txt\"));"));
    } finally {
      Files.deleteIfExists(temp);
    }
  }

  @Test
  void testDeleteFile() throws IOException {
    final var temp = Files.createTempFile("safe_test_", ".txt");
    final var path = temp.toAbsolutePath().toString();
    assertEquals(
        "true",
        run("program t; import io; import file; io:println(file:delete(\"" + path + "\"));"));
    assertFalse(Files.exists(temp));
  }

  @Test
  void testFileOpenReadClose() throws IOException {
    final var temp = Files.createTempFile("safe_test_", ".txt");
    final var path = temp.toAbsolutePath().toString();
    Files.writeString(temp, "hello from handle");
    try {
      assertEquals(
          "hello from handle",
          run(
              "program t; import io; import file; "
                  + "string show(ReadResult r) { return case r of { Ok(s): s; Err(m): m; }; } "
                  + "string process(OpenResult o) { return case o of { Ok(f): show(file:load(f)); Err(m): m; }; } "
                  + "io:println(process(file:open(\""
                  + path
                  + "\", \"r\")));"));
    } finally {
      Files.deleteIfExists(temp);
    }
  }

  @Test
  void testFileOpenWriteClose() throws IOException {
    final var temp = Files.createTempFile("safe_test_", ".txt");
    final var path = temp.toAbsolutePath().toString();
    try {
      run(
          "program t; import file; "
              + "void go(File f) { file:save(f, \"hello world\"); file:close(f); return; } "
              + "case file:open(\""
              + path
              + "\", \"w\") of { "
              + "  Ok(f): go(f); "
              + "  Err(m): 0; "
              + "};");
      assertEquals("hello world", Files.readString(temp));
    } finally {
      Files.deleteIfExists(temp);
    }
  }

  // ========== System Builtins ==========

  @Test
  void testTime() {
    final var output =
        run("program t; import io; import std; int t = std:time(); io:println(t > 0);");
    assertEquals("true", output);
  }

  @Test
  void testTypeOf() {
    assertEquals("int", run("program t; import io; import std; io:println(std:typeof(42));"));
    assertEquals(
        "string", run("program t; import io; import std; io:println(std:typeof(\"hi\"));"));
    assertEquals("boolean", run("program t; import io; import std; io:println(std:typeof(true));"));
    assertEquals("float", run("program t; import io; import std; io:println(std:typeof(3.14));"));
  }

  // ========== List Builtins ==========

  @Test
  void testRemove() {
    assertEquals(
        "1\n3",
        run(
            "program t; import io; import collections; list<int> items = [1, 2, 3]; list<int> r = collections:remove(items, 1); for x in r { io:println(x); }"));
  }

  @Test
  void testSlice() {
    assertEquals(
        "2\n3",
        run(
            "program t; import io; import collections; list<int> items = [1, 2, 3, 4]; list<int> s = collections:slice(items, 1, 3); for x in s { io:println(x); }"));
  }

  @Test
  void testReverse() {
    assertEquals(
        "3\n2\n1",
        run(
            "program t; import io; import collections; list<int> items = [1, 2, 3]; list<int> r = collections:reverse(items); for x in r { io:println(x); }"));
  }

  @Test
  void testSort() {
    assertEquals(
        "1\n2\n3",
        run(
            "program t; import io; import collections; list<int> items = [3, 1, 2]; list<int> s = collections:sort(items); for x in s { io:println(x); }"));
  }

  // ========== Sorting ==========

  @Test
  void testQuicksort() {
    assertEquals(
        "1\n2\n3\n4\n5",
        run(
            "program t; import io; import sorting; "
                + "list<int> s = sorting:quicksort([5, 3, 1, 4, 2], fn(a, b) -> a < b); "
                + "for x in s { io:println(x); }"));
  }

  @Test
  void testMergesort() {
    assertEquals(
        "1\n2\n3\n4\n5",
        run(
            "program t; import io; import sorting; "
                + "list<int> s = sorting:mergesort([5, 3, 1, 4, 2], fn(a, b) -> a < b); "
                + "for x in s { io:println(x); }"));
  }

  @Test
  void testTimsort() {
    assertEquals(
        "1\n2\n3\n4\n5",
        run(
            "program t; import io; import sorting; "
                + "list<int> s = sorting:timsort([5, 3, 1, 4, 2], fn(a, b) -> a < b); "
                + "for x in s { io:println(x); }"));
  }

  @Test
  void testTimsortFloats() {
    assertEquals(
        "1.1\n2.2\n3.3",
        run(
            "program t; import io; import sorting; "
                + "list<float> s = sorting:timsort([3.3, 1.1, 2.2], fn(a, b) -> a < b); "
                + "for x in s { io:println(x); }"));
  }

  @Test
  void testTimsortEmpty() {
    assertEquals(
        "true",
        run(
            "program t; import io; import sorting; "
                + "list<int> s = sorting:timsort([], fn(a, b) -> a < b); "
                + "io:println(sorting:sorted(s, fn(a, b) -> a < b));"));
  }

  @Test
  void testTimsortSingle() {
    assertEquals(
        "42",
        run(
            "program t; import io; import sorting; "
                + "list<int> s = sorting:timsort([42], fn(a, b) -> a < b); "
                + "for x in s { io:println(x); }"));
  }

  @Test
  void testTimsortAlreadySorted() {
    assertEquals(
        "1\n2\n3\n4\n5",
        run(
            "program t; import io; import sorting; "
                + "list<int> s = sorting:timsort([1, 2, 3, 4, 5], fn(a, b) -> a < b); "
                + "for x in s { io:println(x); }"));
  }

  @Test
  void testQuicksortFloats() {
    assertEquals(
        "1.1\n2.2\n3.3",
        run(
            "program t; import io; import sorting; "
                + "list<float> s = sorting:quicksort([3.3, 1.1, 2.2], fn(a, b) -> a < b); "
                + "for x in s { io:println(x); }"));
  }

  @Test
  void testMergesortFloats() {
    assertEquals(
        "1.1\n2.2\n3.3",
        run(
            "program t; import io; import sorting; "
                + "list<float> s = sorting:mergesort([3.3, 1.1, 2.2], fn(a, b) -> a < b); "
                + "for x in s { io:println(x); }"));
  }

  // ========== Sorting with User-Defined Types ==========

  @Test
  void testSelectionUserType() {
    assertEquals(
        "apple\ndate\nbanana\nelderberry\ncherry",
        run(
            "program t; import io; import sorting; "
                + "type Item { string name; int value; } "
                + "list<Item> items = [Item { name: \"banana\", value: 3 }, Item { name: \"apple\", value: 1 }, Item { name: \"cherry\", value: 5 }, Item { name: \"date\", value: 2 }, Item { name: \"elderberry\", value: 4 }]; "
                + "list<Item> s = sorting:selection(items, fn(a, b) -> a.value < b.value); "
                + "for x in s { io:println(x.name); }"));
  }

  @Test
  void testInsertionUserType() {
    assertEquals(
        "apple\ndate\nbanana\nelderberry\ncherry",
        run(
            "program t; import io; import sorting; "
                + "type Item { string name; int value; } "
                + "list<Item> items = [Item { name: \"banana\", value: 3 }, Item { name: \"apple\", value: 1 }, Item { name: \"cherry\", value: 5 }, Item { name: \"date\", value: 2 }, Item { name: \"elderberry\", value: 4 }]; "
                + "list<Item> s = sorting:insertion(items, fn(a, b) -> a.value < b.value); "
                + "for x in s { io:println(x.name); }"));
  }

  @Test
  void testQuicksortUserType() {
    assertEquals(
        "apple\ndate\nbanana\nelderberry\ncherry",
        run(
            "program t; import io; import sorting; "
                + "type Item { string name; int value; } "
                + "list<Item> items = [Item { name: \"banana\", value: 3 }, Item { name: \"apple\", value: 1 }, Item { name: \"cherry\", value: 5 }, Item { name: \"date\", value: 2 }, Item { name: \"elderberry\", value: 4 }]; "
                + "list<Item> s = sorting:quicksort(items, fn(a, b) -> a.value < b.value); "
                + "for x in s { io:println(x.name); }"));
  }

  @Test
  void testMergesortUserType() {
    assertEquals(
        "apple\ndate\nbanana\nelderberry\ncherry",
        run(
            "program t; import io; import sorting; "
                + "type Item { string name; int value; } "
                + "list<Item> items = [Item { name: \"banana\", value: 3 }, Item { name: \"apple\", value: 1 }, Item { name: \"cherry\", value: 5 }, Item { name: \"date\", value: 2 }, Item { name: \"elderberry\", value: 4 }]; "
                + "list<Item> s = sorting:mergesort(items, fn(a, b) -> a.value < b.value); "
                + "for x in s { io:println(x.name); }"));
  }

  @Test
  void testTimsortUserType() {
    assertEquals(
        "apple\ndate\nbanana\nelderberry\ncherry",
        run(
            "program t; import io; import sorting; "
                + "type Item { string name; int value; } "
                + "list<Item> items = [Item { name: \"banana\", value: 3 }, Item { name: \"apple\", value: 1 }, Item { name: \"cherry\", value: 5 }, Item { name: \"date\", value: 2 }, Item { name: \"elderberry\", value: 4 }]; "
                + "list<Item> s = sorting:timsort(items, fn(a, b) -> a.value < b.value); "
                + "for x in s { io:println(x.name); }"));
  }

  @Test
  void testSortedUserType() {
    assertEquals(
        "true",
        run(
            "program t; import io; import sorting; "
                + "type Item { string name; int value; } "
                + "list<Item> items = [Item { name: \"apple\", value: 1 }, Item { name: \"date\", value: 2 }, Item { name: \"banana\", value: 3 }]; "
                + "io:println(sorting:sorted(items, fn(a, b) -> a.value < b.value));"));
  }

  @Test
  void testDescendingSort() {
    assertEquals(
        "5\n4\n3\n2\n1",
        run(
            "program t; import io; import sorting; "
                + "list<int> s = sorting:quicksort([3, 1, 5, 2, 4], fn(a, b) -> a > b); "
                + "for x in s { io:println(x); }"));
  }

  // ========== File Handle Builtins ==========

  @Test
  void testFileOpenAppendClose() throws IOException {
    final var temp = Files.createTempFile("safe_test_", ".txt");
    final var path = temp.toAbsolutePath().toString();
    try {
      Files.writeString(temp, "hello");
      run(
          "program t; import file; "
              + "void go(File f) { file:save(f, \" world\"); file:close(f); return; } "
              + "case file:open(\""
              + path
              + "\", \"a\") of { "
              + "  Ok(f): go(f); "
              + "  Err(m): 0; "
              + "};");
      assertEquals("hello world", Files.readString(temp));
    } finally {
      Files.deleteIfExists(temp);
    }
  }

  @Test
  void testFileRead() throws IOException {
    final var temp = Files.createTempFile("safe_test_", ".txt");
    final var path = temp.toAbsolutePath().toString();
    Files.writeString(temp, "test content");
    try {
      assertEquals(
          "test content",
          run(
              "program t; import io; import file; "
                  + "ReadResult r = file:read(\""
                  + path
                  + "\"); "
                  + "case r of { Ok(s): io:println(s); Err(m): io:println(m); };"));
    } finally {
      Files.deleteIfExists(temp);
    }
  }

  @Test
  void testFileWrite() throws IOException {
    final var temp = Files.createTempFile("safe_test_", ".txt");
    final var path = temp.toAbsolutePath().toString();
    try {
      run("program t; import file; file:write(\"" + path + "\", \"write content\");");
      assertEquals("write content", Files.readString(temp));
    } finally {
      Files.deleteIfExists(temp);
    }
  }

  @Test
  void testFileOpenError() {
    assertEquals(
        "error",
        run(
            "program t; import io; import file; "
                + "OpenResult r = file:open(\"/nonexistent/path/file.txt\", \"r\"); "
                + "case r of { Ok(f): io:println(\"ok\"); Err(m): io:println(\"error\"); };"));
  }

  @Test
  void testFileReadOnWriteHandle() throws IOException {
    final var temp = Files.createTempFile("safe_test_", ".txt");
    final var path = temp.toAbsolutePath().toString();
    try {
      assertEquals(
          "Cannot read from a write-mode handle",
          run(
              "program t; import io; import file; "
                  + "string show(ReadResult r) { return case r of { Ok(s): s; Err(m): m; }; } "
                  + "void go(File f) { io:println(show(file:load(f))); file:close(f); return; } "
                  + "case file:open(\""
                  + path
                  + "\", \"w\") of { "
                  + "  Ok(f): go(f); "
                  + "  Err(m): io:println(m); "
                  + "};"));
    } finally {
      Files.deleteIfExists(temp);
    }
  }

  @Test
  void testFileWriteOnReadHandle() throws IOException {
    final var temp = Files.createTempFile("safe_test_", ".txt");
    final var path = temp.toAbsolutePath().toString();
    Files.writeString(temp, "data");
    try {
      assertEquals(
          "Cannot write to a read-mode handle",
          run(
              "program t; import io; import file; "
                  + "string show(WriteResult r) { return case r of { Done: \"done\"; Err(m): m; }; } "
                  + "void go(File f) { io:println(show(file:save(f, \"bad\"))); file:close(f); return; } "
                  + "case file:open(\""
                  + path
                  + "\", \"r\") of { "
                  + "  Ok(f): go(f); "
                  + "  Err(m): io:println(m); "
                  + "};"));
    } finally {
      Files.deleteIfExists(temp);
    }
  }

  @Test
  void testFileValid() throws IOException {
    final var temp = Files.createTempFile("safe_test_", ".txt");
    final var path = temp.toAbsolutePath().toString();
    Files.writeString(temp, "data");
    try {
      assertEquals(
          "true\nfalse",
          run(
              "program t; import io; import file; "
                  + "void go(File f) { io:println(file:valid(f)); file:close(f); io:println(file:valid(f)); return; } "
                  + "case file:open(\""
                  + path
                  + "\", \"r\") of { "
                  + "  Ok(f): go(f); "
                  + "  Err(m): io:println(m); "
                  + "};"));
    } finally {
      Files.deleteIfExists(temp);
    }
  }

  @Test
  void testFileReadError() {
    assertEquals(
        "error",
        run(
            "program t; import io; import file; "
                + "ReadResult r = file:read(\"/nonexistent/path/file.txt\"); "
                + "case r of { Ok(s): io:println(\"ok\"); Err(m): io:println(\"error\"); };"));
  }

  // ========== Polymorphic sum ==========

  @Test
  void testSumIntList() {
    assertEquals(
        "6", run("program t; import io; import math; int x = math:sum([1, 2, 3]); io:println(x);"));
  }

  @Test
  void testSumFloatList() {
    assertEquals(
        "6.0",
        run(
            "program t; import io; import math; float x = math:sum([1.0, 2.0, 3.0]); io:println(x);"));
  }

  @Test
  void testSumIntNarrowing() {
    assertEquals(
        "10",
        run("program t; import io; import math; int x = math:sum([1, 2, 3, 4]); io:println(x);"));
  }

  @Test
  void testSumFloatNarrowing() {
    assertEquals(
        "6.6",
        run(
            "program t; import io; import math; float x = math:sum([1.1, 2.2, 3.3]); io:println(x);"));
  }

  @Test
  void testSumFloatNarrowingRejectsInt() {
    assertThrows(
        SemanticException.class,
        () ->
            run("program t; import io; import math; int x = math:sum([1.0, 2.0]); io:println(x);"));
  }

  // ========== Option Builtins ==========

  @Test
  void testUnwrapSomeInt() {
    assertEquals(
        "42", run("program t; import io; import option; io:println(option:unwrap(Some(42), 0));"));
  }

  @Test
  void testUnwrapNone() {
    assertEquals(
        "0", run("program t; import io; import option; io:println(option:unwrap(None, 0));"));
  }

  @Test
  void testUnwrapSomeString() {
    assertEquals(
        "hi",
        run("program t; import io; import option; io:println(option:unwrap(Some(\"hi\"), \"\"));"));
  }

  @Test
  void testPresentSome() {
    assertEquals(
        "true", run("program t; import io; import option; io:println(option:present(Some(1)));"));
  }

  @Test
  void testPresentNone() {
    assertEquals(
        "false", run("program t; import io; import option; io:println(option:present(None));"));
  }

  // ========== Generic Stack ==========

  @Test
  void testStackPushString() {
    assertEquals(
        "a",
        run(
            "program t; import io; import stack; "
                + "Stack s = stack:push(stack:create(), \"a\"); "
                + "io:println(stack:peek(s));"));
  }

  // ========== Generic Queue ==========

  @Test
  void testQueueEnqueueString() {
    assertEquals(
        "a",
        run(
            "program t; import io; import queue; "
                + "Queue q = queue:enqueue(queue:create(), \"a\"); "
                + "io:println(queue:front(q));"));
  }

  // ========== Tree ==========

  @Test
  void testTreeInsertAndCount() {
    assertEquals(
        "3",
        run(
            "program t; import io; import tree; "
                + "Tree t = tree:insert(Empty, 5); "
                + "t = tree:insert(t, 3); "
                + "t = tree:insert(t, 7); "
                + "io:println(tree:count(t));"));
  }

  @Test
  void testTreeFindTrue() {
    assertEquals(
        "true",
        run(
            "program t; import io; import tree; "
                + "Tree t = tree:insert(Empty, 5); "
                + "t = tree:insert(t, 3); "
                + "t = tree:insert(t, 7); "
                + "io:println(tree:find(t, 3));"));
  }

  @Test
  void testTreeFindFalse() {
    assertEquals(
        "false",
        run(
            "program t; import io; import tree; "
                + "Tree t = tree:insert(Empty, 5); "
                + "t = tree:insert(t, 3); "
                + "t = tree:insert(t, 7); "
                + "io:println(tree:find(t, 4));"));
  }

  @Test
  void testTreeSmallest() {
    assertEquals(
        "3",
        run(
            "program t; import io; import tree; "
                + "Tree t = tree:insert(Empty, 5); "
                + "t = tree:insert(t, 3); "
                + "t = tree:insert(t, 7); "
                + "io:println(tree:smallest(t));"));
  }

  @Test
  void testTreeLargest() {
    assertEquals(
        "7",
        run(
            "program t; import io; import tree; "
                + "Tree t = tree:insert(Empty, 5); "
                + "t = tree:insert(t, 3); "
                + "t = tree:insert(t, 7); "
                + "io:println(tree:largest(t));"));
  }

  @Test
  void testTreeBlank() {
    assertEquals(
        "true", run("program t; import io; import tree; " + "io:println(tree:blank(Empty));"));
  }

  @Test
  void testTreeDrop() {
    assertEquals(
        "false",
        run(
            "program t; import io; import tree; "
                + "Tree t = tree:insert(Empty, 5); "
                + "t = tree:insert(t, 3); "
                + "t = tree:insert(t, 7); "
                + "t = tree:drop(t, 3); "
                + "io:println(tree:find(t, 3));"));
  }

  @Test
  void testTreeDropCount() {
    assertEquals(
        "2",
        run(
            "program t; import io; import tree; "
                + "Tree t = tree:insert(Empty, 5); "
                + "t = tree:insert(t, 3); "
                + "t = tree:insert(t, 7); "
                + "t = tree:drop(t, 3); "
                + "io:println(tree:count(t));"));
  }

  // ========== Arity Checking ==========

  @Test
  void testArityCheckLen() {
    assertThrows(
        Exception.class, () -> run("program t; import io; import std; io:println(std:len());"));
  }

  @Test
  void testArityCheckAppend() {
    assertThrows(
        Exception.class,
        () ->
            run(
                "program t; import io; import collections; list<int> a = [1]; io:println(collections:append(a));"));
  }
}
