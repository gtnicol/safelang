package io.safelang.ast;

import java.util.*;

/** Represents a variable reference (including dotted access). */
public record VariableReferenceNode(int line, int column, String prefix, List<String> parts)
    implements ASTNode {

  public VariableReferenceNode {
    parts = parts != null ? new ArrayList<>(parts) : new ArrayList<>();
  }

  public VariableReferenceNode(final int line, final int column, final List<String> parts) {
    this(line, column, null, parts);
  }

  public boolean hasPrefix() {
    return prefix != null && !prefix.isEmpty();
  }

  @Override
  public <T> T accept(final ASTVisitor<T> visitor) {
    return visitor.visitVariableReference(this);
  }
}
