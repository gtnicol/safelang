package io.safelang;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.*;

class PathTests {
  private static final String P = "program test;\nimport io;\nimport std;\nimport path;\n";

  @Test
  void join() {
    assertEquals("a/b", TestHelper.run(P + "io:println(path:join(\"a\", \"b\"));\n"));
  }

  @Test
  void parent() {
    assertEquals("/a/b", TestHelper.run(P + "io:println(path:parent(\"/a/b/c\"));\n"));
  }

  @Test
  void name() {
    assertEquals("file.txt", TestHelper.run(P + "io:println(path:name(\"/a/b/file.txt\"));\n"));
  }

  @Test
  void stem() {
    assertEquals("file", TestHelper.run(P + "io:println(path:stem(\"file.txt\"));\n"));
  }

  @Test
  void extension() {
    assertEquals(".txt", TestHelper.run(P + "io:println(path:extension(\"file.txt\"));\n"));
  }

  @Test
  void absolute() {
    assertEquals("true", TestHelper.run(P + "io:println(std:str(path:absolute(\"/a\")));\n"));
  }

  @Test
  void normalize() {
    assertEquals("/a/b", TestHelper.run(P + "io:println(path:normalize(\"/a//b/\"));\n"));
  }

  @Test
  void segments() {
    assertEquals(
        "3", TestHelper.run(P + "io:println(std:str(std:len(path:segments(\"/a/b/c\"))));\n"));
  }

  @Test
  void bytecodeJoin() {
    assertEquals("a/b", TestHelper.bytecode(P + "io:println(path:join(\"a\", \"b\"));\n"));
  }

  @Test
  void bytecodeStem() {
    assertEquals("file", TestHelper.bytecode(P + "io:println(path:stem(\"file.txt\"));\n"));
  }

  @Test
  void bytecodeNormalize() {
    assertEquals("/a/b/c", TestHelper.bytecode(P + "io:println(path:normalize(\"/a//b///c\"));\n"));
  }

  @Test
  void bytecodeSegments() {
    assertEquals(
        "3", TestHelper.bytecode(P + "io:println(std:str(std:len(path:segments(\"/a/b/c\"))));\n"));
  }
}
