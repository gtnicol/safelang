package io.safelang.ast;

import java.util.*;

/** Represents object creation (constructor call). */
public record ObjectCreationNode(
    int line, int column, String type, List<FieldAssignmentNode> fields) implements ASTNode {

  public ObjectCreationNode {
    fields = fields != null ? new ArrayList<>(fields) : new ArrayList<>();
  }

  @Override
  public <T> T accept(final ASTVisitor<T> visitor) {
    return visitor.visitObjectCreation(this);
  }
}
