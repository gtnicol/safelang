package io.safelang;

import static org.junit.jupiter.api.Assertions.*;

import java.io.StringWriter;
import java.util.List;
import java.util.Map;
import javax.script.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ScriptEngineTests {

  private ScriptEngine engine;

  @BeforeEach
  void setup() {
    engine = new ScriptEngineManager().getEngineByName("safe");
  }

  // ---- Discovery ----

  @Test
  void discoveryByName() {
    assertNotNull(engine, "Engine should be found by name 'safe'");
  }

  @Test
  void discoveryByAlternateName() {
    final var manager = new ScriptEngineManager();
    assertNotNull(manager.getEngineByName("SAFE"));
    assertNotNull(manager.getEngineByName("safe-lang"));
  }

  @Test
  void discoveryByExtension() {
    final var manager = new ScriptEngineManager();
    assertNotNull(manager.getEngineByExtension("safe"));
  }

  @Test
  void discoveryByMime() {
    final var manager = new ScriptEngineManager();
    assertNotNull(manager.getEngineByMimeType("application/x-safe"));
  }

  // ---- Factory Metadata ----

  @Test
  void factoryMetadata() {
    final var factory = engine.getFactory();
    assertEquals("SAFE Language Engine", factory.getEngineName());
    assertEquals("1.0", factory.getEngineVersion());
    assertEquals("SAFE", factory.getLanguageName());
    assertEquals("1.0", factory.getLanguageVersion());
  }

  @Test
  void factoryOutputStatement() {
    final var factory = engine.getFactory();
    assertEquals("io:println(\"hello\")", factory.getOutputStatement("hello"));
  }

  @Test
  void factoryMethodCallSyntax() {
    final var factory = engine.getFactory();
    assertEquals("math:sqrt(x)", factory.getMethodCallSyntax("math", "sqrt", "x"));
  }

  @Test
  void factoryProgram() {
    final var factory = engine.getFactory();
    final var result = factory.getProgram("io:println(\"hi\")");
    assertTrue(result.startsWith("program script;"));
    assertTrue(result.contains("io:println(\"hi\");"));
  }

  // ---- Simple Eval ----

  @Test
  void evalArithmetic() throws ScriptException {
    final var result = engine.eval("program test;\n2 + 3;");
    assertEquals(5L, result);
  }

  @Test
  void evalString() throws ScriptException {
    final var result = engine.eval("program test;\n\"hello\";");
    assertEquals("hello", result);
  }

  @Test
  void evalBoolean() throws ScriptException {
    final var result = engine.eval("program test;\ntrue;");
    assertEquals(true, result);
  }

  @Test
  void evalFloat() throws ScriptException {
    final var result = engine.eval("program test;\n3.14;");
    assertEquals(3.14, result);
  }

  @Test
  void evalVoid() throws ScriptException {
    final var result = engine.eval("program test;\nimport io;\nio:println(\"hi\");");
    assertNull(result);
  }

  // ---- Output Redirection ----

  @Test
  void outputRedirection() throws ScriptException {
    final var writer = new StringWriter();
    engine.getContext().setWriter(writer);
    engine.eval("program test;\nimport io;\nio:println(\"captured\");");
    assertTrue(writer.toString().contains("captured"));
  }

  @Test
  void outputMultipleLines() throws ScriptException {
    final var writer = new StringWriter();
    engine.getContext().setWriter(writer);
    engine.eval("program test;\nimport io;\nio:println(\"one\");\nio:println(\"two\");");
    final var output = writer.toString();
    assertTrue(output.contains("one"));
    assertTrue(output.contains("two"));
  }

  @Test
  void printWithoutNewline() throws ScriptException {
    final var writer = new StringWriter();
    engine.getContext().setWriter(writer);
    engine.eval("program test;\nimport io;\nio:print(\"hello\");\nio:print(\" world\");");
    assertEquals("hello world", writer.toString());
  }

  // ---- Bindings ----

  @Test
  void bindingsInjection() throws ScriptException {
    final var writer = new StringWriter();
    engine.getContext().setWriter(writer);
    engine.put("x", 42);
    engine.eval("program test;\nimport io;\nimport std;\nio:println(std:str(x + 1));");
    assertTrue(writer.toString().contains("43"));
  }

  @Test
  void bindingsStringValue() throws ScriptException {
    engine.put("name", "world");
    final var result = engine.eval("import std;\n\"hello \" + name;");
    assertEquals("hello world", result);
  }

  @Test
  void bindingsBooleanValue() throws ScriptException {
    engine.put("flag", true);
    final var result = engine.eval("if (flag) then 1 else 0;");
    assertEquals(1L, result);
  }

  // ---- Auto-Wrapping ----

  @Test
  void autoWrapping() throws ScriptException {
    final var result = engine.eval("2 + 2;");
    assertEquals(4L, result);
  }

  @Test
  void autoWrappingWithImports() throws ScriptException {
    final var writer = new StringWriter();
    engine.getContext().setWriter(writer);
    engine.eval("import io;\nio:println(\"auto\");");
    assertTrue(writer.toString().contains("auto"));
  }

  @Test
  void explicitProgramHeader() throws ScriptException {
    final var result = engine.eval("program mytest;\n1 + 2;");
    assertEquals(3L, result);
  }

  // ---- Type Conversions ----

  @Test
  void convertInteger() throws ScriptException {
    engine.put("n", 10);
    final var result = engine.eval("n * 2;");
    assertEquals(20L, result);
  }

  @Test
  void convertDouble() throws ScriptException {
    engine.put("d", 2.5);
    final var result = engine.eval("d + 0.5;");
    assertEquals(3.0, result);
  }

  @Test
  void convertList() throws ScriptException {
    final var result =
        engine.eval(
            "program test;\nimport std;\nconst list<int> items = [1, 2, 3];\nstd:len(items);");
    assertEquals(3L, result);
  }

  @Test
  void returnList() throws ScriptException {
    final var result = engine.eval("program test;\n[1, 2, 3];");
    assertInstanceOf(List.class, result);
    assertEquals(List.of(1L, 2L, 3L), result);
  }

  @Test
  void returnMap() throws ScriptException {
    final var result = engine.eval("program test;\n{\"a\": 1};");
    assertInstanceOf(Map.class, result);
    final var map = (Map<?, ?>) result;
    assertEquals(1L, map.get("a"));
  }

  // ---- Compiled Scripts ----

  @Test
  void compiledScript() throws ScriptException {
    final var compiled = ((Compilable) engine).compile("program test;\n2 + 3;");
    final var result = compiled.eval();
    assertEquals(5L, result);
  }

  @Test
  void compiledScriptReuse() throws ScriptException {
    final var compiled = ((Compilable) engine).compile("program test;\n10 * 10;");
    assertEquals(100L, compiled.eval());
    assertEquals(100L, compiled.eval());
  }

  @Test
  void compiledScriptEngine() throws ScriptException {
    final var compiled = ((Compilable) engine).compile("program test;\n1;");
    assertSame(engine, compiled.getEngine());
  }

  // ---- Error Mapping ----

  @Test
  void parserError() {
    assertThrows(ScriptException.class, () -> engine.eval("program test;\n@@@;"));
  }

  @Test
  void semanticError() {
    assertThrows(ScriptException.class, () -> engine.eval("program test;\nundefined_var;"));
  }

  @Test
  void compileParserError() {
    assertThrows(ScriptException.class, () -> ((Compilable) engine).compile("program test;\n@@@;"));
  }

  // ---- Edge Cases ----

  @Test
  void emptyProgram() throws ScriptException {
    final var result = engine.eval("program test;");
    assertNull(result);
  }

  @Test
  void functionDeclarationAndCall() throws ScriptException {
    final var result =
        engine.eval(
            """
                program test;
                int add(int a, int b) {
                    return a + b;
                }
                add(3, 4);
                """);
    assertEquals(7L, result);
  }

  @Test
  void factoryGetParameter() {
    final var factory = engine.getFactory();
    assertEquals("SAFE Language Engine", factory.getParameter(ScriptEngine.ENGINE));
    assertEquals("1.0", factory.getParameter(ScriptEngine.ENGINE_VERSION));
    assertEquals("SAFE", factory.getParameter(ScriptEngine.LANGUAGE));
    assertEquals("1.0", factory.getParameter(ScriptEngine.LANGUAGE_VERSION));
    assertEquals("safe", factory.getParameter(ScriptEngine.NAME));
    assertNull(factory.getParameter("nonexistent"));
  }

  // ---- Phase 7: deferred binding resolution + exit handling ----

  @Test
  void compileWithDeferredBinding() throws ScriptException {
    // Phase 7: compile() does NOT snapshot the engine's current bindings.
    // The script references `x`, but `x` is supplied via the eval context
    // AFTER compilation. Before Phase 7, this threw a SemanticException
    // ("Undefined variable: x") at compile time.
    final var compiled = ((Compilable) engine).compile("int y = x + 1; y;");
    final var ctx = new SimpleScriptContext();
    final var bindings = new SimpleBindings();
    bindings.put("x", 5);
    ctx.setBindings(bindings, ScriptContext.ENGINE_SCOPE);
    assertEquals(6L, compiled.eval(ctx));
  }

  @Test
  void compileEvalManyTimesWithDifferentBindings() throws ScriptException {
    // Compile once, eval multiple times with different bindings — the
    // canonical JSR-223 use case.
    final var compiled = ((Compilable) engine).compile("int y = x * 2; y;");
    final var ctx1 = new SimpleScriptContext();
    final var b1 = new SimpleBindings();
    b1.put("x", 3);
    ctx1.setBindings(b1, ScriptContext.ENGINE_SCOPE);
    assertEquals(6L, compiled.eval(ctx1));

    final var ctx2 = new SimpleScriptContext();
    final var b2 = new SimpleBindings();
    b2.put("x", 10);
    ctx2.setBindings(b2, ScriptContext.ENGINE_SCOPE);
    assertEquals(20L, compiled.eval(ctx2));
  }

  @Test
  void exitInScriptThrowsScriptExceptionWithExitCodeAsCause() {
    // Phase 7: exit(N) becomes a ScriptException whose cause is the
    // original ExitException. Embedders can pattern-match the cause to
    // read the exit code.
    final var thrown =
        assertThrows(
            ScriptException.class,
            () ->
                engine.eval(
                    """
        import std;
        std:exit(7);
        """));
    assertNotNull(thrown.getCause());
    assertInstanceOf(io.safelang.interpreter.ExitException.class, thrown.getCause());
    assertEquals(7, ((io.safelang.interpreter.ExitException) thrown.getCause()).code());
  }

  @Test
  void exitInCompiledScriptThrowsScriptExceptionWithExitCodeAsCause() throws ScriptException {
    // Same exit handling for the compiled-script path.
    final var compiled = ((Compilable) engine).compile("import std; std:exit(42);");
    final var thrown =
        assertThrows(ScriptException.class, () -> compiled.eval(new SimpleScriptContext()));
    assertNotNull(thrown.getCause());
    assertInstanceOf(io.safelang.interpreter.ExitException.class, thrown.getCause());
    assertEquals(42, ((io.safelang.interpreter.ExitException) thrown.getCause()).code());
  }
}
