package io.safelang.ast;

public record FieldDeclarationNode(
    int line, int column, TypeNode type, String name, boolean isConst, boolean isPublic)
    implements ASTNode {

  public FieldDeclarationNode(
      final int line, final int column, final TypeNode type, final String name) {
    this(line, column, type, name, false, false);
  }

  @Override
  public <T> T accept(final ASTVisitor<T> visitor) {
    return visitor.visitFieldDeclaration(this);
  }
}
