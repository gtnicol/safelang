package io.safelang.scripting;

import io.safelang.ModuleRegistry;
import io.safelang.SAFEException;
import io.safelang.ast.ProgramNode;
import io.safelang.interpreter.ExitException;
import io.safelang.interpreter.InterpreterException;
import javax.script.CompiledScript;
import javax.script.ScriptContext;
import javax.script.ScriptEngine;
import javax.script.ScriptException;

public class SAFECompiledScript extends CompiledScript {

  private final SAFEScriptEngine engine;
  private final ProgramNode program;
  private final ModuleRegistry registry;

  SAFECompiledScript(
      final SAFEScriptEngine engine, final ProgramNode program, final ModuleRegistry registry) {
    this.engine = engine;
    this.program = program;
    this.registry = registry;
  }

  @Override
  public Object eval(final ScriptContext context) throws ScriptException {
    try {
      return engine.execute(program, registry, context);
    } catch (final ExitException exit) {
      // exit(N) becomes a typed ScriptException with the original exit
      // exception as the cause — embedders can read the code via
      // ((ExitException) e.getCause()).code().
      final var translated = new ScriptException("SAFE program called exit(" + exit.code() + ")");
      translated.initCause(exit);
      throw translated;
    } catch (final InterpreterException exception) {
      throw new ScriptException(exception.getMessage());
    } catch (final SAFEException exception) {
      // Any other SAFE-typed runtime error (e.g. ModuleException, value
      // errors) — translate to a ScriptException so the JSR-223 contract
      // holds for embedders.
      final var translated = new ScriptException(exception.getMessage());
      translated.initCause(exception);
      throw translated;
    }
  }

  @Override
  public ScriptEngine getEngine() {
    return engine;
  }
}
