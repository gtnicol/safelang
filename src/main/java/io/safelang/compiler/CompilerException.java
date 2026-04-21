package io.safelang.compiler;

import io.safelang.SAFEException;

/** Exception thrown during C code generation. */
public class CompilerException extends SAFEException {
  public CompilerException(final String message) {
    super(message);
  }

  public CompilerException(final String message, final Throwable cause) {
    super(message, cause);
  }

  public CompilerException(final String message, final int line, final int column) {
    super(message, line, column);
  }

  public CompilerException(
      final String message, final int line, final int column, final Throwable cause) {
    super(message, line, column, cause);
  }
}
