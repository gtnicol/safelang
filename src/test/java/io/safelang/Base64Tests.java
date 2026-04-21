package io.safelang;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.*;

class Base64Tests {
  private static final String P = "program test;\nimport io;\nimport std;\nimport base64;\n";

  @Test
  void encode() {
    assertEquals("SGVsbG8=", TestHelper.run(P + "io:println(base64:encode(\"Hello\"));\n"));
  }

  @Test
  void decode() {
    assertEquals("Hello", TestHelper.run(P + "io:println(base64:decode(\"SGVsbG8=\"));\n"));
  }

  @Test
  void roundTrip() {
    assertEquals(
        "test", TestHelper.run(P + "io:println(base64:decode(base64:encode(\"test\")));\n"));
  }

  @Test
  void noPadding() {
    assertEquals("TWFu", TestHelper.run(P + "io:println(base64:encode(\"Man\"));\n"));
  }

  @Test
  void emptyEncode() {
    assertEquals("", TestHelper.run(P + "io:println(base64:encode(\"\"));\n"));
  }

  @Test
  void bytecodeEncode() {
    assertEquals("SGVsbG8=", TestHelper.bytecode(P + "io:println(base64:encode(\"Hello\"));\n"));
  }

  @Test
  void bytecodeDecode() {
    assertEquals("Hello", TestHelper.bytecode(P + "io:println(base64:decode(\"SGVsbG8=\"));\n"));
  }

  @Test
  void bytecodeRoundTrip() {
    assertEquals(
        "abc", TestHelper.bytecode(P + "io:println(base64:decode(base64:encode(\"abc\")));\n"));
  }
}
