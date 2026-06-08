package io.safelang.ast;

import java.util.*;

/** Represents a set literal: #{1, 2, 3} */
public record SetLiteralNode(int line, int column, List<ASTNode> elements) implements ASTNode {

  public SetLiteralNode {
    elements = elements != null ? List.copyOf(elements) : List.of();
  }

  @Override
  public <T> T accept(final ASTVisitor<T> visitor) {
    return visitor.visitSetLiteral(this);
  }
}
