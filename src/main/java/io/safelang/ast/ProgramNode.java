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
    imports = imports != null ? imports : new ArrayList<>();
    declarations = declarations != null ? declarations : new ArrayList<>();
    statements = statements != null ? statements : new ArrayList<>();
  }

  @Override
  public <T> T accept(final ASTVisitor<T> visitor) {
    return visitor.visitProgram(this);
  }
}
