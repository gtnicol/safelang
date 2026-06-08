package io.safelang.ast;

import java.util.*;

/** Represents a function declaration with optional contracts and visibility. */
public record FunctionDeclarationNode(
    int line,
    int column,
    TypeNode returns,
    String name,
    List<ParameterNode> parameters,
    ASTNode requires,
    ASTNode ensures,
    ASTNode decreases,
    List<ASTNode> body,
    boolean isPublic)
    implements ASTNode {

  public FunctionDeclarationNode {
    parameters = parameters != null ? List.copyOf(parameters) : List.of();
    body = body != null ? List.copyOf(body) : List.of();
  }

  public FunctionDeclarationNode(
      final int line,
      final int column,
      final TypeNode returns,
      final String name,
      final List<ParameterNode> parameters,
      final List<ASTNode> body) {
    this(line, column, returns, name, parameters, null, null, null, body, false);
  }

  public boolean hasRequires() {
    return requires != null;
  }

  public boolean hasEnsures() {
    return ensures != null;
  }

  public boolean hasDecreases() {
    return decreases != null;
  }

  @Override
  public <T> T accept(final ASTVisitor<T> visitor) {
    return visitor.visitFunctionDeclaration(this);
  }
}
