package io.safelang.ast;

import java.util.*;

/** Represents an assignment statement. */
public record AssignmentNode(int line, int column, List<String> parts, ASTNode value)
    implements ASTNode {

  public AssignmentNode {
    parts = parts != null ? new ArrayList<>(parts) : new ArrayList<>();
  }

  @Override
  public <T> T accept(final ASTVisitor<T> visitor) {
    return visitor.visitAssignment(this);
  }
}
