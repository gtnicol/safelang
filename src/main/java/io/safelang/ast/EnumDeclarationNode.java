package io.safelang.ast;

import java.util.*;

public record EnumDeclarationNode(
    int line,
    int column,
    String name,
    List<String> parameters,
    List<EnumVariantNode> variants,
    boolean isPublic)
    implements ASTNode {

  public EnumDeclarationNode {
    parameters = parameters != null ? parameters : new ArrayList<>();
    variants = variants != null ? variants : new ArrayList<>();
  }

  @Override
  public <T> T accept(final ASTVisitor<T> visitor) {
    return visitor.visitEnumDeclaration(this);
  }
}
