package io.safelang;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

/** Tests for all 5 standard library additions (B1-B5). */
class StdlibExtensionTests {

  // ========== B1: Trig/Math Builtins ==========

  @TempDir Path directory;

  @Test
  void tangent() {
    assertEquals(
        "0.0",
        TestHelper.run(
            """
                program test;
                import io;
                import std;
                import math;
                io:println(std:str(math:tan(0.0)));
                """));
  }

  @Test
  void arcsine() {
    final var output =
        TestHelper.run(
            """
                program test;
                import io;
                import std;
                import math;
                io:println(std:str(math:asin(1.0)));
                """);
    assertTrue(output.startsWith("1.5707"));
  }

  @Test
  void arccosine() {
    assertEquals(
        "0.0",
        TestHelper.run(
            """
                program test;
                import io;
                import std;
                import math;
                io:println(std:str(math:acos(1.0)));
                """));
  }

  @Test
  void arctangent() {
    final var output =
        TestHelper.run(
            """
                program test;
                import io;
                import std;
                import math;
                io:println(std:str(math:atan(1.0)));
                """);
    assertTrue(output.startsWith("0.7853"));
  }

  @Test
  void arctangent2() {
    final var output =
        TestHelper.run(
            """
                program test;
                import io;
                import std;
                import math;
                io:println(std:str(math:atan2(1.0, 1.0)));
                """);
    assertTrue(output.startsWith("0.7853"));
  }

  @Test
  void exponential() {
    final var output =
        TestHelper.run(
            """
                program test;
                import io;
                import std;
                import math;
                io:println(std:str(math:exp(1.0)));
                """);
    assertTrue(output.startsWith("2.718"));
  }

  // ========== B2: Random Number Generation ==========

  @Test
  void logarithm10() {
    assertEquals(
        "2.0",
        TestHelper.run(
            """
                program test;
                import io;
                import std;
                import math;
                io:println(std:str(math:log10(100.0)));
                """));
  }

  @Test
  void seededRandDeterministic() {
    // Same seed should produce same value
    final var first =
        TestHelper.run(
            """
                program test;
                import io;
                import std;
                import math;
                math:seed(42);
                io:println(std:str(math:rand()));
                """);
    final var second =
        TestHelper.run(
            """
                program test;
                import io;
                import std;
                import math;
                math:seed(42);
                io:println(std:str(math:rand()));
                """);
    assertEquals(first, second);
  }

  @Test
  void randintInRange() {
    assertEquals(
        "true",
        TestHelper.run(
            """
                program test;
                import io;
                import math;
                math:seed(123);
                int r = math:randint(10, 20);
                io:println(r >= 10 && r < 20);
                """));
  }

  // ========== B3: Functional Module ==========

  @Test
  void seedChangesOutput() {
    final var first =
        TestHelper.run(
            """
                program test;
                import io;
                import std;
                import math;
                math:seed(1);
                io:println(std:str(math:rand()));
                """);
    final var second =
        TestHelper.run(
            """
                program test;
                import io;
                import std;
                import math;
                math:seed(999);
                io:println(std:str(math:rand()));
                """);
    assertNotEquals(first, second);
  }

  @Test
  void functionalMap() {
    assertEquals(
        "[2, 4, 6]",
        TestHelper.run(
            """
                program test;
                import io;
                import functional;
                io:println(functional:map([1, 2, 3], fn(x) -> x * 2));
                """));
  }

  @Test
  void functionalFilter() {
    assertEquals(
        "[2, 4]",
        TestHelper.run(
            """
                program test;
                import io;
                import functional;
                io:println(functional:filter([1, 2, 3, 4, 5], fn(x) -> x - (x / 2) * 2 == 0));
                """));
  }

  @Test
  void functionalFold() {
    assertEquals(
        "15",
        TestHelper.run(
            """
                program test;
                import io;
                import functional;
                io:println(functional:fold([1, 2, 3, 4, 5], 0, fn(a, b) -> a + b));
                """));
  }

  @Test
  void functionalFlatmap() {
    assertEquals(
        "[1, 10, 2, 20, 3, 30]",
        TestHelper.run(
            """
                program test;
                import io;
                import functional;
                io:println(functional:flatmap([1, 2, 3], fn(x) -> [x, x * 10]));
                """));
  }

  @Test
  void functionalEach() {
    assertEquals(
        "1\n2\n3",
        TestHelper.run(
            """
                program test;
                import io;
                import functional;
                functional:each([1, 2, 3], fn(x) -> io:println(x));
                """));
  }

  @Test
  void functionalChained() {
    // map then fold
    assertEquals(
        "30",
        TestHelper.run(
            """
                program test;
                import io;
                import functional;
                const list<int> doubled = functional:map([1, 2, 3, 4, 5], fn(x) -> x * 2);
                io:println(functional:fold(doubled, 0, fn(a, b) -> a + b));
                """));
  }

  // ========== B3b: Functional converge ==========

  @Test
  void functionalFoldProduct() {
    assertEquals(
        "120",
        TestHelper.run(
            """
                program test;
                import io;
                import functional;
                io:println(functional:fold([1, 2, 3, 4, 5], 1, fn(a, b) -> a * b));
                """));
  }

