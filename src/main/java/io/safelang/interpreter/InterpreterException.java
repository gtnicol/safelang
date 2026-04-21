package io.safelang.interpreter;

import io.safelang.SAFEException;

/** Exception thrown by the interpreter when a runtime error is encountered. */
public class InterpreterException extends SAFEException {
  public InterpreterException(final String message) {
    super(message);
  }

  public InterpreterException(final String message, final Throwable cause) {
    super(message, cause);
  }
}
