package io.safelang.ast;

/** Represents an if expression. */
public record IfExpressionNode(
    int line, int column, ASTNode condition, ASTNode then, ASTNode otherwise) implements ASTNode {

  public boolean hasOtherwise() {
    return otherwise != null;
  }

  @Override
  public <T> T accept(final ASTVisitor<T> visitor) {
    return visitor.visitIfExpression(this);
  }
}
