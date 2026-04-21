package io.safelang.ast;

import java.util.*;

public record EnumVariantNode(int line, int column, String name, List<TypeNode> fields)
    implements ASTNode {

  public EnumVariantNode {
    fields = fields != null ? new ArrayList<>(fields) : new ArrayList<>();
  }

  public boolean hasFields() {
    return !fields.isEmpty();
  }

  @Override
  public <T> T accept(final ASTVisitor<T> visitor) {
    return visitor.visitEnumVariant(this);
  }
}
