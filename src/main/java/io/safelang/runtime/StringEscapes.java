package io.safelang.runtime;

/**
 * String escape/unescape helpers shared between the C backend and the bytecode {@code .safea} text
 * format. Each format covers a different vocabulary:
 *
 * <ul>
 *   <li>{@link #cString} escapes the eight characters that need quoting inside a C string literal.
 *   <li>{@link #assembly} escapes the four characters that need quoting inside a {@code .safea}
 *       string literal — newlines, tabs, double quotes, and backslashes.
 *   <li>{@link #unassembly} reverses {@link #assembly} for the bytecode assembler.
 * </ul>
 *
 * <p>The richer escape language used by SAFE source itself (octal, hex {@code \xHH}, 4- and 8-digit
 * unicode escapes, plus {@code \a}, {@code \b}, {@code \f}, {@code \v}, {@code \?}, etc.) lives in
 * {@code parser/ASTBuilder.unescape} where it is entangled with string interpolation parsing — that
 * one is intentionally not consolidated here.
 */
public final class StringEscapes {

  private StringEscapes() {}

  /** Escape a string for emission inside a C double-quoted literal. */
  public static String cString(final String value) {
    return value
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")
        .replace("\r", "\\r")
        .replace("\t", "\\t")
        .replace("\0", "\\0")
        .replace("\b", "\\b")
        .replace("\f", "\\f");
  }

  /** Escape a string for emission inside a {@code .safea} double-quoted literal. */
  public static String assembly(final String value) {
    return value
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")
        .replace("\t", "\\t");
  }

  /**
   * Reverse {@link #assembly}: strip surrounding quotes if present and decode the four supported
   * escape sequences.
   */
  public static String unassembly(final String value) {
    var stripped = value;
    if (stripped.startsWith("\"") && stripped.endsWith("\"")) {
      stripped = stripped.substring(1, stripped.length() - 1);
    }
    // Two-pass via a sentinel so an unescaped \\ doesn't get re-interpreted by
    // a later replace.
    return stripped
        .replace("\\\\", "\0BACKSLASH\0")
        .replace("\\n", "\n")
        .replace("\\t", "\t")
        .replace("\\\"", "\"")
        .replace("\0BACKSLASH\0", "\\");
  }
}
