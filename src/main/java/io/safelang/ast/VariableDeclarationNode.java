package io.safelang.ast;

public record VariableDeclarationNode(
    int line,
    int column,
    TypeNode type,
    String name,
    ASTNode initializer,
    boolean isConst,
    boolean isPublic)
    implements ASTNode {

  public VariableDeclarationNode(
      final int line,
      final int column,
      final TypeNode type,
      final String name,
      final ASTNode initializer,
      final boolean isConst) {
    this(line, column, type, name, initializer, isConst, false);
  }

  public VariableDeclarationNode(
      final int line,
      final int column,
      final TypeNode type,
      final String name,
      final ASTNode initializer) {
    this(line, column, type, name, initializer, false, false);
  }

  public boolean hasInitializer() {
    return initializer != null;
  }

  public boolean isConstant() {
    return isConst;
  }

  @Override
  public <T> T accept(final ASTVisitor<T> visitor) {
    return visitor.visitVariableDeclaration(this);
  }
}
