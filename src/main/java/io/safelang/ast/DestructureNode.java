package io.safelang.ast;

import java.util.List;

public record DestructureNode(
    int line, int column, TypeNode type, List<String> names, ASTNode initializer, boolean constant)
    implements ASTNode {

  public boolean isConstant() {
    return constant;
  }

  @Override
  public <T> T accept(final ASTVisitor<T> visitor) {
    return visitor.visitDestructure(this);
  }
}
