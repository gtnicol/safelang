package io.safelang.analyzer;

import io.safelang.SAFEException;

/** Exception thrown during semantic analysis. */
public class SemanticException extends SAFEException {
  public SemanticException(final String message, final int line, final int column) {
    super(message, line, column);
  }
}
