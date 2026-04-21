package io.safelang;

/**
 * Base exception for all SAFE language errors. Provides optional line/column location info. All
 * SAFE exceptions are unchecked (RuntimeException).
 */
public class SAFEException extends RuntimeException {
  private final int line;
  private final int column;

  public SAFEException(final String message) {
    super(message);
    this.line = -1;
    this.column = -1;
  }

  public SAFEException(final String message, final Throwable cause) {
    super(message, cause);
    this.line = -1;
    this.column = -1;
  }

  public SAFEException(final String message, final int line, final int column) {
    super(line >= 0 ? String.format("%s (at line %d, column %d)", message, line, column) : message);
    this.line = line;
    this.column = column;
  }

  public SAFEException(
      final String message, final int line, final int column, final Throwable cause) {
    super(
        line >= 0 ? String.format("%s (at line %d, column %d)", message, line, column) : message,
        cause);
    this.line = line;
    this.column = column;
  }

  public int line() {
    return line;
  }

  public int column() {
    return column;
  }
}
