package io.safelang.ast;

import java.util.*;

public record TypeDeclarationNode(
    int line,
    int column,
    String name,
    List<String> parameters,
    List<FieldDeclarationNode> fields,
    boolean isPublic)
    implements ASTNode {

  public TypeDeclarationNode {
    parameters = parameters != null ? parameters : new ArrayList<>();
    fields = fields != null ? fields : new ArrayList<>();
  }

  public TypeDeclarationNode(
      final int line,
      final int column,
      final String name,
      final List<FieldDeclarationNode> fields) {
    this(line, column, name, new ArrayList<>(), fields, false);
  }

  @Override
  public <T> T accept(final ASTVisitor<T> visitor) {
    return visitor.visitTypeDeclaration(this);
  }
}