  @Test
  void convergeFixedPoint() {
    assertEquals(
        "0",
        TestHelper.run(
            """
                program test;
                import io;
                import functional;
                io:println(functional:converge(10, fn(x) -> x / 2, 100));
                """));
  }

  @Test
  void convergeIdentity() {
    assertEquals(
        "42",
        TestHelper.run(
            """
                program test;
                import io;
                import functional;
                io:println(functional:converge(42, fn(x) -> x, 5));
                """));
  }

  // ========== B4: Regex Support ==========

  @Test
  void convergeSqrt() {
    final var output =
        TestHelper.run(
            """
                program test;
                import io;
                import std;
                import functional;
                io:println(std:str(functional:converge(2.0, fn(x) -> (x + 2.0 / x) / 2.0, 20)));
                """);
    final var value = Double.parseDouble(output);
    assertEquals(Math.sqrt(2.0), value, 1e-10);
  }

  @Test
  void matchesTrue() {
    assertEquals(
        "true",
        TestHelper.run(
            """
                program test;
                import io;
                import strings;
                io:println(strings:matches("hello123", ".*\\\\d+"));
                """));
  }

  @Test
  void matchesFalse() {
    assertEquals(
        "false",
        TestHelper.run(
            """
                program test;
                import io;
                import strings;
                io:println(strings:matches("hello", "^\\\\d+$"));
                """));
  }

  @Test
  void findallDigits() {
    assertEquals(
        "[123, 456]",
        TestHelper.run(
            """
                program test;
                import io;
                import strings;
                io:println(strings:findall("abc123def456", "\\\\d+"));
                """));
  }

  @Test
  void findallNoMatch() {
    assertEquals(
        "[]",
        TestHelper.run(
            """
                program test;
                import io;
                import strings;
                io:println(strings:findall("hello", "\\\\d+"));
                """));
  }

  @Test
  void replaceallSpaces() {
    assertEquals(
        "hello-world-foo",
        TestHelper.run(
            """
                program test;
                import io;
                import strings;
                io:println(strings:replaceall("hello world foo", "\\\\s+", "-"));
                """));
  }

  // ========== B5: Directory Operations ==========

  @Test
  void replaceallDigits() {
    assertEquals(
        "abc***def***",
        TestHelper.run(
            """
                program test;
                import io;
                import strings;
                io:println(strings:replaceall("abc123def456", "\\\\d+", "***"));
                """));
  }

  @Test
  void mkdirAndIsdir() {
    final var target = directory.resolve("subdir").toAbsolutePath();
    assertEquals(
        "true\ntrue",
        TestHelper.run(
            """
                program test;
                import io;
                import file;
                io:println(file:mkdir("%s"));
                io:println(file:isdir("%s"));
                """
                .formatted(target, target)));
  }

  @Test
  void listdirEmpty() {
    final var target = directory.resolve("emptydir").toAbsolutePath();
    assertEquals(
        "true\n[]",
        TestHelper.run(
            """
                program test;
                import io;
                import file;
                io:println(file:mkdir("%s"));
                io:println(file:listdir("%s"));
                """
                .formatted(target, target)));
  }

  @Test
  void listdirWithFiles() throws IOException {
    final var target = directory.resolve("populated").toAbsolutePath();
    Files.createDirectories(target);
    Files.writeString(target.resolve("a.txt"), "hello");
    Files.writeString(target.resolve("b.txt"), "world");
    final var output =
        TestHelper.run(
            """
                program test;
                import io;
                import std;
                import file;
                const list<string> entries = file:listdir("%s");
                io:println(std:len(entries));
                """
                .formatted(target));
    assertEquals("2", output);
  }

  @Test
  void rmdirRemoves() throws IOException {
    final var target = directory.resolve("toremove").toAbsolutePath();
    Files.createDirectories(target);
    assertEquals(
        "true\nfalse",
        TestHelper.run(
            """
                program test;
                import io;
                import file;
                io:println(file:rmdir("%s"));
                io:println(file:isdir("%s"));
                """
                .formatted(target, target)));
  }

  @Test
  void rmdirRefusesRegularFile() throws IOException {
    final var target = directory.resolve("plain.txt").toAbsolutePath();
    Files.writeString(target, "content");
    assertEquals(
        "false",
        TestHelper.run(
            """
                program test;
                import io;
                import file;
                io:println(file:rmdir("%s"));
                """
                .formatted(target)));
    assertTrue(Files.exists(target), "rmdir must not delete regular files");
  }

  @Test
  void isdirFalseForFile() throws IOException {
    final var target = directory.resolve("notadir.txt").toAbsolutePath();
    Files.writeString(target, "content");
    assertEquals(
        "false",
        TestHelper.run(
            """
                program test;
                import io;
                import file;
                io:println(file:isdir("%s"));
                """
                .formatted(target)));
  }

  @Test
  void isdirFalseForNonexistent() {
    assertEquals(
        "false",
        TestHelper.run(
            """
                program test;
                import io;
                import file;
                io:println(file:isdir("/nonexistent/path/xyz"));
                """));
  }
}
