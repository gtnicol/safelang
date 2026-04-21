package io.safelang.runtime;

import io.safelang.ast.EnumDeclarationNode;
import io.safelang.ast.FunctionDeclarationNode;
import io.safelang.ast.TypeDeclarationNode;
import io.safelang.interpreter.InterpreterException;
import java.util.*;
import java.util.function.Function;

/**
 * Scoped symbol table with parent chain for lexical scoping. Not thread-safe.
 *
 * <p><b>Why this is not unified with {@link io.safelang.analyzer.TypeEnvironment}.</b> The two
 * classes share a parent-chain shape but their value types and responsibilities are different:
 *
 * <ul>
 *   <li>{@code Environment} (here) stores runtime {@link SAFEValue}s plus dedicated maps for
 *       function declarations, type declarations, and enum declarations. It is consumed by the
 *       interpreter and the bytecode VM at execution time.
 *   <li>{@code TypeEnvironment} stores compile-time {@link io.safelang.ast.TypeNode}s plus a "used"
 *       tracker that drives the analyzer's unused-variable warning. It is consumed by the semantic
 *       analyzer at type-checking time.
 * </ul>
 *
 * <p>A shared abstract base would have to genericize over the value type and the auxiliary state,
 * which would erase the strong typing of the runtime accessors and add complexity without removing
 * meaningful duplication (each class is ~70 lines). The third audit round deliberately leaves them
 * separate; the monster classes ({@code CCodeGenerator}, {@code WasmCompiler}, {@code Interpreter},
 * {@code SemanticAnalyzer}, {@code TerminationChecker}) are likewise out of scope for this round.
 */
public class Environment {
  private final Map<String, SAFEValue> variables;
  private final Set<String> constants;
  private final Map<String, FunctionDeclarationNode> functions;
  private final Map<String, TypeDeclarationNode> types;
  private final Map<String, EnumDeclarationNode> enums;
  private final Environment parent;

  public Environment() {
    this(null);
  }

  public Environment(final Environment parent) {
    this.variables = new HashMap<>();
    this.constants = new HashSet<>();
    this.functions = new HashMap<>();
    this.types = new HashMap<>();
    this.enums = new HashMap<>();
    this.parent = parent;
  }

  private <T> T lookup(final Function<Environment, Map<String, T>> selector, final String name) {
    var env = this;
    while (env != null) {
      final var map = selector.apply(env);
      if (map.containsKey(name)) return map.get(name);
      env = env.parent;
    }
    return null;
  }

  private boolean exists(final Function<Environment, Map<String, ?>> selector, final String name) {
    var env = this;
    while (env != null) {
      if (selector.apply(env).containsKey(name)) return true;
      env = env.parent;
    }
    return false;
  }

  private boolean member(final Function<Environment, Set<String>> selector, final String name) {
    var env = this;
    while (env != null) {
      if (selector.apply(env).contains(name)) return true;
      env = env.parent;
    }
    return false;
  }

  /** Define a mutable variable in the current scope. Copies mutable values to prevent aliasing. */
  public void define(final String name, final SAFEValue value) {
    variables.put(name, value.copy());
  }

  /** Define a constant variable in the current scope. Copies mutable values to prevent aliasing. */
  public void constant(final String name, final SAFEValue value) {
    variables.put(name, value.copy());
    constants.add(name);
  }

  /** Check if a variable is constant, searching the parent chain. */
  public boolean isConst(final String name) {
    return member(e -> e.constants, name);
  }

  /**
   * Get a variable value, searching the parent chain. Throws {@link InterpreterException} if
   * variable not found.
   */
  public SAFEValue get(final String name) {
    final var result = lookup(e -> e.variables, name);
    if (result != null) return result;
    throw new InterpreterException("Undefined variable: " + name);
  }

  /** Check if a variable exists, searching the parent chain. */
  public boolean has(final String name) {
    return exists(e -> e.variables, name);
  }

  /** Set a variable value, searching the parent chain. Throws if the variable is const. */
  public void set(final String name, final SAFEValue value) {
    if (isConst(name)) {
      throw new InterpreterException("Cannot reassign const variable: " + name);
    }
    if (variables.containsKey(name)) {
      variables.put(name, value.copy());
    } else if (parent != null) {
      parent.set(name, value);
    } else {
      throw new InterpreterException("Undefined variable: " + name);
    }
  }

  /** Define a function declaration in the current scope. */
  public void define(final String name, final FunctionDeclarationNode function) {
    functions.put(name, function);
  }

  /** Get a function declaration, searching the parent chain. Returns null if not found. */
  public FunctionDeclarationNode function(final String name) {
    return lookup(e -> e.functions, name);
  }

  /** Define a type declaration in the current scope. */
  public void define(final String name, final TypeDeclarationNode type) {
    types.put(name, type);
  }

  /** Get a type declaration, searching the parent chain. Returns null if not found. */
  public TypeDeclarationNode type(final String name) {
    return lookup(e -> e.types, name);
  }

  /** Define an enum declaration in the current scope. */
  public void define(final String name, final EnumDeclarationNode declaration) {
    enums.put(name, declaration);
  }

  /** Get an enum declaration, searching the parent chain. */
  public EnumDeclarationNode enumeration(final String name) {
    return lookup(e -> e.enums, name);
  }

  /** Find the enum declaration that contains a variant with the given name. */
  public EnumDeclarationNode variant(final String variant) {
    for (final var entry : enums.values()) {
      for (final var v : entry.variants()) {
        if (v.name().equals(variant)) {
          return entry;
        }
      }
    }
    if (parent != null) {
      return parent.variant(variant);
    }
    return null;
  }

  /** Create a child scope. */
  public Environment child() {
    return new Environment(this);
  }

  /**
   * Create a flat snapshot of the entire scope chain, capturing all bindings by value into a
   * single-level environment. Used for closure capture-by-value.
   */
  public Environment snapshot() {
    final var result = new Environment();
    // Collect all scopes from outermost to innermost
    final var chain = new ArrayList<Environment>();
    var env = this;
    while (env != null) {
      chain.add(env);
      env = env.parent;
    }
    // Apply from outermost to innermost so inner scopes shadow outer
    // Copy mutable values to ensure capture-by-value semantics
    for (int i = chain.size() - 1; i >= 0; i--) {
      final var scope = chain.get(i);
      for (final var entry : scope.variables.entrySet()) {
        result.variables.put(entry.getKey(), entry.getValue().copy());
      }
      result.constants.addAll(scope.constants);
      result.functions.putAll(scope.functions);
      result.types.putAll(scope.types);
      result.enums.putAll(scope.enums);
    }
    return result;
  }
}
