package io.safelang.ast;

import java.util.*;

/** Represents a bounded while loop statement. while (condition) bound (maxIterations) { body } */
public record WhileStatementNode(
    int line, int column, ASTNode condition, ASTNode bound, List<ASTNode> body) implements ASTNode {

  public WhileStatementNode {
    body = body != null ? List.copyOf(body) : List.of();
  }

  @Override
  public <T> T accept(final ASTVisitor<T> visitor) {
    return visitor.visitWhileStatement(this);
  }
}
