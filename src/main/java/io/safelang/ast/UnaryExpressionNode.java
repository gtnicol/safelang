package io.safelang.ast;

/** Represents a unary expression (e.g., !expr, -expr). */
public record UnaryExpressionNode(int line, int column, String operator, ASTNode operand)
    implements ASTNode {

  @Override
  public <T> T accept(final ASTVisitor<T> visitor) {
    return visitor.visitUnaryExpression(this);
  }
}
