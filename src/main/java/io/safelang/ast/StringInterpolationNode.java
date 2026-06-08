package io.safelang.ast;

import java.util.*;

/** Represents a string interpolation expression. */
public record StringInterpolationNode(int line, int column, List<ASTNode> parts)
    implements ASTNode {

  public StringInterpolationNode {
    parts = parts != null ? List.copyOf(parts) : List.of();
  }

  @Override
  public <T> T accept(final ASTVisitor<T> visitor) {
    return visitor.visitStringInterpolation(this);
  }
}
