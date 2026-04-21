package io.safelang.ast;

/** Represents an expression statement (wraps an expression as a statement). */
public record ExpressionStatementNode(int line, int column, ASTNode expression) implements ASTNode {

  @Override
  public <T> T accept(final ASTVisitor<T> visitor) {
    return visitor.visitExpressionStatement(this);
  }
}
