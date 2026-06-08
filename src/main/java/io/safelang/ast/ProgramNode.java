package io.safelang.ast;

import java.util.*;

public record ProgramNode(
    int line,
    int column,
    String header,
    String name,
    List<ImportNode> imports,
    List<ASTNode> declarations,
    List<ASTNode> statements)
    implements ASTNode {

  public ProgramNode {
    imports = imports != null ? List.copyOf(imports) : List.of();
    declarations = declarations != null ? List.copyOf(declarations) : List.of();
    statements = statements != null ? List.copyOf(statements) : List.of();
  }

  @Override
  public <T> T accept(final ASTVisitor<T> visitor) {
    return visitor.visitProgram(this);
  }
}
