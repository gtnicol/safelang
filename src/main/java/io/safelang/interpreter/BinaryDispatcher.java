package io.safelang.interpreter;

import io.safelang.runtime.SAFEValue;
import java.util.function.BinaryOperator;

/**
 * Dispatches a binary operator over two already-evaluated SAFE values. Short-circuit logical
 * operators ({@code &&}, {@code ||}) are NOT routed here — they must be handled at the visitor
 * level so the right operand can be skipped, and they live in {@link Interpreter} where lazy
 * evaluation has access to the AST visitor.
 *
 * <p>Wraps any {@link RuntimeException} thrown from {@link SAFEValue}'s arithmetic into a typed
 * {@link InterpreterException}, preserving the previous behaviour of the inline dispatcher.
 */
public final class BinaryDispatcher {

  private BinaryDispatcher() {}

  public static SAFEValue dispatch(
      final String operator, final SAFEValue left, final SAFEValue right) {
    return switch (operator) {
      case "+" -> arithmetic(SAFEValue::add, left, right);
      case "-" -> arithmetic(SAFEValue::subtract, left, right);
      case "*" -> arithmetic(SAFEValue::multiply, left, right);
      case "/" -> arithmetic(SAFEValue::divide, left, right);
      case "%" -> arithmetic(SAFEValue::modulo, left, right);
      case "<" -> SAFEValue.ofBoolean(compare(left, right) < 0);
      case "<=" -> SAFEValue.ofBoolean(compare(left, right) <= 0);
      case ">" -> SAFEValue.ofBoolean(compare(left, right) > 0);
      case ">=" -> SAFEValue.ofBoolean(compare(left, right) >= 0);
      case "==" -> SAFEValue.ofBoolean(left.equals(right));
      case "!=" -> SAFEValue.ofBoolean(!left.equals(right));
      case "in" -> includes(left, right);
      case "&" -> arithmetic(SAFEValue::bitwiseAnd, left, right);
      case "|" -> arithmetic(SAFEValue::bitwiseOr, left, right);
      case "^" -> arithmetic(SAFEValue::bitwiseXor, left, right);
      case "<<" -> arithmetic(SAFEValue::shiftLeft, left, right);
      case ">>" -> arithmetic(SAFEValue::shiftRight, left, right);
      default -> throw new InterpreterException("Unknown binary operator: " + operator);
    };
  }

  private static SAFEValue arithmetic(
      final BinaryOperator<SAFEValue> operation, final SAFEValue left, final SAFEValue right) {
    try {
      return operation.apply(left, right);
    } catch (final RuntimeException exception) {
      throw new InterpreterException(exception.getMessage(), exception);
    }
  }

  private static int compare(final SAFEValue left, final SAFEValue right) {
    try {
      return SAFEValue.compare(left, right);
    } catch (final RuntimeException exception) {
      throw new InterpreterException(exception.getMessage(), exception);
    }
  }

  private static SAFEValue includes(final SAFEValue left, final SAFEValue right) {
    return switch (right) {
      case SAFEValue.ListValue list -> {
        for (final var item : list.asList()) {
          if (left.equals(item)) yield SAFEValue.ofBoolean(true);
        }
        yield SAFEValue.ofBoolean(false);
      }
      case SAFEValue.MapValue map -> SAFEValue.ofBoolean(map.asMap().containsKey(left));
      case SAFEValue.SetValue set -> SAFEValue.ofBoolean(set.asSet().contains(left));
      case SAFEValue.StringValue(String s) -> SAFEValue.ofBoolean(s.contains(left.asString()));
      default ->
          throw new InterpreterException(
              "'in' operator requires list, map, set, or string on right side");
    };
  }
}
