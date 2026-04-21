package io.safelang.ast;

/** Represents a field assignment within object creation. */
public record FieldAssignmentNode(int line, int column, String field, ASTNode value)
    implements ASTNode {

  @Override
  public <T> T accept(final ASTVisitor<T> visitor) {
    return visitor.visitFieldAssignment(this);
  }
}
