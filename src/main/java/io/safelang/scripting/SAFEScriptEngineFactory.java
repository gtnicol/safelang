package io.safelang.scripting;

import java.util.List;
import javax.script.ScriptEngine;
import javax.script.ScriptEngineFactory;

public class SAFEScriptEngineFactory implements ScriptEngineFactory {

  @Override
  public String getEngineName() {
    return "SAFE Language Engine";
  }

  @Override
  public String getEngineVersion() {
    return "1.0";
  }

  @Override
  public List<String> getExtensions() {
    return List.of("safe");
  }

  @Override
  public List<String> getMimeTypes() {
    return List.of("application/x-safe");
  }

  @Override
  public List<String> getNames() {
    return List.of("safe", "SAFE", "safe-lang");
  }

  @Override
  public String getLanguageName() {
    return "SAFE";
  }

  @Override
  public String getLanguageVersion() {
    return "1.0";
  }

  @Override
  public Object getParameter(final String key) {
    return switch (key) {
      case ScriptEngine.ENGINE -> getEngineName();
      case ScriptEngine.ENGINE_VERSION -> getEngineVersion();
      case ScriptEngine.LANGUAGE -> getLanguageName();
      case ScriptEngine.LANGUAGE_VERSION -> getLanguageVersion();
      case ScriptEngine.NAME -> getNames().get(0);
      default -> null;
    };
  }

  @Override
  public String getMethodCallSyntax(
      final String object, final String method, final String... arguments) {
    final var joined = String.join(", ", arguments);
    return object + ":" + method + "(" + joined + ")";
  }

  @Override
  public String getOutputStatement(final String value) {
    final var escaped = value.replace("\\", "\\\\").replace("\"", "\\\"");
    return "io:println(\"" + escaped + "\")";
  }

  @Override
  public String getProgram(final String... statements) {
    final var builder = new StringBuilder();
    builder.append("program script;\n");
    for (final var statement : statements) {
      builder.append(statement);
      if (!statement.endsWith(";")) {
        builder.append(";");
      }
      builder.append("\n");
    }
    return builder.toString();
  }

  @Override
  public ScriptEngine getScriptEngine() {
    return new SAFEScriptEngine(this);
  }
}
