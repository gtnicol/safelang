package io.safelang.ast;

public record RangeNode(int line, int column, ASTNode start, ASTNode end, ASTNode step)
    implements ASTNode {

  public RangeNode(final int line, final int column, final ASTNode start, final ASTNode end) {
    this(line, column, start, end, null);
  }

  public boolean hasStep() {
    return step != null;
  }

  @Override
  public <T> T accept(final ASTVisitor<T> visitor) {
    return visitor.visitRange(this);
  }
}
