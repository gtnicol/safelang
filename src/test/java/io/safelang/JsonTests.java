package io.safelang;

import static org.junit.jupiter.api.Assertions.*;

import io.safelang.analyzer.SemanticAnalyzer;
import io.safelang.bytecode.*;
import io.safelang.compiler.bytecode.*;
import io.safelang.interpreter.Interpreter;
import io.safelang.parser.SAFEParser;
import java.io.*;
import java.nio.file.*;
import org.junit.jupiter.api.*;

class JsonTests {

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
    "functional",
    "binary",
    "hash",
    "json"
  };

  private static final String PREAMBLE =
      """
            program test;
            import io;
            import std;
            import json;
            """;

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

  private String runBytecode(final String source) {
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
    final var compiler = new BytecodeCompiler();
    compiler.setRegistry(registry);
    final var module = compiler.compile(program);

    final var old = System.out;
    final var buffer = new ByteArrayOutputStream();
    System.setOut(new PrintStream(buffer));
    try {
      final var vm = new BytecodeVM(module);
      vm.execute();
    } finally {
      System.setOut(old);
    }
    return buffer.toString().trim();
  }

  // ========== Parse Primitives ==========

  @Test
  void parseNull() {
    final var result =
        run(
            PREAMBLE
                + """
                ParseResult r = json:parse("null");
                io:println(case r of { Ok(j): json:kind(j); Err(e): e; });
                io:println(case r of { Ok(j): json:format(j); Err(e): e; });
                io:println(case r of { Ok(j): std:str(json:blank(j)); Err(e): e; });
                """);
    assertEquals("null\nnull\ntrue", result);
  }

  @Test
  void parseBooleans() {
    final var result =
        run(
            PREAMBLE
                + """
                ParseResult r1 = json:parse("true");
                ParseResult r2 = json:parse("false");
                io:println(case r1 of { Ok(j): json:format(j); Err(e): e; });
                io:println(case r2 of { Ok(j): json:format(j); Err(e): e; });
                io:println(case r1 of { Ok(j): json:kind(j); Err(e): e; });
                """);
    assertEquals("true\nfalse\nboolean", result);
  }

  @Test
  void parseIntegers() {
    final var result =
        run(
            PREAMBLE
                + """
                io:println(case json:parse("0") of { Ok(j): json:format(j); Err(e): e; });
                io:println(case json:parse("42") of { Ok(j): json:format(j); Err(e): e; });
                io:println(case json:parse("-7") of { Ok(j): json:format(j); Err(e): e; });
                io:println(case json:parse("42") of { Ok(j): json:kind(j); Err(e): e; });
                """);
    assertEquals("0\n42\n-7\ninteger", result);
  }

  @Test
  void parseFloats() {
    final var result =
        run(
            PREAMBLE
                + """
                io:println(case json:parse("3.14") of { Ok(j): json:kind(j); Err(e): e; });
                io:println(case json:parse("-0.5") of { Ok(j): json:format(j); Err(e): e; });
                io:println(case json:parse("1e10") of { Ok(j): json:kind(j); Err(e): e; });
                """);
    assertEquals("float\n-0.5\nfloat", result);
  }

  @Test
  void parseStrings() {
    final var result =
        run(
            PREAMBLE
                + """
                io:println(case json:parse("\\"hello\\"") of { Ok(j): json:format(j); Err(e): e; });
                io:println(case json:parse("\\"\\"") of { Ok(j): json:format(j); Err(e): e; });
                io:println(case json:parse("\\"hello\\"") of { Ok(j): json:kind(j); Err(e): e; });
                """);
    assertEquals("\"hello\"\n\"\"\nstring", result);
  }

  // ========== Parse Structures ==========

  @Test
  void parseArrays() {
    final var result =
        run(
            PREAMBLE
                + """
                io:println(case json:parse("[]") of { Ok(j): json:format(j); Err(e): e; });
                io:println(case json:parse("[1]") of { Ok(j): json:format(j); Err(e): e; });
                io:println(case json:parse("[1,2,3]") of { Ok(j): json:format(j); Err(e): e; });
                io:println(case json:parse("[[1],[2]]") of { Ok(j): json:format(j); Err(e): e; });
                """);
    assertEquals("[]\n[1]\n[1,2,3]\n[[1],[2]]", result);
  }

  @Test
  void parseObjects() {
    final var result =
        run(
            PREAMBLE
                + """
                io:println(case json:parse("{}") of { Ok(j): json:format(j); Err(e): e; });
                io:println(case json:parse("{\\"a\\":1}") of { Ok(j): json:format(j); Err(e): e; });
                io:println(case json:parse("{\\"a\\":1,\\"b\\":2}") of { Ok(j): json:format(j); Err(e): e; });
                """);
    assertEquals("{}\n{\"a\":1}\n{\"a\":1,\"b\":2}", result);
  }

  @Test
  void parseNested() {
    final var result =
        run(
            PREAMBLE
                + """
                ParseResult r = json:parse("{\\"items\\":[1,2],\\"name\\":\\"test\\"}");
                io:println(case r of { Ok(j): json:format(j); Err(e): e; });
                """);
    assertEquals("{\"items\":[1,2],\"name\":\"test\"}", result);
  }

  @Test
  void parseWhitespace() {
    final var result =
        run(
            PREAMBLE
                + """
                ParseResult r = json:parse("  { \\"a\\" : 1 , \\"b\\" : 2 }  ");
                io:println(case r of { Ok(j): json:format(j); Err(e): e; });
                """);
    assertEquals("{\"a\":1,\"b\":2}", result);
  }

  // ========== Parse Errors ==========

  @Test
  void parseErrors() {
    final var result =
        run(
            PREAMBLE
                + """
                io:println(case json:parse("") of { Ok(j): "ok"; Err(e): "err"; });
                io:println(case json:parse("}") of { Ok(j): "ok"; Err(e): "err"; });
                io:println(case json:parse("[1,2") of { Ok(j): "ok"; Err(e): "err"; });
                io:println(case json:parse("1 2") of { Ok(j): "ok"; Err(e): "err"; });
                io:println(case json:parse("{\\"a\\"}") of { Ok(j): "ok"; Err(e): "err"; });
                """);
    assertEquals("err\nerr\nerr\nerr\nerr", result);
  }

  // ========== Format ==========

  @Test
  void formatPrimitives() {
    final var result =
        run(
            PREAMBLE
                + """
                io:println(json:format(Null));
                io:println(json:format(Bool(true)));
                io:println(json:format(Bool(false)));
                io:println(json:format(Int(42)));
                io:println(json:format(Str("hi")));
                """);
    assertEquals("null\ntrue\nfalse\n42\n\"hi\"", result);
  }

  @Test
  void formatStructures() {
    final var result =
        run(
            PREAMBLE
                + """
                io:println(json:format(Array([])));
                io:println(json:format(Array([Int(1), Int(2)])));
                io:println(json:format(Object({})));
                io:println(json:format(Object({"a": Int(1)})));
                """);
    assertEquals("[]\n[1,2]\n{}\n{\"a\":1}", result);
  }

  @Test
  void formatEscaping() {
    final var result =
        run(
            PREAMBLE
                + """
                io:println(json:format(Str("line1\\nline2")));
                io:println(json:format(Str("tab\\there")));
                io:println(json:format(Str("quo\\"te")));
                """);
    assertEquals("\"line1\\nline2\"\n\"tab\\there\"\n\"quo\\\"te\"", result);
  }

  // ========== Round Trip ==========

  @Test
  void roundTrip() {
    final var result =
        run(
            PREAMBLE
                + """
                const string input = "{\\"name\\":\\"Alice\\",\\"scores\\":[95,87]}";
                ParseResult r1 = json:parse(input);
                const string formatted = case r1 of { Ok(j): json:format(j); Err(e): "error"; };
                ParseResult r2 = json:parse(formatted);
                const string again = case r2 of { Ok(j): json:format(j); Err(e): "error"; };
                io:println(formatted);
                io:println(std:str(formatted == again));
                """);
    assertEquals("{\"name\":\"Alice\",\"scores\":[95,87]}\ntrue", result);
  }

  // ========== Accessors ==========

  @Test
  void getHelper() {
    final var result =
        run(
            PREAMBLE
                + """
                ParseResult r = json:parse("{\\"x\\":42,\\"y\\":\\"hello\\"}");
                Json obj = case r of { Ok(j): j; Err(e): Null; };
                io:println(json:format(json:get(obj, "x")));
                io:println(json:format(json:get(obj, "y")));
                io:println(json:format(json:get(obj, "z")));
                io:println(json:format(json:get(Int(5), "x")));
                """);
    assertEquals("42\n\"hello\"\nnull\nnull", result);
  }

  @Test
  void atHelper() {
    final var result =
        run(
            PREAMBLE
                + """
                ParseResult r = json:parse("[10,20,30]");
                Json arr = case r of { Ok(j): j; Err(e): Null; };
                io:println(json:format(json:at(arr, 0)));
                io:println(json:format(json:at(arr, 2)));
                io:println(json:format(json:at(arr, -1)));
                io:println(json:format(json:at(arr, 99)));
                io:println(json:format(json:at(Null, 0)));
                """);
    assertEquals("10\n30\nnull\nnull\nnull", result);
  }

  @Test
  void fieldsHelper() {
    final var result =
        run(
            PREAMBLE
                + """
                ParseResult r = json:parse("{\\"a\\":1,\\"b\\":2}");
                Json obj = case r of { Ok(j): j; Err(e): Null; };
                io:println(std:str(std:len(json:fields(obj))));
                io:println(std:str(std:len(json:fields(Null))));
                """);
    assertEquals("2\n0", result);
  }

  @Test
  void countHelper() {
    final var result =
        run(
            PREAMBLE
                + """
                io:println(std:str(json:count(Array([Int(1), Int(2), Int(3)]))));
                io:println(std:str(json:count(Object({"a": Int(1)}))));
                io:println(std:str(json:count(Str("hello"))));
                io:println(std:str(json:count(Null)));
                """);
    assertEquals("3\n1\n5\n0", result);
  }

  @Test
  void blankHelper() {
    final var result =
        run(
            PREAMBLE
                + """
                io:println(std:str(json:blank(Null)));
                io:println(std:str(json:blank(Bool(false))));
                io:println(std:str(json:blank(Int(0))));
                io:println(std:str(json:blank(Str(""))));
                io:println(std:str(json:blank(Array([]))));
                """);
    assertEquals("true\nfalse\nfalse\nfalse\nfalse", result);
  }

  @Test
  void kindHelper() {
    final var result =
        run(
            PREAMBLE
                + """
                io:println(json:kind(Null));
                io:println(json:kind(Bool(true)));
                io:println(json:kind(Int(1)));
                io:println(json:kind(Float(1.0)));
                io:println(json:kind(Str("x")));
                io:println(json:kind(Array([])));
                io:println(json:kind(Object({})));
                """);
    assertEquals("null\nboolean\ninteger\nfloat\nstring\narray\nobject", result);
  }

  // ========== Pattern Matching ==========

  @Test
  void patternMatch() {
    final var result =
        run(
            PREAMBLE
                + """
                ParseResult r = json:parse("{\\"val\\":42}");
                Json obj = case r of { Ok(j): j; Err(e): Null; };
                Json inner = json:get(obj, "val");
                const int n = case inner of { Int(x): x; _: 0; };
                io:println(std:str(n));
                """);
    assertEquals("42", result);
  }

  // ========== Bytecode Backend ==========

  @Test
  void bytecodeParseAndFormat() {
    final var result =
        runBytecode(
            PREAMBLE
                + """
                io:println(case json:parse("42") of { Ok(j): json:format(j); Err(e): e; });
                io:println(case json:parse("\\"hi\\"") of { Ok(j): json:format(j); Err(e): e; });
                io:println(case json:parse("[1,2]") of { Ok(j): json:format(j); Err(e): e; });
                io:println(case json:parse("{\\"a\\":1}") of { Ok(j): json:format(j); Err(e): e; });
                """);
    assertEquals("42\n\"hi\"\n[1,2]\n{\"a\":1}", result);
  }

  @Test
  void bytecodeHelpers() {
    final var result =
        runBytecode(
            PREAMBLE
                + """
                ParseResult r = json:parse("{\\"x\\":10,\\"y\\":20}");
                Json obj = case r of { Ok(j): j; Err(e): Null; };
                io:println(json:format(json:get(obj, "x")));
                io:println(json:kind(obj));
                io:println(std:str(json:count(obj)));
                """);
    assertEquals("10\nobject\n2", result);
  }

  @Test
  void bytecodeRoundTrip() {
    final var result =
        runBytecode(
            PREAMBLE
                + """
                const string input = "{\\"name\\":\\"Bob\\",\\"items\\":[1,2,3]}";
                ParseResult r = json:parse(input);
                const string out = case r of { Ok(j): json:format(j); Err(e): "error"; };
                io:println(out);
                """);
    assertEquals("{\"name\":\"Bob\",\"items\":[1,2,3]}", result);
  }

  @Test
  void bytecodeErrors() {
    final var result =
        runBytecode(
            PREAMBLE
                + """
                io:println(case json:parse("") of { Ok(j): "ok"; Err(e): "err"; });
                io:println(case json:parse("[1,") of { Ok(j): "ok"; Err(e): "err"; });
                """);
    assertEquals("err\nerr", result);
  }

  // ========== Construction ==========

  @Test
  void constructAndFormat() {
    final var result =
        run(
            PREAMBLE
                + """
                Json nested = Object({"data": Array([Object({"id": Int(1)}), Object({"id": Int(2)})])});
                io:println(json:format(nested));
                """);
    assertEquals("{\"data\":[{\"id\":1},{\"id\":2}]}", result);
  }

  // ========== JSONL ==========

  @Test
  void jsonlParsing() {
    final var result =
        run(
            PREAMBLE
                + """
                const list<Json> items = json:jsonl("{\\"a\\":1}\\n{\\"b\\":2}\\n{\\"c\\":3}");
                io:println(std:str(std:len(items)));
                io:println(json:format(items[0]));
                io:println(json:format(items[2]));
                """);
    assertEquals("3\n{\"a\":1}\n{\"c\":3}", result);
  }

  @Test
  void jsonlSkipsBlankLines() {
    final var result =
        run(
            PREAMBLE
                + """
                const list<Json> items = json:jsonl("{\\"a\\":1}\\n\\n{\\"b\\":2}\\n");
                io:println(std:str(std:len(items)));
                """);
    assertEquals("2", result);
  }

  @Test
  void jsonlMixedTypes() {
    final var result =
        run(
            PREAMBLE
                + """
                const list<Json> items = json:jsonl("1\\n\\"two\\"\\ntrue\\nnull");
                io:println(std:str(std:len(items)));
                """);
    assertEquals("4", result);
  }

  // ========== File Loading ==========

  @Test
  void loadJsonFile() throws Exception {
    Files.writeString(
        Path.of("/tmp/test_junit_json.json"), "{\"x\":42}");
    final var result =
        run(
            PREAMBLE
                + """
                io:println(case json:load("/tmp/test_junit_json.json") of { Ok(j): json:format(j); Err(e): e; });
                """);
    assertEquals("{\"x\":42}", result);
    Files.deleteIfExists(Path.of("/tmp/test_junit_json.json"));
  }

  @Test
  void loadMissingFile() {
    final var result =
        run(
            PREAMBLE
                + """
                io:println(case json:load("/tmp/nonexistent_junit.json") of { Ok(j): "ok"; Err(e): "err"; });
                """);
    assertEquals("err", result);
  }

  @Test
  void loadJsonlFile() throws Exception {
    Files.writeString(
        Path.of("/tmp/test_junit.jsonl"), "{\"id\":1}\n{\"id\":2}\n{\"id\":3}");
    final var result =
        run(
            PREAMBLE
                + """
                io:println(case json:load("/tmp/test_junit.jsonl", JSONL) of { Ok(j): std:str(json:count(j)); Err(e): e; });
                """);
    assertEquals("3", result);
    Files.deleteIfExists(Path.of("/tmp/test_junit.jsonl"));
  }
}
