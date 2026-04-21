package io.safelang.ast;

public record AssertNode(int line, int column, ASTNode condition, ASTNode message)
    implements ASTNode {

  public boolean hasMessage() {
    return message != null;
  }

  @Override
  public <T> T accept(final ASTVisitor<T> visitor) {
    return visitor.visitAssert(this);
  }
}
