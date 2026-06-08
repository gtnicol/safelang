package io.safelang.ast;

import java.util.*;

/** Represents a case expression. */
public record CaseExpressionNode(
    int line, int column, ASTNode subject, List<CaseBranchNode> branches, ASTNode fallback)
    implements ASTNode {

  public CaseExpressionNode {
    branches = branches != null ? List.copyOf(branches) : List.of();
  }

  public boolean hasFallback() {
    return fallback != null;
  }

  @Override
  public <T> T accept(final ASTVisitor<T> visitor) {
    return visitor.visitCaseExpression(this);
  }
}
