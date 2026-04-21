package io.safelang.ast;

public record CaseBranchNode(
    int line, int column, ASTNode pattern, ASTNode result, boolean isWildcard, ASTNode guard)
    implements ASTNode {

  public CaseBranchNode(
      final int line, final int column, final ASTNode pattern, final ASTNode result) {
    this(line, column, pattern, result, false, null);
  }

  public CaseBranchNode(
      final int line,
      final int column,
      final ASTNode pattern,
      final ASTNode result,
      final boolean isWildcard) {
    this(line, column, pattern, result, isWildcard, null);
  }

  public boolean hasGuard() {
    return guard != null;
  }

  @Override
  public <T> T accept(final ASTVisitor<T> visitor) {
    return visitor.visitCaseBranch(this);
  }
}
