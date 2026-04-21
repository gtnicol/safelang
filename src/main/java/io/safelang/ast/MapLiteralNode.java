package io.safelang.ast;

import java.util.*;

public record MapLiteralNode(int line, int column, List<MapEntryNode> entries) implements ASTNode {

  public MapLiteralNode {
    entries = entries != null ? new ArrayList<>(entries) : new ArrayList<>();
  }

  @Override
  public <T> T accept(final ASTVisitor<T> visitor) {
    return visitor.visitMapLiteral(this);
  }
}
