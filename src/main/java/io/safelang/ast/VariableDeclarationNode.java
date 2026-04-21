package io.safelang.ast;

public record VariableDeclarationNode(
    int line, int column, TypeNode type, String name, ASTNode initializer, boolean isConst)
    implements ASTNode {

  public VariableDeclarationNode(
      final int line,
      final int column,
      final TypeNode type,
      final String name,
      final ASTNode initializer) {
    this(line, column, type, name, initializer, false);
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
