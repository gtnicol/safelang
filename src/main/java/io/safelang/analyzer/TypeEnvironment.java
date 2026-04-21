package io.safelang.analyzer;

import io.safelang.ast.TypeNode;
import java.util.*;

/**
 * Scoped type-checking environment with parent chain for lexical scoping. Stores compile-time
 * {@link TypeNode}s and a "used" tracker that drives the unused-variable warning.
 *
 * <p><b>Why this is not unified with {@link io.safelang.runtime.Environment}.</b> The two classes
 * share a parent-chain shape but their value types and responsibilities are different:
 *
 * <ul>
 *   <li>{@code Environment} (runtime) stores runtime {@code SAFEValue}s plus dedicated maps for
 *       function/type/enum declarations. It serves the interpreter and bytecode VM.
 *   <li>{@code TypeEnvironment} (here) stores compile-time {@code TypeNode}s plus a "used" tracker.
 *       It serves the semantic analyzer.
 * </ul>
 *
 * <p>A shared abstract base would have to genericize over the value type and the auxiliary state,
 * which would erase the strong typing of each class's accessors and add complexity without removing
 * meaningful duplication (each class is ~70 lines). The third audit round deliberately leaves them
 * separate.
 */
public class TypeEnvironment {
  private final Map<String, TypeNode> variables = new HashMap<>();
  private final Set<String> constants = new HashSet<>();
  private final Set<String> used = new HashSet<>();
  private final TypeEnvironment parent;

  public TypeEnvironment() {
    this(null);
  }

  public TypeEnvironment(final TypeEnvironment parent) {
    this.parent = parent;
  }

  public void define(final String name, final TypeNode type, final boolean constant) {
    variables.put(name, type);
    if (constant) {
      constants.add(name);
    }
  }

  public TypeNode variable(final String name) {
    if (variables.containsKey(name)) {
      return variables.get(name);
    }
    if (parent != null) {
      return parent.variable(name);
    }
    return null;
  }

  public boolean isConst(final String name) {
    if (variables.containsKey(name)) {
      return constants.contains(name);
    }
    if (parent != null) {
      return parent.isConst(name);
    }
    return false;
  }

  public boolean has(final String name) {
    if (variables.containsKey(name)) {
      return true;
    }
    if (parent != null) {
      return parent.has(name);
    }
    return false;
  }

  public void markUsed(final String name) {
    if (variables.containsKey(name)) {
      used.add(name);
    } else if (parent != null) {
      parent.markUsed(name);
    }
  }

  public Set<String> unused() {
    final var result = new HashSet<>(variables.keySet());
    result.removeAll(used);
    return result;
  }

  public TypeEnvironment child() {
    return new TypeEnvironment(this);
  }
}
