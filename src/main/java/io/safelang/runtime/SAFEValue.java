package io.safelang.runtime;

import io.safelang.interpreter.InterpreterException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Runtime value in the SAFE language. Sealed over 14 kinds: int, uint, float, string, bool, void,
 * bytes, tuple, enum, function, set, list, map, and object.
 *
 * <p>All runtime errors raised by SAFEValue methods are {@link InterpreterException}s. Both the
 * interpreter and the bytecode VM catch them and wrap them in their backend-specific exception
 * types.
 *
 * <h2>Accessor aliasing rule</h2>
 *
 * <p>All {@code as*()} accessors return a <b>live reference</b> to the underlying storage — {@code
 * asList}, {@code asTuple}, {@code asSet}, {@code asMap}, {@code asBytes}, and {@code fields} are
 * uniform so callers do not need to remember which accessors clone and which don't.
 *
 * <p>Callers that need a <i>snapshot</i> independent of the source value should call {@link
 * #copy()} (a deep copy) for arbitrary {@code SAFEValue}s, or {@code accessor().clone()} / {@code
 * new ArrayList<>(accessor())} when they only want a shallow snapshot of one collection. The {@link
 * io.safelang.interpreter.builtins.BinaryBuiltins} {@code bset}/{@code bpatch} executors are the
 * canonical example of an explicit clone at the call site.
 */
