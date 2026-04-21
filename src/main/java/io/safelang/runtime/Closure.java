package io.safelang.runtime;

import io.safelang.ast.FunctionDeclarationNode;
import io.safelang.ast.LambdaNode;

/** Represents a captured function or lambda closure at runtime. */
public record Closure(
    String name,
    FunctionDeclarationNode declaration,
    LambdaNode lambda,
    Environment environment,
    SAFEValue[] captures) {
  /** Create a named function reference closure (interpreter) */
  public static Closure named(
      final String name, final FunctionDeclarationNode declaration, final Environment environment) {
    return new Closure(name, declaration, null, environment, null);
  }

  /** Create a lambda closure (interpreter) */
  public static Closure lambda(final LambdaNode lambda, final Environment environment) {
    return new Closure(null, null, lambda, environment, null);
  }

  /** Create a bytecode closure with captured values */
  public static Closure bytecode(final String name, final SAFEValue[] captures) {
    return new Closure(name, null, null, null, captures);
  }

  public boolean isNamed() {
    return name != null;
  }
}
