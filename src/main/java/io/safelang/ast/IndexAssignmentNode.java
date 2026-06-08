package io.safelang.ast;

import java.util.*;

public record IndexAssignmentNode(
    int line, int column, ASTNode container, List<ASTNode> indices, ASTNode value)
    implements ASTNode {

  public IndexAssignmentNode {
    indices = indices != null ? List.copyOf(indices) : List.of();
  }

  @Override
  public <T> T accept(final ASTVisitor<T> visitor) {
    return visitor.visitIndexAssignment(this);
  }
}
