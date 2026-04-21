package io.safelang.ast;

import java.util.*;

/**
 * Represents a type reference — primitive, qualified, or generic/parameterized. Examples: int,
 * string, list<int>, map<string, float>, Result<T, E>
 */
public record TypeNode(
    int line,
    int column,
    String name,
    List<String> parts,
    boolean isQualified,
    List<TypeNode> parameters,
    boolean variable,
    List<TypeNode> members)
    implements ASTNode {

  public TypeNode {
    parameters = parameters != null ? new ArrayList<>(parameters) : new ArrayList<>();
    members = members != null ? new ArrayList<>(members) : new ArrayList<>();
  }

  public TypeNode(final int line, final int column, final String name) {
    this(line, column, name, new ArrayList<>(), false, new ArrayList<>(), false, new ArrayList<>());
  }

  public TypeNode(final int line, final int column, final List<String> parts) {
    this(
        line,
        column,
        String.join(".", parts),
        new ArrayList<>(parts),
        true,
        new ArrayList<>(),
        false,
        new ArrayList<>());
  }

  public static TypeNode withParameters(
      final int line, final int column, final String name, final List<TypeNode> parameters) {
    return new TypeNode(
        line, column, name, new ArrayList<>(), false, parameters, false, new ArrayList<>());
  }

  public static TypeNode withVariable(final int line, final int column, final String name) {
    return new TypeNode(
        line, column, name, new ArrayList<>(), false, new ArrayList<>(), true, new ArrayList<>());
  }

  public static TypeNode withMembers(
      final int line, final int column, final List<TypeNode> members) {
    final var builder = new StringBuilder();
    for (int i = 0; i < members.size(); i++) {
      if (i > 0) builder.append("|");
      builder.append(members.get(i).fullName());
    }
    return new TypeNode(
        line,
        column,
        builder.toString(),
        new ArrayList<>(),
        false,
        new ArrayList<>(),
        false,
        members);
  }

  public boolean isParameterized() {
    return !parameters.isEmpty();
  }

  public boolean isVariable() {
    return variable;
  }

  public boolean isUnion() {
    return !members.isEmpty();
  }

  public boolean isTuple() {
    return "tuple".equals(name) && !parameters.isEmpty();
  }

  public boolean isFunction() {
    return "fn".equals(name) && !parameters.isEmpty();
  }

  /** For fn types: parameter types are all but last typeParameter; return type is last */
  public List<TypeNode> parameterTypes() {
    if (!isFunction() || parameters.size() < 1) return List.of();
    // Defensive snapshot — never expose a live view of the internal list.
    return new ArrayList<>(parameters.subList(0, parameters.size() - 1));
  }

  /** For fn types: return type is the last typeParameter */
  public TypeNode returnType() {
    if (!isFunction() || parameters.isEmpty()) return null;
    return parameters.get(parameters.size() - 1);
  }

  /**
   * Returns the full type name including parameters. e.g., "list<int>" or "map<string, float>" or
   * "Result<T, E>"
   */
  public String fullName() {
    if (!members.isEmpty()) {
      final var builder = new StringBuilder();
      for (int i = 0; i < members.size(); i++) {
        if (i > 0) builder.append("|");
        builder.append(members.get(i).fullName());
      }
      return builder.toString();
    }
    final var prefix = variable ? "?" : "";
    if (parameters.isEmpty()) {
      return prefix + name;
    }
    final var builder = new StringBuilder(prefix + name);
    builder.append("<");
    for (int i = 0; i < parameters.size(); i++) {
      if (i > 0) builder.append(", ");
      builder.append(parameters.get(i).fullName());
    }
    builder.append(">");
    return builder.toString();
  }

  @Override
  public <T> T accept(final ASTVisitor<T> visitor) {
    return visitor.visitType(this);
  }
}
