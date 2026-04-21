package io.safelang;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.*;

class DatetimeTests {
  private static final String P = "program test;\nimport io;\nimport std;\nimport datetime;\n";

  @Test
  void create() {
    assertEquals(
        "2024-03-15T10:30:45Z",
        TestHelper.run(
            P
                + """
                Timestamp t = datetime:create(2024, 3, 15, 10, 30, 45);
                io:println(datetime:format(t));
                """));
  }

  @Test
  void year() {
    assertEquals(
        "2024",
        TestHelper.run(
            P + "io:println(std:str(datetime:year(datetime:create(2024, 6, 1, 0, 0, 0))));\n"));
  }

  @Test
  void month() {
    assertEquals(
        "6",
        TestHelper.run(
            P + "io:println(std:str(datetime:month(datetime:create(2024, 6, 1, 0, 0, 0))));\n"));
  }

  @Test
  void day() {
    assertEquals(
        "15",
        TestHelper.run(
            P + "io:println(std:str(datetime:day(datetime:create(2024, 3, 15, 0, 0, 0))));\n"));
  }

  @Test
  void epoch() {
    assertEquals(
        "0",
        TestHelper.run(P + "io:println(std:str(datetime:create(1970, 1, 1, 0, 0, 0).millis));\n"));
  }

  @Test
  void leap() {
    assertEquals(
        "true\nfalse",
        TestHelper.run(
            P
                + "io:println(std:str(datetime:leap(2024)));\nio:println(std:str(datetime:leap(2023)));\n"));
  }

  @Test
  void addDays() {
    assertEquals(
        "25",
        TestHelper.run(
            P
                + """
                Timestamp t = datetime:add(datetime:create(2024, 3, 15, 0, 0, 0), 10);
                io:println(std:str(datetime:day(t)));
                """));
  }

  @Test
  void diff() {
    assertEquals(
        "5",
        TestHelper.run(
            P
                + """
                Timestamp a = datetime:create(2024, 3, 15, 0, 0, 0);
                Timestamp b = datetime:create(2024, 3, 10, 0, 0, 0);
                io:println(std:str(datetime:diff(a, b)));
                """));
  }

  @Test
  void now() {
    assertEquals(
        "true",
        TestHelper.run(P + "io:println(std:str(datetime:year(datetime:now()) >= 2024));\n"));
  }

  @Test
  void bytecodeCreate() {
    assertEquals(
        "2024-03-15T10:30:45Z",
        TestHelper.bytecode(
            P
                + """
                Timestamp t = datetime:create(2024, 3, 15, 10, 30, 45);
                io:println(datetime:format(t));
                """));
  }

  @Test
  void bytecodeLeap() {
    assertEquals("true", TestHelper.bytecode(P + "io:println(std:str(datetime:leap(2000)));\n"));
  }

  @Test
  void bytecodeDiff() {
    assertEquals(
        "5",
        TestHelper.bytecode(
            P
                + """
                io:println(std:str(datetime:diff(datetime:create(2024, 3, 15, 0, 0, 0), datetime:create(2024, 3, 10, 0, 0, 0))));
                """));
  }
}
