package io.safelang.ast;

import java.util.*;
import java.util.ArrayList;

public record IndexAssignmentNode(
    int line, int column, ASTNode container, List<ASTNode> indices, ASTNode value)
    implements ASTNode {

  public IndexAssignmentNode {
    indices = indices != null ? new ArrayList<>(indices) : new ArrayList<>();
  }

  @Override
  public <T> T accept(final ASTVisitor<T> visitor) {
    return visitor.visitIndexAssignment(this);
  }
}
