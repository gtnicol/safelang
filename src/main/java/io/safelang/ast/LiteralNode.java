package io.safelang.ast;

/**
 * Sealed interface over every primitive literal the parser can produce. Each variant is a record
 * with a strongly-typed {@code value()} accessor: {@code long} for int/uint, {@code double} for
 * float, {@code String} for strings, {@code boolean} for booleans.
 *
 * <p>Consumers switch-pattern on the variants — the compiler enforces exhaustiveness. Construct via
 * the {@code ofXxx} factories to make intent explicit at the call site.
 */
public sealed interface LiteralNode extends ASTNode
    permits LiteralNode.IntLiteral,
        LiteralNode.UintLiteral,
        LiteralNode.FloatLiteral,
        LiteralNode.StringLiteral,
        LiteralNode.BoolLiteral {

  @Override
  default <T> T accept(final ASTVisitor<T> visitor) {
    return visitor.visitLiteral(this);
  }

  static IntLiteral ofInt(final int line, final int column, final long value) {
    return new IntLiteral(line, column, value);
  }

  static UintLiteral ofUint(final int line, final int column, final long value) {
    return new UintLiteral(line, column, value);
  }

  static FloatLiteral ofFloat(final int line, final int column, final double value) {
    return new FloatLiteral(line, column, value);
  }

  static StringLiteral ofString(final int line, final int column, final String value) {
    return new StringLiteral(line, column, value);
  }

  static BoolLiteral ofBoolean(final int line, final int column, final boolean value) {
    return new BoolLiteral(line, column, value);
  }

  record IntLiteral(int line, int column, long value) implements LiteralNode {}

  record UintLiteral(int line, int column, long value) implements LiteralNode {}

  record FloatLiteral(int line, int column, double value) implements LiteralNode {}

  record StringLiteral(int line, int column, String value) implements LiteralNode {}

  record BoolLiteral(int line, int column, boolean value) implements LiteralNode {}
}
