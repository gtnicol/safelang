package io.safelang.interpreter;

import io.safelang.runtime.SAFEValue;

/**
 * Exception used internally to implement return statements. This is used for control flow, not
 * error handling.
 */
public class ReturnException extends RuntimeException {
  private final SAFEValue value;

  public ReturnException(final SAFEValue value) {
    super("Return from function");
    this.value = value;
  }

  public SAFEValue value() {
    return value;
  }

  @Override
  public Throwable fillInStackTrace() {
    return this;
  }
}
