package io.safelang.ast;

public record ParameterNode(
    int line, int column, TypeNode type, String name, boolean isConst, ASTNode initial)
    implements ASTNode {

  public ParameterNode(final int line, final int column, final TypeNode type, final String name) {
    this(line, column, type, name, false, null);
  }

  public ParameterNode(
      final int line,
      final int column,
      final TypeNode type,
      final String name,
      final boolean isConst) {
    this(line, column, type, name, isConst, null);
  }

  public boolean hasDefault() {
    return initial != null;
  }

  @Override
  public <T> T accept(final ASTVisitor<T> visitor) {
    return visitor.visitParameter(this);
  }
}
