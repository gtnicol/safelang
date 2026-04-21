package io.safelang.parser;

import io.safelang.SAFEException;

/** Exception thrown by the parser when a syntax error is encountered. */
public class ParserException extends SAFEException {
  public ParserException(final String message, final int line, final int column) {
    super(message, line, column);
  }
}
