package io.safelang.ast;

/** Represents a binary expression (e.g., a + b, a || b, a && b). */
public record BinaryExpressionNode(
    int line, int column, ASTNode left, String operator, ASTNode right) implements ASTNode {

  @Override
  public <T> T accept(final ASTVisitor<T> visitor) {
    return visitor.visitBinaryExpression(this);
  }
}
