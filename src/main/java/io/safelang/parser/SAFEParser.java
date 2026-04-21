package io.safelang.parser;

import io.safelang.ast.ProgramNode;
import io.safelang.parser.generated.SAFEGrammarLexer;
import io.safelang.parser.generated.SAFEGrammarParser;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;

public class SAFEParser {

  public static ProgramNode parse(final String source) {
    final var lexer = new SAFEGrammarLexer(CharStreams.fromString(source));
    lexer.removeErrorListeners();
    lexer.addErrorListener(ThrowingErrorListener.INSTANCE);

    final var tokens = new CommonTokenStream(lexer);
    final var parser = new SAFEGrammarParser(tokens);
    parser.removeErrorListeners();
    parser.addErrorListener(ThrowingErrorListener.INSTANCE);

    final var tree = parser.program();
    final var builder = new ASTBuilder();
    return (ProgramNode) builder.visit(tree);
  }

  public static CommonTokenStream tokenize(final String source) {
    final var lexer = new SAFEGrammarLexer(CharStreams.fromString(source));
    lexer.removeErrorListeners();
    lexer.addErrorListener(ThrowingErrorListener.INSTANCE);

    final var tokens = new CommonTokenStream(lexer);
    tokens.fill();
    return tokens;
  }
}
