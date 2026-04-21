package io.safelang.ast;

public record MapEntryNode(int line, int column, ASTNode key, ASTNode value) implements ASTNode {

  @Override
  public <T> T accept(final ASTVisitor<T> visitor) {
    return visitor.visitMapEntry(this);
  }
}
