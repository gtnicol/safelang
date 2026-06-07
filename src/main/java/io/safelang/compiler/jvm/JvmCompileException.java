package io.safelang.compiler.jvm;

import io.safelang.SAFEException;

/** Raised when the JVM backend encounters a construct it cannot yet compile. */
public final class JvmCompileException extends SAFEException {

  public JvmCompileException(final String message) {
    super(message);
  }
}
