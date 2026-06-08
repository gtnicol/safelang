package io.safelang.ast;

import java.util.*;

public record EnumPatternNode(int line, int column, String variant, List<String> bindings)
    implements ASTNode {

  public EnumPatternNode {
    bindings = bindings != null ? List.copyOf(bindings) : List.of();
  }

  public boolean hasBindings() {
    return !bindings.isEmpty();
  }

  @Override
  public <T> T accept(final ASTVisitor<T> visitor) {
    return visitor.visitEnumPattern(this);
  }
}
