package io.safelang.ast;

import java.util.*;

/**
 * Represents a lambda expression: fn(x, y) -> x + y Parameters may have optional type annotations.
 */
public record LambdaNode(int line, int column, List<ParameterNode> parameters, ASTNode body)
    implements ASTNode {

  public LambdaNode {
    parameters = parameters != null ? new ArrayList<>(parameters) : new ArrayList<>();
  }

  @Override
  public <T> T accept(final ASTVisitor<T> visitor) {
    return visitor.visitLambda(this);
  }
}
