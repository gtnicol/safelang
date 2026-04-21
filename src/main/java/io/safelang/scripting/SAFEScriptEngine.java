package io.safelang.scripting;

import io.safelang.ModuleRegistry;
import io.safelang.SafeFrontend;
import io.safelang.analyzer.SemanticException;
import io.safelang.ast.ProgramNode;
import io.safelang.compiler.CompilerFrontEnd;
import io.safelang.interpreter.ExitException;
import io.safelang.interpreter.Interpreter;
import io.safelang.interpreter.InterpreterException;
import io.safelang.parser.ParserException;
import io.safelang.parser.SAFEParser;
import io.safelang.runtime.SAFEValue;
import java.io.IOException;
import java.io.Reader;
import java.util.*;
import javax.script.*;

public class SAFEScriptEngine extends AbstractScriptEngine implements Compilable {

  private final SAFEScriptEngineFactory factory;

  SAFEScriptEngine(final SAFEScriptEngineFactory factory) {
    this.factory = factory;
  }

  static SAFEValue convert(final Object value) {
    switch (value) {
      case null -> {
        return SAFEValue.ofVoid();
      }
      case SAFEValue safeValue -> {
        return safeValue;
      }
      case Integer i -> {
        return SAFEValue.ofInt(i);
      }
      case Long l -> {
        return SAFEValue.ofInt(l);
      }
      case Double v -> {
        return SAFEValue.ofFloat(v);
      }
      case Float v -> {
        return SAFEValue.ofFloat(v);
      }
      case String s -> {
        return SAFEValue.ofString(s);
      }
      case Boolean b -> {
        return SAFEValue.ofBoolean(b);
      }
      case List<?> objects -> {
        final var items = new ArrayList<SAFEValue>();
        for (final var item : objects) {
          items.add(convert(item));
        }
        return SAFEValue.ofList(items);
      }
      case Map<?, ?> map1 -> {
        final var map = new LinkedHashMap<SAFEValue, SAFEValue>();
        for (final var entry : map1.entrySet()) {
          map.put(convert(entry.getKey()), convert(entry.getValue()));
        }
        return SAFEValue.ofMap(map);
      }
      default -> {}
    }
    throw new IllegalArgumentException(
        "Cannot convert " + value.getClass().getName() + " to SAFEValue");
  }

  static Object unconvert(final SAFEValue value) {
    if (value == null) {
      return null;
    }
    return switch (value) {
      case SAFEValue.VoidValue ignored -> null;
      case SAFEValue.IntValue i -> i.value();
      case SAFEValue.UintValue u -> u.value();
      case SAFEValue.FloatValue f -> f.value();
      case SAFEValue.StringValue s -> s.value();
      case SAFEValue.BoolValue b -> b.value();
      case SAFEValue.ListValue list -> {
        final var result = new ArrayList<>();
        for (final var item : list.asList()) result.add(unconvert(item));
        yield result;
      }
      case SAFEValue.TupleValue tuple -> {
        final var result = new ArrayList<>();
        for (final var item : tuple.asTuple()) result.add(unconvert(item));
        yield result;
      }
      case SAFEValue.MapValue map -> {
        final var result = new LinkedHashMap<>();
        for (final var entry : map.asMap().entrySet()) {
          result.put(unconvert(entry.getKey()), unconvert(entry.getValue()));
        }
        yield result;
      }
      case SAFEValue.SetValue set -> {
        final var result = new LinkedHashSet<>();
        for (final var item : set.asSet()) result.add(unconvert(item));
        yield result;
      }
      case SAFEValue.BytesValue bytes -> bytes.value();
      case SAFEValue.EnumValue ignored -> value;
      case SAFEValue.ObjectValue ignored -> value;
      case SAFEValue.FunctionValue ignored -> value;
    };
  }

  /**
   * Pre-walk a parsed script and add every identifier referenced as a variable-reference head into
   * {@code into}. The analyzer checks local scope and the function/type/enum registries before
   * falling back to the external set, so adding ALL referenced names here is safe — names that ARE
   * locally defined still resolve to their local binding.
   */
  private static void collectReferences(final ProgramNode program, final Set<String> into) {
    final var collector = new IdentifierCollector(into);
    program.accept(collector);
  }

  @Override
  public Object eval(final String script, final ScriptContext context) throws ScriptException {
    try {
      final var result = bootstrap(script, bindings(context));
      return execute(result.program(), result.registry(), context);
    } catch (final ExitException exit) {
      // exit(N) inside the script becomes a typed ScriptException whose
      // cause is the original ExitException — embedders can pattern-match
      // the cause to read the exit code instead of parsing the message.
      final var translated = new ScriptException("SAFE program called exit(" + exit.code() + ")");
      translated.initCause(exit);
      throw translated;
    } catch (ParserException | SemanticException | InterpreterException exception) {
      throw new ScriptException(exception.getMessage());
    }
  }

  /** Run the shared frontend pipeline for a script with the given bindings. */
  private CompilerFrontEnd.ParseResult bootstrap(final String script, final Set<String> bindings) {
    final var options = SafeFrontend.Options.defaults().withBindings(bindings);
    return SafeFrontend.bootstrap(wrap(script), options);
  }

  @Override
  public Object eval(final Reader reader, final ScriptContext context) throws ScriptException {
    try {
      final var builder = new StringBuilder();
      final var buffer = new char[4096];
      int read;
      while ((read = reader.read(buffer)) != -1) {
        builder.append(buffer, 0, read);
      }
      return eval(builder.toString(), context);
    } catch (IOException exception) {
      throw new ScriptException("Failed to read script: " + exception.getMessage());
    }
  }

