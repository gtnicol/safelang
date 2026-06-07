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

class XmlTests {

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
    "json",
    "xml"
  };

  private static final String PREAMBLE =
      """
            program test;
            import io;
            import std;
            import xml;
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

  // ========== Parse ==========

  @Test
  void parseSelfClosing() {
    assertEquals(
        "<hello/>",
        run(
            PREAMBLE
                + """
                io:println(case xml:parse("<hello/>") of { Ok(v): xml:format(v); Err(e): e; });
                """));
  }

  @Test
  void parseWithText() {
    assertEquals(
        "hello world",
        run(
            PREAMBLE
                + """
                io:println(case xml:parse("<p>hello world</p>") of { Ok(v): xml:text(v); Err(e): e; });
                """));
  }

  @Test
  void parseAttributes() {
    assertEquals(
        "val",
        run(
            PREAMBLE
                + """
                io:println(case xml:parse("<a key=\\"val\\"/>") of { Ok(v): xml:attr(v, "key"); Err(e): e; });
                """));
  }

  @Test
  void parseNested() {
    assertEquals(
        "2",
        run(
            PREAMBLE
                + """
                ParseResult r = xml:parse("<root><a>1</a><b>2</b></root>");
                io:println(case r of { Ok(v): std:str(xml:count(v)); Err(e): e; });
                """));
  }

  @Test
  void parseMixedContent() {
    assertEquals(
        "Hello world!",
        run(
            PREAMBLE
                + """
                io:println(case xml:parse("<p>Hello <b>world</b>!</p>") of { Ok(v): xml:text(v); Err(e): e; });
                """));
  }

  @Test
  void parseComment() {
    assertEquals(
        "root",
        run(
            PREAMBLE
                + """
                io:println(case xml:parse("<!-- comment --><root/>") of { Ok(v): xml:tag(v); Err(e): e; });
                """));
  }

  @Test
  void parseXmlDeclaration() {
    assertEquals(
        "data",
        run(
            PREAMBLE
                + """
                io:println(case xml:parse("<?xml version=\\"1.0\\"?><root>data</root>") of { Ok(v): xml:text(v); Err(e): e; });
                """));
  }

  @Test
  void parseEntities() {
    assertEquals(
        "A & B",
        run(
            PREAMBLE
                + """
                io:println(case xml:parse("<p>A &amp; B</p>") of { Ok(v): xml:text(v); Err(e): e; });
                """));
  }

  // ========== Errors ==========

  @Test
  void parseErrors() {
    final var result =
        run(
            PREAMBLE
                + """
                io:println(case xml:parse("") of { Ok(v): "ok"; Err(e): "err"; });
                io:println(case xml:parse("<a></b>") of { Ok(v): "ok"; Err(e): "err"; });
                io:println(case xml:parse("<a>") of { Ok(v): "ok"; Err(e): "err"; });
                io:println(case xml:parse("<a/> extra") of { Ok(v): "ok"; Err(e): "err"; });
                io:println(case xml:parse("just text") of { Ok(v): "ok"; Err(e): "err"; });
                """);
    assertEquals("err\nerr\nerr\nerr\nerr", result);
  }

  // ========== Format ==========

  @Test
  void formatElements() {
    final var result =
        run(
            PREAMBLE
                + """
                io:println(xml:format(Element("br", {}, [])));
                io:println(xml:format(Element("p", {}, [Text("hi")])));
                io:println(xml:format(Element("a", {"href": "url"}, [Text("link")])));
                io:println(xml:format(Text("hello")));
                """);
    assertEquals("<br/>\n<p>hi</p>\n<a href=\"url\">link</a>\nhello", result);
  }

  @Test
  void formatEscaping() {
    final var result =
        run(
            PREAMBLE
                + """
                io:println(xml:format(Text("A & B")));
                io:println(xml:format(Text("<tag>")));
                """);
    assertEquals("A &amp; B\n&lt;tag&gt;", result);
  }

  // ========== Round Trip ==========

  @Test
  void roundTrip() {
    final var result =
        run(
            PREAMBLE
                + """
                const string input = "<root attr=\\"val\\"><child>text</child></root>";
                ParseResult r = xml:parse(input);
                const string out = case r of { Ok(v): xml:format(v); Err(e): "error"; };
                io:println(out);
                io:println(std:str(out == input));
                """);
    assertEquals("<root attr=\"val\"><child>text</child></root>\ntrue", result);
  }

  // ========== Helpers ==========

  @Test
  void findHelper() {
    final var result =
        run(
            PREAMBLE
                + """
                ParseResult r = xml:parse("<root><item id=\\"1\\"/><other/><item id=\\"2\\"/></root>");
                Xml root = case r of { Ok(v): v; Err(e): Text(""); };
                io:println(std:str(std:len(xml:find(root, "item"))));
                io:println(std:str(std:len(xml:find(root, "missing"))));
                """);
    assertEquals("2\n0", result);
  }

  @Test
  void tagHelper() {
    assertEquals(
        "div",
        run(
            PREAMBLE
                + """
                io:println(xml:tag(Element("div", {}, [])));
                """));
    assertEquals(
        "",
        run(
            PREAMBLE
                + """
                io:println(xml:tag(Text("hi")));
                """));
  }

  // ========== New Node Types ==========

  @Test
  void parseCommentNode() {
    final var result =
        run(
            PREAMBLE
                + """
                ParseResult r = xml:parse("<root><!--note--><a/></root>");
                Xml root = case r of { Ok(v): v; Err(e): Text(""); };
                io:println(std:str(xml:count(root)));
                const list<Xml> kids = xml:children(root);
                io:println(case kids[0] of { Comment(t): t; _: "none"; });
                """);
    assertEquals("2\nnote", result);
  }

  @Test
  void parseCdata() {
    assertEquals(
        "raw <text>",
        run(
            PREAMBLE
                + """
                io:println(case xml:parse("<root><![CDATA[raw <text>]]></root>") of { Ok(v): xml:text(v); Err(e): e; });
                """));
  }

  @Test
  void parsePI() {
    final var result =
        run(
            PREAMBLE
                + """
                ParseResult r = xml:parse("<root><?style href=\\"s.css\\"?><a/></root>");
                Xml root = case r of { Ok(v): v; Err(e): Text(""); };
                const list<Xml> kids = xml:children(root);
                io:println(case kids[0] of { PI(t, d): t; _: "none"; });
                """);
    assertEquals("style", result);
  }

  @Test
  void parseNumericEntities() {
    final var result =
        run(
            PREAMBLE
                + """
                io:println(case xml:parse("<p>&#65;&#x42;</p>") of { Ok(v): xml:text(v); Err(e): e; });
                """);
    assertEquals("AB", result);
  }

  @Test
  void parseEntityReference() {
    final var result =
        run(
            PREAMBLE
                + """
                ParseResult r = xml:parse("<p>&custom;</p>");
                Xml root = case r of { Ok(v): v; Err(e): Text(""); };
                const list<Xml> kids = xml:children(root);
                io:println(case kids[0] of { EntityReference(n): n; _: "none"; });
                """);
    assertEquals("custom", result);
  }

  @Test
  void formatNewTypes() {
    final var result =
        run(
            PREAMBLE
                + """
                io:println(xml:format(Comment(" note ")));
                io:println(xml:format(CData("raw")));
                io:println(xml:format(PI("style", "href")));
                io:println(xml:format(EntityReference("nbsp")));
                """);
    assertEquals("<!-- note -->\n<![CDATA[raw]]>\n<?style href?>\n&nbsp;", result);
  }

  // ========== Bytecode ==========

  @Test
  void bytecodeParseAndFormat() {
    final var result =
        runBytecode(
            PREAMBLE
                + """
                io:println(case xml:parse("<a key=\\"val\\">text</a>") of { Ok(v): xml:format(v); Err(e): e; });
                """);
    assertEquals("<a key=\"val\">text</a>", result);
  }

  @Test
  void bytecodeNested() {
    final var result =
        runBytecode(
            PREAMBLE
                + """
                ParseResult r = xml:parse("<root><a>1</a><b>2</b></root>");
                io:println(case r of { Ok(v): xml:text(v); Err(e): e; });
                """);
    assertEquals("12", result);
  }

  @Test
  void bytecodeErrors() {
    final var result =
        runBytecode(
            PREAMBLE
                + """
                io:println(case xml:parse("") of { Ok(v): "ok"; Err(e): "err"; });
                io:println(case xml:parse("<a></b>") of { Ok(v): "ok"; Err(e): "err"; });
                """);
    assertEquals("err\nerr", result);
  }

  @Test
  void bytecodeNewTypes() {
    final var result =
        runBytecode(
            PREAMBLE
                + """
                io:println(case xml:parse("<root><![CDATA[data]]></root>") of { Ok(v): xml:text(v); Err(e): e; });
                io:println(case xml:parse("<p>&#65;</p>") of { Ok(v): xml:text(v); Err(e): e; });
                io:println(xml:format(Comment(" c ")));
                """);
    assertEquals("data\nA\n<!-- c -->", result);
  }

  // ========== Construction ==========

  @Test
  void constructDocument() {
    final var result =
        run(
            PREAMBLE
                + """
                Xml doc = Element("html", {}, [
                    Element("head", {}, [Element("title", {}, [Text("Test")])]),
                    Element("body", {}, [Element("p", {}, [Text("Hello")])])
                ]);
                io:println(xml:format(doc));
                """);
    assertEquals("<html><head><title>Test</title></head><body><p>Hello</p></body></html>", result);
  }

  // ========== File Loading ==========

  @Test
  void loadXmlFile() throws Exception {
    Files.writeString(Path.of("/tmp/test_junit.xml"), "<root><child>data</child></root>");
    final var result =
        run(
            PREAMBLE
                + """
                io:println(case xml:load("/tmp/test_junit.xml") of { Ok(v): xml:text(v); Err(e): e; });
                """);
    assertEquals("data", result);
    Files.deleteIfExists(Path.of("/tmp/test_junit.xml"));
  }

  @Test
  void loadMissingXmlFile() {
    final var result =
        run(
            PREAMBLE
                + """
                io:println(case xml:load("/tmp/nonexistent_junit.xml") of { Ok(v): "ok"; Err(e): "err"; });
                """);
    assertEquals("err", result);
  }
}
