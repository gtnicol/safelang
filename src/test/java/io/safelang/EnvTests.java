package io.safelang;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.*;

class EnvTests {
  private static final String P = "program test;\nimport io;\nimport std;\nimport env;\n";

  @Test
  void getHome() {
    assertFalse(TestHelper.run(P + "io:println(env:get(\"HOME\"));\n").isEmpty());
  }

  @Test
  void getMissing() {
    assertEquals("", TestHelper.run(P + "io:println(env:get(\"SAFE_NONEXISTENT_XYZ\"));\n"));
  }

  @Test
  void hasHome() {
    assertEquals("true", TestHelper.run(P + "io:println(std:str(env:has(\"HOME\")));\n"));
  }

  @Test
  void hasMissing() {
    assertEquals(
        "false", TestHelper.run(P + "io:println(std:str(env:has(\"SAFE_NONEXISTENT_XYZ\")));\n"));
  }

  @Test
  void requireFallback() {
    assertEquals(
        "default",
        TestHelper.run(P + "io:println(env:require(\"SAFE_NONEXISTENT_XYZ\", \"default\"));\n"));
  }

  @Test
  void bytecodeGetHome() {
    assertFalse(TestHelper.bytecode(P + "io:println(env:get(\"HOME\"));\n").isEmpty());
  }

  @Test
  void bytecodeHas() {
    assertEquals("true", TestHelper.bytecode(P + "io:println(std:str(env:has(\"HOME\")));\n"));
  }

  @Test
  void bytecodeRequire() {
    assertEquals(
        "default",
        TestHelper.bytecode(
            P + "io:println(env:require(\"SAFE_NONEXISTENT_XYZ\", \"default\"));\n"));
  }
}
