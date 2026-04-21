package io.safelang.ast;

import java.util.ArrayList;
import java.util.List;

/**
 * Represents a do-expression block: do { statements; expression } Executes statements, then
 * evaluates and returns the final expression.
 */
public record DoExpressionNode(int line, int column, List<ASTNode> statements, ASTNode expression)
    implements ASTNode {

  public DoExpressionNode {
    statements = statements != null ? new ArrayList<>(statements) : new ArrayList<>();
  }

  @Override
  public <T> T accept(final ASTVisitor<T> visitor) {
    return visitor.visitDoExpression(this);
  }
}
