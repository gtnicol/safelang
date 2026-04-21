package io.safelang;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.*;

class CsvTests {
  private static final String P = "program test;\nimport io;\nimport std;\nimport csv;\n";

  @Test
  void parseSimple() {
    assertEquals(
        "3",
        TestHelper.run(
            P
                + """
                io:println(std:str(std:len(csv:parse("a,b\\n1,2\\n3,4"))));
                """));
  }

  @Test
  void parseQuoted() {
    assertEquals(
        "has, comma",
        TestHelper.run(
            P
                + """
                const list<list<string>> rows = csv:parse("a\\n\\"has, comma\\"");
                io:println(rows[1][0]);
                """));
  }

  @Test
  void records() {
    assertEquals(
        "Alice",
        TestHelper.run(
            P
                + """
                const list<map<string, string>> recs = csv:records("name,age\\nAlice,30");
                io:println(recs[0]["name"]);
                """));
  }

  @Test
  void format() {
    assertEquals(
        "a,b\n1,2",
        TestHelper.run(
            P
                + """
                io:println(csv:format([["a","b"],["1","2"]]));
                """));
  }

  @Test
  void line() {
    assertEquals("a,b,c", TestHelper.run(P + "io:println(csv:line([\"a\",\"b\",\"c\"]));\n"));
  }

  @Test
  void roundTrip() {
    assertEquals(
        "true",
        TestHelper.run(
            P
                + """
                const string input = "a,b\\n1,2";
                io:println(std:str(csv:format(csv:parse(input)) == input));
                """));
  }

  @Test
  void bytecodeParse() {
    assertEquals(
        "3",
        TestHelper.bytecode(
            P
                + """
                io:println(std:str(std:len(csv:parse("a,b\\n1,2\\n3,4"))));
                """));
  }

  @Test
  void bytecodeRecords() {
    assertEquals(
        "Alice",
        TestHelper.bytecode(
            P
                + """
                const list<map<string, string>> recs = csv:records("name,age\\nAlice,30");
                io:println(recs[0]["name"]);
                """));
  }

  @Test
  void bytecodeLine() {
    assertEquals("a,b", TestHelper.bytecode(P + "io:println(csv:line([\"a\",\"b\"]));\n"));
  }
}
