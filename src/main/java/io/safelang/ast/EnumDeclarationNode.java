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
    parameters = parameters != null ? List.copyOf(parameters) : List.of();
    variants = variants != null ? List.copyOf(variants) : List.of();
  }

  @Override
  public <T> T accept(final ASTVisitor<T> visitor) {
    return visitor.visitEnumDeclaration(this);
  }
}
