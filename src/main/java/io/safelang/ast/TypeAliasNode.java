package io.safelang.ast;

/** Represents a type alias declaration: type Name = ExistingType; */
public record TypeAliasNode(int line, int column, String name, TypeNode target, boolean visible)
    implements ASTNode {

  public boolean isPublic() {
    return visible;
  }

  @Override
  public <T> T accept(final ASTVisitor<T> visitor) {
    return visitor.visitTypeAlias(this);
  }
}
