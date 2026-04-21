package io.safelang.ast;

public record IndexAccessNode(int line, int column, ASTNode container, ASTNode index)
    implements ASTNode {

  @Override
  public <T> T accept(final ASTVisitor<T> visitor) {
    return visitor.visitIndexAccess(this);
  }
}