public sealed interface SAFEValue
    permits SAFEValue.IntValue,
        SAFEValue.UintValue,
        SAFEValue.FloatValue,
        SAFEValue.StringValue,
        SAFEValue.BoolValue,
        SAFEValue.VoidValue,
        SAFEValue.BytesValue,
        SAFEValue.TupleValue,
        SAFEValue.EnumValue,
        SAFEValue.FunctionValue,
        SAFEValue.SetValue,
        SAFEValue.ListValue,
        SAFEValue.MapValue,
        SAFEValue.ObjectValue {

  int MAX_TUPLE_SIZE = 64;
  int MAX_LIST_SIZE = 10_000_000;

  // ------------------------------------------------------------------
  // Factories
  // ------------------------------------------------------------------

  static SAFEValue ofInt(final long value) {
    return new IntValue(value);
  }

  static SAFEValue ofFloat(final double value) {
    return new FloatValue(value);
  }

  static SAFEValue ofUint(final long value) {
    if (value < 0) {
      throw new InterpreterException("Unsigned integer cannot be negative: " + value);
    }
    return new UintValue(value);
  }

  // Create uint from raw bits (no negativity check) for bitwise operations.
  // SAFE uint uses the non-negative long range [0, Long.MAX_VALUE].
  static SAFEValue ofUintRaw(final long bits) {
    return new UintValue(bits & Long.MAX_VALUE);
  }

  static SAFEValue ofString(final String value) {
    return new StringValue(value != null ? value : "");
  }

  static SAFEValue ofBoolean(final boolean value) {
    return new BoolValue(value);
  }

  static SAFEValue ofVoid() {
    return VoidValue.INSTANCE;
  }

  static SAFEValue ofList(final List<SAFEValue> list) {
    if (list == null) return new ListValue(PersistentList.empty());
    if (list instanceof PersistentList<SAFEValue> persistent) return new ListValue(persistent);
    return new ListValue(PersistentList.from(list));
  }

  static SAFEValue ofMap(final Map<SAFEValue, SAFEValue> map) {
    return new MapValue(map != null ? new LinkedHashMap<>(map) : new LinkedHashMap<>());
  }

  static SAFEValue ofObject(final String type, final Map<String, SAFEValue> fields) {
    return new ObjectValue(type, new LinkedHashMap<>(fields));
  }

  static SAFEValue ofEnum(final String type, final String variant, final List<SAFEValue> data) {
    return new EnumValue(type, variant, data != null ? new ArrayList<>(data) : new ArrayList<>());
  }

  static SAFEValue ofTuple(final List<SAFEValue> elements) {
    final var source = elements != null ? elements : List.<SAFEValue>of();
    if (source.size() > MAX_TUPLE_SIZE) {
      throw new InterpreterException(
          "Tuple size " + source.size() + " exceeds maximum of " + MAX_TUPLE_SIZE);
    }
    return new TupleValue(new ArrayList<>(source));
  }

  static SAFEValue ofSet(final LinkedHashSet<SAFEValue> elements) {
    return new SetValue(elements != null ? new LinkedHashSet<>(elements) : new LinkedHashSet<>());
  }

  static SAFEValue ofFunction(final Closure closure) {
    return new FunctionValue(closure);
  }

  static SAFEValue ofBytes(final byte[] value) {
    return new BytesValue(value != null ? value.clone() : new byte[0]);
  }

  // ------------------------------------------------------------------
  // Tag
  // ------------------------------------------------------------------

  ValueType type();

  default boolean isInt() {
    return this instanceof IntValue;
  }

  default boolean isFloat() {
    return this instanceof FloatValue;
  }

  default boolean isUint() {
    return this instanceof UintValue;
  }

  default boolean isString() {
    return this instanceof StringValue;
  }

  default boolean isBoolean() {
    return this instanceof BoolValue;
  }

  default boolean isList() {
    return this instanceof ListValue;
  }

  default boolean isObject() {
    return this instanceof ObjectValue;
  }

  default boolean isVoid() {
    return this instanceof VoidValue;
  }

  default boolean isMap() {
    return this instanceof MapValue;
  }

  default boolean isEnum() {
    return this instanceof EnumValue;
  }

  default boolean isTuple() {
    return this instanceof TupleValue;
  }

  default boolean isSet() {
    return this instanceof SetValue;
  }

  default boolean isFunction() {
    return this instanceof FunctionValue;
  }

  default boolean isBytes() {
    return this instanceof BytesValue;
  }

  default boolean isNumeric() {
    return this instanceof IntValue || this instanceof FloatValue || this instanceof UintValue;
  }

  // ------------------------------------------------------------------
  // Accessors — default implementations throw; each subtype overrides the
  // accessors that make sense for it plus any coercions (e.g. INT.asFloat).
  // ------------------------------------------------------------------

  default long asInt() {
    throw new InterpreterException("Cannot convert " + type() + " to int");
  }

  default double asFloat() {
    throw new InterpreterException("Cannot convert " + type() + " to float");
  }

  default long asUint() {
    throw new InterpreterException("Cannot convert " + type() + " to uint");
  }

  default boolean asBoolean() {
    return false;
  }

  default String asString() {
    return "";
  }

  default List<SAFEValue> asList() {
    throw new InterpreterException("Cannot convert " + type() + " to list");
  }

  default PersistentList<SAFEValue> asPersistentList() {
    throw new InterpreterException("Cannot convert " + type() + " to list");
  }

  default Map<SAFEValue, SAFEValue> asMap() {
    throw new InterpreterException("Cannot convert " + type() + " to map");
  }

  default LinkedHashSet<SAFEValue> asSet() {
    throw new InterpreterException("Cannot convert " + type() + " to set");
  }

  default List<SAFEValue> asTuple() {
    throw new InterpreterException("Cannot convert " + type() + " to tuple");
  }

  default int tupleSize() {
    return asTuple().size();
  }

  default Closure asClosure() {
    throw new InterpreterException("Cannot convert " + type() + " to function");
  }

  default byte[] asBytes() {
    throw new InterpreterException("Expected bytes, got " + type());
  }

  default Map<String, SAFEValue> fields() {
    throw new InterpreterException("Cannot convert " + type() + " to object");
  }

  default String enumType() {
    throw new InterpreterException("Cannot get enum type name from " + type());
  }

  default String variant() {
    throw new InterpreterException("Cannot get enum variant from " + type());
  }

  default List<SAFEValue> data() {
    throw new InterpreterException("Cannot get enum data from " + type());
  }

  default SAFEValue element(final int index) {
    final var list = asList();
    if (index < 0 || index >= list.size()) {
      throw new InterpreterException("List index out of bounds: " + index);
    }
    return list.get(index);
  }

  default void setElement(final int index, final SAFEValue value) {
    throw new InterpreterException("Cannot set element on " + type());
  }

  default SAFEValue entry(final SAFEValue key) {
    final var map = asMap();
    final var result = map.get(key);
    return result != null ? result : ofVoid();
  }

  default void setEntry(final SAFEValue key, final SAFEValue value) {
    throw new InterpreterException("Cannot set entry on " + type());
  }

  default void setField(final String name, final SAFEValue value) {
    throw new InterpreterException("Cannot set field on " + type());
  }

  // ------------------------------------------------------------------
  // Deep copy — default is identity (immutable); mutable subtypes override.
  // ------------------------------------------------------------------

  default SAFEValue copy() {
    return this;
  }

  // ------------------------------------------------------------------
  // Arithmetic / bitwise / compare — static; preserved verbatim from the
  // previous implementation.
  // ------------------------------------------------------------------

  static SAFEValue add(final SAFEValue left, final SAFEValue right) {
    if (left.isBytes() && right.isBytes()) {
      final var a = left.asBytes();
      final var b = right.asBytes();
      final var result = new byte[a.length + b.length];
      System.arraycopy(a, 0, result, 0, a.length);
      System.arraycopy(b, 0, result, a.length, b.length);
      return new BytesValue(result);
    }
    if (left.isString() || right.isString()) {
      return ofString(left.asString() + right.asString());
    }
    if (left.isFloat() || right.isFloat()) {
      return ofFloat(left.asFloat() + right.asFloat());
    }
    if (left.isInt() && right.isInt()) {
      try {
        return ofInt(Math.addExact(left.asInt(), right.asInt()));
      } catch (ArithmeticException e) {
        throw new InterpreterException("Integer overflow");
      }
    }
    if (left.isUint() || right.isUint()) {
      final long result = left.asUint() + right.asUint();
      if (Long.compareUnsigned(result, left.asUint()) < 0) {
        throw new InterpreterException("Unsigned integer overflow");
      }
      return ofUint(result);
    }
    throw new InterpreterException("Cannot add " + left.type() + " and " + right.type());
  }

  static SAFEValue subtract(final SAFEValue left, final SAFEValue right) {
    if (left.isFloat() || right.isFloat()) {
      return ofFloat(left.asFloat() - right.asFloat());
    }
    if (left.isInt() && right.isInt()) {
      try {
        return ofInt(Math.subtractExact(left.asInt(), right.asInt()));
      } catch (ArithmeticException e) {
        throw new InterpreterException("Integer overflow");
      }
    }
    if (left.isUint() || right.isUint()) {
      if (Long.compareUnsigned(left.asUint(), right.asUint()) < 0) {
        throw new InterpreterException("Unsigned integer underflow");
      }
      return ofUint(left.asUint() - right.asUint());
    }
    throw new InterpreterException("Cannot subtract " + left.type() + " and " + right.type());
  }

  static SAFEValue multiply(final SAFEValue left, final SAFEValue right) {
    if (left.isFloat() || right.isFloat()) {
      return ofFloat(left.asFloat() * right.asFloat());
    }
    if (left.isInt() && right.isInt()) {
      try {
        return ofInt(Math.multiplyExact(left.asInt(), right.asInt()));
      } catch (ArithmeticException e) {
        throw new InterpreterException("Integer overflow");
      }
    }
    if (left.isUint() || right.isUint()) {
      final long a = left.asUint();
      final long b = right.asUint();
      final long result = a * b;
      if (a != 0 && Long.divideUnsigned(result, a) != b) {
        throw new InterpreterException("Unsigned integer overflow");
      }
      return ofUint(result);
    }
    throw new InterpreterException("Cannot multiply " + left.type() + " and " + right.type());
  }

  static SAFEValue divide(final SAFEValue left, final SAFEValue right) {
    if (left.isFloat() || right.isFloat()) {
      final var divisor = right.asFloat();
      if (divisor == 0.0) throw new InterpreterException("Division by zero");
      return ofFloat(left.asFloat() / divisor);
    }
    if (left.isInt() && right.isInt()) {
      final var divisor = right.asInt();
      if (divisor == 0) throw new InterpreterException("Division by zero");
      if (left.asInt() == Long.MIN_VALUE && divisor == -1) {
        throw new InterpreterException("Integer overflow: INT64_MIN / -1");
      }
      return ofInt(left.asInt() / divisor);
    }
    if (left.isUint() || right.isUint()) {
      final var divisor = right.asUint();
      if (divisor == 0) throw new InterpreterException("Division by zero");
      return ofUint(left.asUint() / divisor);
    }
    throw new InterpreterException("Cannot divide " + left.type() + " and " + right.type());
  }

  static SAFEValue modulo(final SAFEValue left, final SAFEValue right) {
    if (left.isInt() && right.isInt()) {
      final var divisor = right.asInt();
      if (divisor == 0) throw new InterpreterException("Division by zero");
      return ofInt(left.asInt() % divisor);
    }
    if (left.isUint() || right.isUint()) {
      final var divisor = right.asUint();
      if (divisor == 0) throw new InterpreterException("Division by zero");
      return ofUint(left.asUint() % divisor);
    }
    throw new InterpreterException("Cannot modulo " + left.type() + " and " + right.type());
  }

  static int compare(final SAFEValue left, final SAFEValue right) {
    if (left.isBytes() && right.isBytes()) {
      return Arrays.compareUnsigned(left.asBytes(), right.asBytes());
    }
    if (!left.isNumeric() || !right.isNumeric()) {
      throw new InterpreterException("Comparison requires numeric types");
    }
    if (left.isFloat() || right.isFloat()) {
      return Double.compare(left.asFloat(), right.asFloat());
    }
    return Long.compare(left.asInt(), right.asInt());
  }

  static SAFEValue negate(final SAFEValue operand) {
    return switch (operand) {
      case IntValue(long v) -> {
        try {
          yield ofInt(Math.negateExact(v));
        } catch (ArithmeticException e) {
          throw new InterpreterException("Integer overflow");
        }
      }
      case FloatValue(double v) -> ofFloat(-v);
      case UintValue(long v) -> {
        if (v == 0) yield ofUint(0);
        throw new InterpreterException("Cannot negate unsigned integer (would be negative)");
      }
      default -> throw new InterpreterException("Cannot negate " + operand.type());
    };
  }

  static SAFEValue bitwiseAnd(final SAFEValue left, final SAFEValue right) {
    if (left.isUint() || right.isUint()) return ofUint(left.asUint() & right.asUint());
    if (left.isInt() && right.isInt()) return ofInt(left.asInt() & right.asInt());
    throw new InterpreterException("Bitwise AND requires int or uint types");
  }

  static SAFEValue bitwiseOr(final SAFEValue left, final SAFEValue right) {
    if (left.isUint() || right.isUint()) return ofUintRaw(left.asUint() | right.asUint());
    if (left.isInt() && right.isInt()) return ofInt(left.asInt() | right.asInt());
    throw new InterpreterException("Bitwise OR requires int or uint types");
  }

  static SAFEValue bitwiseXor(final SAFEValue left, final SAFEValue right) {
    if (left.isUint() || right.isUint()) return ofUintRaw(left.asUint() ^ right.asUint());
    if (left.isInt() && right.isInt()) return ofInt(left.asInt() ^ right.asInt());
    throw new InterpreterException("Bitwise XOR requires int or uint types");
  }

  static SAFEValue bitwiseNot(final SAFEValue operand) {
    return switch (operand) {
      case UintValue(long v) -> ofUintRaw(~v);
      case IntValue(long v) -> ofInt(~v);
      default -> throw new InterpreterException("Bitwise NOT requires int or uint type");
    };
  }

  static SAFEValue shiftLeft(final SAFEValue left, final SAFEValue right) {
    final var amount = shiftAmount(right);
    if (left.isUint() || right.isUint()) return ofUintRaw(left.asUint() << amount);
    if (left.isInt() && right.isInt()) return ofInt(left.asInt() << amount);
    throw new InterpreterException("Left shift requires int or uint types");
  }

  static SAFEValue shiftRight(final SAFEValue left, final SAFEValue right) {
    final var amount = shiftAmount(right);
    if (left.isUint() || right.isUint()) return ofUintRaw(left.asUint() >>> amount);
    if (left.isInt() && right.isInt()) return ofInt(left.asInt() >> amount);
    throw new InterpreterException("Right shift requires int or uint types");
  }

  // Validate the shift amount on the full 64-bit value, before any cast.
  // Casting first would silently truncate the high bits — a uint amount of
  // (2^32 + 32) would survive the bounds check after truncation to 32.
  private static int shiftAmount(final SAFEValue value) {
    final var raw = value.isUint() ? value.asUint() : value.asInt();
    if (raw < 0 || raw > 63) {
      throw new InterpreterException("Shift amount must be 0-63, got " + raw);
    }
    return (int) raw;
  }

  private static String join(
      final Iterable<SAFEValue> items, final String open, final String close) {
    final var builder = new StringBuilder(open);
    var first = true;
    for (final var item : items) {
      if (!first) builder.append(", ");
      builder.append(item.asString());
      first = false;
    }
    return builder.append(close).toString();
  }

  // ------------------------------------------------------------------
  // Variants
  // ------------------------------------------------------------------

  record IntValue(long value) implements SAFEValue {
    @Override
    public ValueType type() {
      return ValueType.INT;
    }

    @Override
    public long asInt() {
      return value;
    }

    @Override
    public double asFloat() {
      return value;
    }

    @Override
    public long asUint() {
      if (value < 0) {
        throw new InterpreterException("Cannot convert negative int to uint: " + value);
      }
      return value;
    }

    @Override
    public boolean asBoolean() {
      return value != 0;
    }

    @Override
    public String asString() {
      return Long.toString(value);
    }

    @Override
    public String toString() {
      return asString();
    }
  }

  record UintValue(long value) implements SAFEValue {
    @Override
    public ValueType type() {
      return ValueType.UINT;
    }

    @Override
    public long asInt() {
      return value;
    }

    @Override
    public double asFloat() {
      return value;
    }

    @Override
    public long asUint() {
      return value;
    }

    @Override
    public boolean asBoolean() {
      return value != 0;
    }

    @Override
    public String asString() {
      return Long.toString(value);
    }

    @Override
    public String toString() {
      return asString();
    }
  }

  record FloatValue(double value) implements SAFEValue {
    @Override
    public ValueType type() {
      return ValueType.FLOAT;
    }

    @Override
    public long asInt() {
      return (long) value;
    }

    @Override
    public double asFloat() {
      return value;
    }

    @Override
    public long asUint() {
      final long result = (long) value;
      if (result < 0) {
        throw new InterpreterException("Cannot convert negative float to uint");
      }
      return result;
    }

    @Override
    public boolean asBoolean() {
      return value != 0.0;
    }

    @Override
    public String asString() {
      return Double.toString(value);
    }

    @Override
    public String toString() {
      return asString();
    }

    @Override
    public boolean equals(final Object other) {
      if (!(other instanceof FloatValue that)) return false;
      var a = value;
      var b = that.value;
      if (a == 0.0) a = 0.0;
      if (b == 0.0) b = 0.0;
      return Double.compare(a, b) == 0;
    }

    @Override
    public int hashCode() {
      var d = value;
      if (d == 0.0) d = 0.0; // canonicalize -0.0 → +0.0
      if (Double.isNaN(d)) d = Double.NaN; // canonicalize all NaN bit patterns
      return Objects.hash(ValueType.FLOAT, Double.hashCode(d));
    }
  }

  record StringValue(String value) implements SAFEValue {
    @Override
    public ValueType type() {
      return ValueType.STRING;
    }

    @Override
    public long asInt() {
      try {
        return Long.parseLong(value);
      } catch (NumberFormatException e) {
        throw new InterpreterException("Cannot convert string '" + value + "' to int");
      }
    }

    @Override
    public double asFloat() {
      try {
        return Double.parseDouble(value);
      } catch (NumberFormatException e) {
        throw new InterpreterException("Cannot convert string '" + value + "' to float");
      }
    }

    @Override
    public long asUint() {
      try {
        final long result = Long.parseLong(value);
        if (result < 0) {
          throw new InterpreterException("Cannot convert negative number to uint: " + result);
        }
        return result;
      } catch (NumberFormatException e) {
        throw new InterpreterException("Cannot convert string '" + value + "' to uint");
      }
    }

    @Override
    public boolean asBoolean() {
      return !value.isEmpty();
    }

    @Override
    public String asString() {
      return value;
    }

    @Override
    public String toString() {
      return value;
    }
  }

  record BoolValue(boolean value) implements SAFEValue {
    @Override
    public ValueType type() {
      return ValueType.BOOLEAN;
    }

    @Override
    public long asInt() {
      return value ? 1 : 0;
    }

    @Override
    public double asFloat() {
      return value ? 1.0 : 0.0;
    }

    @Override
    public long asUint() {
      return value ? 1 : 0;
    }

    @Override
    public boolean asBoolean() {
      return value;
    }

    @Override
    public String asString() {
      return Boolean.toString(value);
    }

    @Override
    public String toString() {
      return asString();
    }
  }

  record VoidValue() implements SAFEValue {
    static final VoidValue INSTANCE = new VoidValue();

    @Override
    public ValueType type() {
      return ValueType.VOID;
    }

    @Override
    public boolean asBoolean() {
      return false;
    }

    @Override
    public String asString() {
      return "void";
    }

    @Override
    public String toString() {
      return "void";
    }
  }

  record BytesValue(byte[] value) implements SAFEValue {
    @Override
    public ValueType type() {
      return ValueType.BYTES;
    }

    @Override
    public byte[] asBytes() {
      return value;
    }

    @Override
    public boolean asBoolean() {
      return value.length > 0;
    }

    @Override
    public String asString() {
      final var builder = new StringBuilder(value.length * 2);
      for (final var b : value) {
        builder.append(String.format("%02x", b & 0xFF));
      }
      return builder.toString();
    }

    @Override
    public SAFEValue copy() {
      return new BytesValue(value.clone());
    }

    @Override
    public String toString() {
      return asString();
    }

    @Override
    public boolean equals(final Object other) {
      return other instanceof BytesValue that && Arrays.equals(value, that.value);
    }

    @Override
    public int hashCode() {
      return Objects.hash(ValueType.BYTES, Arrays.hashCode(value));
    }
  }

  record TupleValue(List<SAFEValue> value) implements SAFEValue {
    @Override
    public ValueType type() {
      return ValueType.TUPLE;
    }

    @Override
    public List<SAFEValue> asTuple() {
      return value;
    }

    @Override
    public boolean asBoolean() {
      return !value.isEmpty();
    }

    @Override
    public String asString() {
      return join(value, "(", ")");
    }

    @Override
    public SAFEValue copy() {
      final var clone = new ArrayList<SAFEValue>(value.size());
      for (final var element : value) {
        clone.add(element != null ? element.copy() : null);
      }
      return new TupleValue(clone);
    }

    @Override
    public String toString() {
      return asString();
    }
  }

  record SetValue(LinkedHashSet<SAFEValue> value) implements SAFEValue {
    @Override
    public ValueType type() {
      return ValueType.SET;
    }

    @Override
    public LinkedHashSet<SAFEValue> asSet() {
      return value;
    }

    @Override
    public boolean asBoolean() {
      return !value.isEmpty();
    }

    @Override
    public String asString() {
      return join(value, "#{", "}");
    }

    @Override
    public SAFEValue copy() {
      final var clone = new LinkedHashSet<SAFEValue>(value.size());
      for (final var element : value) {
        clone.add(element != null ? element.copy() : null);
      }
      return new SetValue(clone);
    }

    @Override
    public String toString() {
      return asString();
    }
  }

  record EnumValue(String enumType, String variant, List<SAFEValue> data) implements SAFEValue {
    @Override
    public ValueType type() {
      return ValueType.ENUM;
    }

    @Override
    public boolean asBoolean() {
      return true;
    }

    @Override
    public String asString() {
      final var builder = new StringBuilder(enumType).append(".").append(variant);
      if (!data.isEmpty()) {
        builder.append(join(data, "(", ")"));
      }
      return builder.toString();
    }

    @Override
    public List<SAFEValue> data() {
      return new ArrayList<>(data);
    }

    @Override
    public SAFEValue copy() {
      final var clone = new ArrayList<SAFEValue>(data.size());
      for (final var element : data) {
        clone.add(element != null ? element.copy() : null);
      }
      return new EnumValue(enumType, variant, clone);
    }

    @Override
    public String toString() {
      return asString();
    }
  }

  record FunctionValue(Closure closure) implements SAFEValue {
    @Override
    public ValueType type() {
      return ValueType.FUNCTION;
    }

    @Override
    public Closure asClosure() {
      return closure;
    }

    @Override
    public boolean asBoolean() {
      return true;
    }

    @Override
    public String asString() {
      return closure.isNamed() ? "fn<" + closure.name() + ">" : "fn<lambda>";
    }

    @Override
    public String toString() {
      return asString();
    }

    @Override
    public boolean equals(final Object other) {
      // Closures compare by identity — two distinct closures are never equal
      // even if their ASTs match, to match the pre-refactor semantics.
      return other instanceof FunctionValue that && closure == that.closure;
    }

    @Override
    public int hashCode() {
      return System.identityHashCode(closure);
    }
  }

  // ------------------------------------------------------------------
  // Mutable variants
  // ------------------------------------------------------------------

  final class ListValue implements SAFEValue {
    // Intentionally not final: setElement replaces with a new PersistentList
    // to preserve copy-on-write semantics while exposing reference identity
    // to aliased SAFEValues.
    private PersistentList<SAFEValue> list;

    ListValue(final PersistentList<SAFEValue> list) {
      this.list = list;
    }

    @Override
    public ValueType type() {
      return ValueType.LIST;
    }

    @Override
    public List<SAFEValue> asList() {
      return list;
    }

    @Override
    public PersistentList<SAFEValue> asPersistentList() {
      return list;
    }

    @Override
    public boolean asBoolean() {
      return !list.isEmpty();
    }

    @Override
    public String asString() {
      return join(list, "[", "]");
    }

    @Override
    public SAFEValue element(final int index) {
      if (index < 0 || index >= list.size()) {
        throw new InterpreterException("List index out of bounds: " + index);
      }
      return list.get(index);
    }

    @Override
    public void setElement(final int index, final SAFEValue value) {
      if (index < 0 || index >= list.size()) {
        throw new InterpreterException("List index out of bounds: " + index);
      }
      this.list = list.update(index, value);
    }

    @Override
    public SAFEValue copy() {
      PersistentList<SAFEValue> clone = PersistentList.empty();
      for (final var element : list) {
        clone = clone.append(element != null ? element.copy() : null);
      }
      return new ListValue(clone);
    }

    @Override
    public String toString() {
      return asString();
    }

    @Override
    public boolean equals(final Object other) {
      return other instanceof ListValue that && list.equals(that.list);
    }

    @Override
    public int hashCode() {
      return Objects.hash(ValueType.LIST, list);
    }
  }

  final class MapValue implements SAFEValue {
    // Non-final: setEntry swaps in a freshly built LinkedHashMap.
    private Map<SAFEValue, SAFEValue> map;

    MapValue(final Map<SAFEValue, SAFEValue> map) {
      this.map = map;
    }

    @Override
    public ValueType type() {
      return ValueType.MAP;
    }

    @Override
    public Map<SAFEValue, SAFEValue> asMap() {
      return map;
    }

    @Override
    public boolean asBoolean() {
      return !map.isEmpty();
    }

    @Override
    public String asString() {
      final var builder = new StringBuilder("{");
      var first = true;
      for (final var e : map.entrySet()) {
        if (!first) builder.append(", ");
        builder.append(e.getKey().asString()).append(": ").append(e.getValue().asString());
        first = false;
      }
      return builder.append("}").toString();
    }

    @Override
    public SAFEValue entry(final SAFEValue key) {
      final var result = map.get(key);
      return result != null ? result : ofVoid();
    }

    @Override
    public void setEntry(final SAFEValue key, final SAFEValue value) {
      final var next = new LinkedHashMap<>(map);
      next.put(key, value);
      this.map = next;
    }

    @Override
    public SAFEValue copy() {
      final var clone = new LinkedHashMap<SAFEValue, SAFEValue>(map.size());
      for (final var entry : map.entrySet()) {
        final var k = entry.getKey() != null ? entry.getKey().copy() : null;
        final var v = entry.getValue() != null ? entry.getValue().copy() : null;
        clone.put(k, v);
      }
      return new MapValue(clone);
    }

    @Override
    public String toString() {
      return asString();
    }

    @Override
    public boolean equals(final Object other) {
      return other instanceof MapValue that && map.equals(that.map);
    }

    @Override
    public int hashCode() {
      return Objects.hash(ValueType.MAP, map);
    }
  }

  final class ObjectValue implements SAFEValue {
    private final String typeName;
    private final Map<String, SAFEValue> fields;

    ObjectValue(final String typeName, final Map<String, SAFEValue> fields) {
      this.typeName = typeName;
      this.fields = fields;
    }

    @Override
    public ValueType type() {
      return ValueType.OBJECT;
    }

    String typeName() {
      return typeName;
    }

    @Override
    public Map<String, SAFEValue> fields() {
      return fields;
    }

    @Override
    public boolean asBoolean() {
      return true;
    }

    @Override
    public String asString() {
      final var builder = new StringBuilder(typeName).append(" { ");
      var first = true;
      for (final var entry : fields.entrySet()) {
        if (!first) builder.append(", ");
        builder.append(entry.getKey()).append(": ").append(entry.getValue().asString());
        first = false;
      }
      return builder.append(" }").toString();
    }

    @Override
    public void setField(final String name, final SAFEValue value) {
      fields.put(name, value);
    }

    @Override
    public SAFEValue copy() {
      final var cloned = new LinkedHashMap<String, SAFEValue>(fields.size());
      for (final var entry : fields.entrySet()) {
        cloned.put(entry.getKey(), entry.getValue() != null ? entry.getValue().copy() : null);
      }
      return new ObjectValue(typeName, cloned);
    }

    @Override
    public String toString() {
      return asString();
    }

    @Override
    public boolean equals(final Object other) {
      return other instanceof ObjectValue that
          && typeName.equals(that.typeName)
          && fields.equals(that.fields);
    }

    @Override
    public int hashCode() {
      return Objects.hash(ValueType.OBJECT, typeName, fields);
    }
  }

  enum ValueType {
    INT,
    FLOAT,
    UINT,
    STRING,
    BOOLEAN,
    LIST,
    OBJECT,
    VOID,
    MAP,
    ENUM,
    TUPLE,
    SET,
    FUNCTION,
    BYTES
  }
}
