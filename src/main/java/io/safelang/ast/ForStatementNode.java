package io.safelang.ast;

import java.util.*;

/** Represents a for loop statement. */
public record ForStatementNode(
    int line, int column, String variable, ASTNode iterable, List<ASTNode> body)
    implements ASTNode {

  public ForStatementNode {
    body = body != null ? new ArrayList<>(body) : new ArrayList<>();
  }

  @Override
  public <T> T accept(final ASTVisitor<T> visitor) {
    return visitor.visitForStatement(this);
  }
}
