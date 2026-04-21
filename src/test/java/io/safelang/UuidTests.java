package io.safelang;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.*;

class UuidTests {
  private static final String P = "program test;\nimport io;\nimport std;\nimport uuid;\n";

  @Test
  void generateLength() {
    assertEquals("36", TestHelper.run(P + "io:println(std:str(std:len(uuid:generate())));\n"));
  }

  @Test
  void generateVersion() {
    assertEquals("4", TestHelper.run(P + "io:println(uuid:generate()[14]);\n"));
  }

  @Test
  void valid() {
    assertEquals("true", TestHelper.run(P + "io:println(std:str(uuid:valid(uuid:generate())));\n"));
  }

  @Test
  void nil() {
    assertEquals(
        "00000000-0000-0000-0000-000000000000", TestHelper.run(P + "io:println(uuid:nil());\n"));
  }

  @Test
  void unique() {
    assertEquals(
        "false", TestHelper.run(P + "io:println(std:str(uuid:generate() == uuid:generate()));\n"));
  }

  @Test
  void bytecodeGenerate() {
    assertEquals("36", TestHelper.bytecode(P + "io:println(std:str(std:len(uuid:generate())));\n"));
  }

  @Test
  void bytecodeValid() {
    assertEquals(
        "true", TestHelper.bytecode(P + "io:println(std:str(uuid:valid(uuid:generate())));\n"));
  }

  @Test
  void bytecodeNil() {
    assertEquals(
        "00000000-0000-0000-0000-000000000000",
        TestHelper.bytecode(P + "io:println(uuid:nil());\n"));
  }
}
