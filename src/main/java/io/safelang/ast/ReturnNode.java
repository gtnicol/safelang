package io.safelang.ast;

/** Represents a return statement. */
public record ReturnNode(int line, int column, ASTNode expression) implements ASTNode {

  public boolean hasExpression() {
    return expression != null;
  }

  @Override
  public <T> T accept(final ASTVisitor<T> visitor) {
    return visitor.visitReturn(this);
  }
}
