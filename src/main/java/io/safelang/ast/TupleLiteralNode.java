package io.safelang.ast;

import java.util.*;
import java.util.ArrayList;

/** Represents a tuple literal: (expr1, expr2, ...) with 2+ elements. */
public record TupleLiteralNode(int line, int column, List<ASTNode> elements) implements ASTNode {

  public TupleLiteralNode {
    elements = elements != null ? new ArrayList<>(elements) : new ArrayList<>();
  }

  @Override
  public <T> T accept(final ASTVisitor<T> visitor) {
    return visitor.visitTupleLiteral(this);
  }
}
