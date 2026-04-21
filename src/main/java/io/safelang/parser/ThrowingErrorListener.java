package io.safelang.parser;

import org.antlr.v4.runtime.BaseErrorListener;
import org.antlr.v4.runtime.RecognitionException;
import org.antlr.v4.runtime.Recognizer;

public class ThrowingErrorListener extends BaseErrorListener {

  public static final ThrowingErrorListener INSTANCE = new ThrowingErrorListener();

  @Override
  public void syntaxError(
      final Recognizer<?, ?> recognizer,
      final Object symbol,
      final int line,
      final int column,
      final String message,
      final RecognitionException exception) {
    throw new ParserException(message, line, column);
  }
}