  @Override
  public Bindings createBindings() {
    return new SimpleBindings();
  }

  @Override
  public ScriptEngineFactory getFactory() {
    return factory;
  }

  @Override
  public CompiledScript compile(final String script) throws ScriptException {
    try {
      // JSR-223 contract: compile once, eval many times with different
      // bindings. We can't snapshot the engine's CURRENT bindings here
      // because the eventual eval(context) call may supply different ones.
      // Instead, pre-walk the script to collect every identifier reference
      // and treat them all as `external` (allowed-but-typeless) during
      // semantic analysis. The actual binding lookup happens at eval time.
      final var wrapped = wrap(script);
      final var program = SAFEParser.parse(wrapped);
      final var external = new HashSet<>(bindings(getContext()));
      collectReferences(program, external);
      final var options = SafeFrontend.Options.defaults().withBindings(external);
      // Re-bootstrap with the augmented bindings — the analyzer accepts any
      // unresolved identifier in the external set instead of erroring.
      // We pass the already-parsed program by re-feeding the wrapped source;
      // SafeFrontend re-parses internally which is cheap for a script.
      final var result = SafeFrontend.bootstrap(wrapped, options);
      return new SAFECompiledScript(this, result.program(), result.registry());
    } catch (ParserException | SemanticException exception) {
      throw new ScriptException(exception.getMessage());
    }
  }

  @Override
  public CompiledScript compile(final Reader reader) throws ScriptException {
    try {
      final var builder = new StringBuilder();
      final var buffer = new char[4096];
      int read;
      while ((read = reader.read(buffer)) != -1) {
        builder.append(buffer, 0, read);
      }
      return compile(builder.toString());
    } catch (IOException exception) {
      throw new ScriptException("Failed to read script: " + exception.getMessage());
    }
  }

  Object execute(
      final ProgramNode program, final ModuleRegistry registry, final ScriptContext context) {
    final var interpreter = new Interpreter();
    interpreter.setRegistry(registry);

    final var writer = context.getWriter();
    if (writer != null) {
      interpreter.setOutput(writer);
    }

    final var names = bindings(context);
    for (final var name : names) {
      // convert(null) returns SAFE void, so null bindings are a legitimate
      // signal from the embedder and must reach the interpreter; silently
      // dropping them would surface as an "undefined variable" error.
      interpreter.bind(name, convert(lookup(name, context)));
    }

    // Don't catch ExitException here — let SAFECompiledScript.eval translate
    // it to a typed ScriptException with the exit code, so embedders can
    // distinguish "program exited" from "program crashed".
    final var result = interpreter.interpret(program);
    sync(interpreter, names, context);
    return unconvert(result);
  }

  private String wrap(final String script) {
    final var trimmed = script.strip();
    if (trimmed.startsWith("program ") || trimmed.startsWith("module ")) {
      return script;
    }
    // Prepend program header; leave the original text intact so multiline
    // strings and other syntax are not corrupted by line-level rewriting.
    return "program script;\n" + script;
  }

  private Set<String> bindings(final ScriptContext context) {
    final var names = new HashSet<String>();
    final var engine = context.getBindings(ScriptContext.ENGINE_SCOPE);
    if (engine != null) {
      names.addAll(engine.keySet());
    }
    final var global = context.getBindings(ScriptContext.GLOBAL_SCOPE);
    if (global != null) {
      names.addAll(global.keySet());
    }
    return names;
  }

  private Object lookup(final String name, final ScriptContext context) {
    final var engine = context.getBindings(ScriptContext.ENGINE_SCOPE);
    if (engine != null && engine.containsKey(name)) {
      return engine.get(name);
    }
    final var global = context.getBindings(ScriptContext.GLOBAL_SCOPE);
    if (global != null && global.containsKey(name)) {
      return global.get(name);
    }
    return null;
  }

  private void sync(
      final Interpreter interpreter, final Set<String> names, final ScriptContext context) {
    final var global = interpreter.global();
    final var engine = context.getBindings(ScriptContext.ENGINE_SCOPE);
    if (engine == null) {
      return;
    }
    for (final var name : names) {
      if (global.has(name)) {
        engine.put(name, unconvert(global.get(name)));
      }
    }
  }

  /**
   * AST walker that records every {@link VariableReferenceNode}'s head name. Used by {@link
   * #compile(String)} to populate the analyzer's external set for late-bound JSR-223 bindings.
   *
   * <p>Inherits full-tree recursion from {@link io.safelang.ast.TraversingASTVisitor}, so a new
   * node kind becomes visible automatically — no silent drops when the grammar grows.
   */
  private static final class IdentifierCollector
      extends io.safelang.ast.TraversingASTVisitor<Void> {

    private final Set<String> into;

    IdentifierCollector(final Set<String> into) {
      this.into = into;
    }

    @Override
    public Void visitVariableReference(final io.safelang.ast.VariableReferenceNode node) {
      if (!node.parts().isEmpty()) {
        into.add(node.parts().getFirst());
      }
      return null;
    }

    @Override
    public Void visitAssignment(final io.safelang.ast.AssignmentNode node) {
      // An assignment `x.y = ...` references `x` (head of the target) as an
      // external binding, in addition to whatever appears on the RHS.
      if (!node.parts().isEmpty()) {
        into.add(node.parts().getFirst());
      }
      return super.visitAssignment(node);
    }
  }
}
