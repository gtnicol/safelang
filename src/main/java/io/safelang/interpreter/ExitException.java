package io.safelang.interpreter;

/**
 * Thrown when a SAFE program calls exit(). Caught at the CLI boundary to call System.exit(), but
 * converted to ScriptException in JSR 223.
 */
public class ExitException extends RuntimeException {

  private final int code;

  public ExitException(final int code) {
    super("exit(" + code + ")");
    this.code = code;
  }

  public int code() {
    return code;
  }
}
