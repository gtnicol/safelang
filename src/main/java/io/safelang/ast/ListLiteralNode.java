package io.safelang.ast;

import java.util.*;

/** Represents a list literal. */
public record ListLiteralNode(int line, int column, List<ASTNode> elements) implements ASTNode {

  public ListLiteralNode {
    elements = elements != null ? new ArrayList<>(elements) : new ArrayList<>();
  }

  @Override
  public <T> T accept(final ASTVisitor<T> visitor) {
    return visitor.visitListLiteral(this);
  }
}
