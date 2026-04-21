package io.safelang.bytecode;

import io.safelang.SAFEException;

/** Exception thrown during bytecode compilation, execution, or I/O. */
public class BytecodeException extends SAFEException {
  public BytecodeException(final String message) {
    super(message);
  }

  public BytecodeException(final String message, final Throwable cause) {
    super(message, cause);
  }
}
