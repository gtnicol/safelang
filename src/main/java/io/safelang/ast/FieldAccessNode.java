package io.safelang.ast;

/** Represents field access on an expression (e.g., obj.name, items[0].field). */
public record FieldAccessNode(int line, int column, ASTNode receiver, String field)
    implements ASTNode {

  @Override
  public <T> T accept(final ASTVisitor<T> visitor) {
    return visitor.visitFieldAccess(this);
  }
}
