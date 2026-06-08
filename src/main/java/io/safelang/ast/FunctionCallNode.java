package io.safelang.ast;

import java.util.*;

/** Represents a function call. */
public record FunctionCallNode(
    int line, int column, String prefix, String name, List<ASTNode> arguments) implements ASTNode {

  public FunctionCallNode {
    arguments = arguments != null ? List.copyOf(arguments) : List.of();
  }

  public boolean hasPrefix() {
    return prefix != null && !prefix.isEmpty();
  }

  @Override
  public <T> T accept(final ASTVisitor<T> visitor) {
    return visitor.visitFunctionCall(this);
  }
}
