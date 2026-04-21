package io.safelang.ast;

import java.util.*;
import java.util.ArrayList;

public record ImportNode(int line, int column, String module, List<String> symbols)
    implements ASTNode {

  public ImportNode {
    symbols = symbols != null ? new ArrayList<>(symbols) : null;
  }

  public ImportNode(final int line, final int column, final String module) {
    this(line, column, module, null);
  }

  public boolean isSelective() {
    return symbols != null;
  }

  @Override
  public <T> T accept(final ASTVisitor<T> visitor) {
    return visitor.visitImport(this);
  }
}
