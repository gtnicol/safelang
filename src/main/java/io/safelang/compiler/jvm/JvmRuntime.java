package io.safelang.compiler.jvm;

import io.safelang.SAFEException;
import io.safelang.interpreter.BinaryDispatcher;
import io.safelang.interpreter.builtins.BuiltinRegistration;
import io.safelang.runtime.BinaryFileHandle;
import io.safelang.runtime.BuiltinExecutors;
import io.safelang.runtime.Closure;
import io.safelang.runtime.FileHandle;
import io.safelang.runtime.SAFEValue;
import java.io.Writer;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Scanner;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Static runtime facade invoked from generated JVM bytecode. It exists so generated code references
 * only ordinary class methods — never {@code SAFEValue}'s interface-static factories — which keeps
 * the emitted class files at major version 50 and free of {@code StackMapTable} attributes.
 *
 * <p>All semantics are delegated to the existing SAFE runtime: operators go through {@link
 * BinaryDispatcher}, builtins through the shared {@link BuiltinExecutors} table populated by {@link
 * BuiltinRegistration}, and the {@code decreases} measure discipline mirrors {@code
 * BytecodeVM.decreases}. Generated programs therefore behave identically to the interpreter and VM.
 */
public final class JvmRuntime {

  private static final BuiltinExecutors executors = new BuiltinExecutors();
  private static final List<String> arguments = new ArrayList<>();
  private static final Map<Integer, FileHandle> handles = new HashMap<>();
  private static final Map<Integer, BinaryFileHandle> binaries = new HashMap<>();
  private static final Map<String, Deque<Long>> measures = new HashMap<>();
  private static final Map<String, Method> methods = new HashMap<>();
  private static Class<?> program;

  // When null, the IO builtins fall back to System.out (the normal standalone-jar path). Test
  // harnesses install a capture writer via setOutput to collect program output in-process.
  private static volatile Writer output;

  static {
    final var random = new Random[] {new Random()};
    final var scanner = new Scanner(System.in, StandardCharsets.UTF_8);
    final var counter = new AtomicInteger(0);
    BuiltinRegistration.registerAll(
        executors, () -> output, scanner, random, handles, binaries, counter, arguments);
  }

  private JvmRuntime() {}

  /** Redirect program output to {@code writer} (used by in-process test harnesses). */
  public static void setOutput(final Writer writer) {
    output = writer;
  }

  /** Restore the default output path (System.out) used by standalone jars. */
  public static void clearOutput() {
    output = null;
  }

  public static SAFEValue integer(final long value) {
    return SAFEValue.ofInt(value);
  }

  public static SAFEValue decimal(final double value) {
    return SAFEValue.ofFloat(value);
  }

  public static SAFEValue unsigned(final long value) {
    return SAFEValue.ofUint(value);
  }

  public static SAFEValue text(final String value) {
    return SAFEValue.ofString(value);
  }

  public static SAFEValue bool(final boolean value) {
    return SAFEValue.ofBoolean(value);
  }

  public static SAFEValue nothing() {
    return SAFEValue.ofVoid();
  }

  public static SAFEValue list(final SAFEValue[] elements) {
    return SAFEValue.ofList(new ArrayList<>(Arrays.asList(elements)));
  }

  public static SAFEValue tuple(final SAFEValue[] elements) {
    return SAFEValue.ofTuple(Arrays.asList(elements));
  }

  public static SAFEValue set(final SAFEValue[] elements) {
    return SAFEValue.ofSet(new LinkedHashSet<>(Arrays.asList(elements)));
  }

  public static SAFEValue map(final SAFEValue[] keys, final SAFEValue[] values) {
    final var entries = new LinkedHashMap<SAFEValue, SAFEValue>();
    for (var index = 0; index < keys.length; index++) {
      entries.put(keys[index], values[index]);
    }
    return SAFEValue.ofMap(entries);
  }

  public static SAFEValue object(
      final String type, final String[] names, final SAFEValue[] values) {
    final var fields = new LinkedHashMap<String, SAFEValue>();
    for (var index = 0; index < names.length; index++) {
      fields.put(names[index], values[index]);
    }
    return SAFEValue.ofObject(type, fields);
  }

  public static SAFEValue enumeration(
      final String type, final String variant, final SAFEValue[] data) {
    return SAFEValue.ofEnum(type, variant, Arrays.asList(data));
  }

  /** Index into a list, tuple, map, or string, matching {@code Interpreter.visitIndexAccess}. */
  public static SAFEValue index(final SAFEValue container, final SAFEValue key) {
    return switch (container) {
      case SAFEValue.ListValue list -> list.element((int) key.asInt());
      case SAFEValue.TupleValue(List<SAFEValue> elements) -> {
        final var position = (int) key.asInt();
        if (position < 0 || position >= elements.size()) {
          throw new SAFEException("Tuple index out of bounds: " + position);
        }
        yield elements.get(position);
      }
      case SAFEValue.MapValue map -> map.entry(key);
      case SAFEValue.StringValue(String text) -> {
        final var position = (int) key.asInt();
        if (position < 0 || position >= text.length()) {
          throw new SAFEException("String index out of bounds: " + position);
        }
        yield SAFEValue.ofString(String.valueOf(text.charAt(position)));
      }
      default -> throw new SAFEException("Cannot index into " + container.type());
    };
  }

  public static SAFEValue field(final SAFEValue object, final String name) {
    if (!object.isObject()) {
      throw new SAFEException("Cannot access field on non-object type");
    }
    final var value = object.fields().get(name);
    if (value == null) {
      throw new SAFEException("Field not found: " + name);
    }
    return value;
  }

  public static SAFEValue interpolate(final SAFEValue[] parts) {
    final var builder = new StringBuilder();
    for (final var part : parts) {
      builder.append(part.asString());
    }
    return SAFEValue.ofString(builder.toString());
  }

  public static SAFEValue assertion(final SAFEValue condition, final SAFEValue message) {
    if (!condition.asBoolean()) {
      throw new SAFEException(message.isVoid() ? "Assertion failed" : message.asString());
    }
    return SAFEValue.ofVoid();
  }

  public static boolean equal(final SAFEValue left, final SAFEValue right) {
    return left.equals(right);
  }

  public static boolean variantMatches(final SAFEValue subject, final String variant) {
    return subject.isEnum() && subject.variant().equals(variant);
  }

  public static SAFEValue datum(final SAFEValue subject, final int position) {
    return subject.data().get(position);
  }

  public static SAFEValue binary(
      final String operator, final SAFEValue left, final SAFEValue right) {
    return BinaryDispatcher.dispatch(operator, left, right);
  }

  public static SAFEValue not(final SAFEValue operand) {
    return SAFEValue.ofBoolean(!operand.asBoolean());
  }

  public static SAFEValue negate(final SAFEValue operand) {
    try {
      return SAFEValue.negate(operand);
    } catch (final RuntimeException exception) {
      throw new SAFEException(exception.getMessage());
    }
  }

  public static SAFEValue complement(final SAFEValue operand) {
    try {
      return SAFEValue.bitwiseNot(operand);
    } catch (final RuntimeException exception) {
      throw new SAFEException(exception.getMessage());
    }
  }

  /** Read a SAFE value as a JVM boolean for branch instructions. */
  public static boolean truth(final SAFEValue value) {
    return value.asBoolean();
  }

  public static SAFEValue call(final String name, final SAFEValue[] arguments) {
    final var executor = executors.get(name);
    if (executor == null) {
      throw new SAFEException("Unknown builtin: " + name);
    }
    return executor.execute(new ArrayList<>(Arrays.asList(arguments)));
  }

  /**
   * Expand a {@code for}-loop iterable into an iterator over its elements, matching the
   * interpreter: lists yield their elements, strings yield single-character strings, sets yield
   * their members, and maps yield their keys.
   */
  public static Iterator<SAFEValue> elements(final SAFEValue iterable) {
    final List<SAFEValue> items =
        switch (iterable) {
          case SAFEValue.ListValue list -> list.asList();
          case SAFEValue.StringValue(String chars) -> {
            final var result = new ArrayList<SAFEValue>(chars.length());
            for (var index = 0; index < chars.length(); index++) {
              result.add(SAFEValue.ofString(String.valueOf(chars.charAt(index))));
            }
            yield result;
          }
          case SAFEValue.SetValue set -> new ArrayList<>(set.asSet());
          case SAFEValue.MapValue map -> new ArrayList<>(map.asMap().keySet());
          default ->
              throw new SAFEException("For loop iterable must be a list, string, set, or map");
        };
    return items.iterator();
  }

  public static boolean hasNext(final Iterator<SAFEValue> iterator) {
    return iterator.hasNext();
  }

  public static SAFEValue next(final Iterator<SAFEValue> iterator) {
    return iterator.next();
  }

  /** Build a range list, mirroring {@code Interpreter.visitRange}. */
  public static SAFEValue range(final SAFEValue from, final SAFEValue to, final SAFEValue by) {
    final var start = from.asInt();
    final var end = to.asInt();
    final var step = by.asInt();
    if (step == 0) {
      throw new SAFEException("Range step cannot be zero");
    }
    if ((step > 0 && start > end) || (step < 0 && start < end)) {
      return SAFEValue.ofList(new ArrayList<>());
    }
    final var span = Math.abs(end / step - start / step) + 1;
    if (span > SAFEValue.MAX_LIST_SIZE || span < 0) {
      throw new SAFEException("range size exceeds maximum of " + SAFEValue.MAX_LIST_SIZE);
    }
    final var list = new ArrayList<SAFEValue>();
    if (step > 0) {
      for (var value = start; value <= end; value += step) {
        list.add(SAFEValue.ofInt(value));
        if (value > 0 && end - value < step) {
          break;
        }
      }
    } else {
      for (var value = start; value >= end; value += step) {
        list.add(SAFEValue.ofInt(value));
        if (value < 0 && end - value > step) {
          break;
        }
      }
    }
    return SAFEValue.ofList(list);
  }

  /** Evaluate a {@code while}-loop bound, rejecting negatives, mirroring the interpreter. */
  public static long bound(final SAFEValue value) {
    final var max = value.asInt();
    if (max < 0) {
      throw new SAFEException("While loop bound must be non-negative, got " + max);
    }
    return max;
  }

  public static SAFEValue closure(final String name, final SAFEValue[] captures) {
    return SAFEValue.ofFunction(Closure.bytecode(name, captures));
  }

  /**
   * Register the generated entry class so closures can dispatch to their lambda methods. Called
   * once from generated {@code main} before any program code runs.
   */
  public static void program(final Class<?> entry) {
    program = entry;
    methods.clear();
  }

  /** Invoke a function value (lambda) with {@code args}, dispatching to its generated method. */
  public static SAFEValue invoke(final SAFEValue function, final SAFEValue[] args) {
    final var closure = function.asClosure();
    final var target = method(closure.name());
    try {
      return (SAFEValue) target.invoke(null, closure.captures(), args);
    } catch (final java.lang.reflect.InvocationTargetException invocation) {
      final var cause = invocation.getCause();
      if (cause instanceof RuntimeException runtime) {
        throw runtime;
      }
      throw new SAFEException(cause == null ? "lambda failed" : cause.getMessage());
    } catch (final ReflectiveOperationException exception) {
      throw new SAFEException("Cannot invoke function: " + exception.getMessage());
    }
  }

  private static java.lang.reflect.Method method(final String name) {
    return methods.computeIfAbsent(
        name,
        key -> {
          try {
            final var found = program.getDeclaredMethod(key, SAFEValue[].class, SAFEValue[].class);
            found.setAccessible(true);
            return found;
          } catch (final NoSuchMethodException exception) {
            throw new SAFEException("Unknown function: " + key);
          }
        });
  }

  public static void store(
      final SAFEValue container, final SAFEValue[] indices, final SAFEValue value) {
    var current = container;
    for (var step = 0; step < indices.length - 1; step++) {
      final var key = indices[step];
      current =
          switch (current) {
            case SAFEValue.ListValue list -> list.element((int) key.asInt());
            case SAFEValue.MapValue map -> map.entry(key);
            default -> throw new SAFEException("Cannot index into " + current.type());
          };
    }
    final var last = indices[indices.length - 1];
    switch (current) {
      case SAFEValue.ListValue list -> list.setElement((int) last.asInt(), value);
      case SAFEValue.MapValue map -> map.setEntry(last, value);
      default -> throw new SAFEException("Cannot index into " + current.type());
    }
  }

  public static long measureOf(final SAFEValue value) {
    return value.asInt();
  }

  public static void requires(final SAFEValue condition, final String name) {
    if (!condition.asBoolean()) {
      throw new SAFEException("Requires contract failed for function: " + name);
    }
  }

  public static void ensures(final SAFEValue condition, final String name) {
    if (!condition.asBoolean()) {
      throw new SAFEException("Ensures contract failed for function: " + name);
    }
  }

  public static void pushMeasure(final String name, final long measure) {
    final var stack = measures.computeIfAbsent(name, key -> new ArrayDeque<>());
    if (measure < 0) {
      throw new SAFEException("Decreases measure must be non-negative for: " + name);
    }
    if (!stack.isEmpty() && measure >= stack.peek()) {
      throw new SAFEException(
          "Decreases clause not satisfied for: "
              + name
              + " (measure "
              + measure
              + " did not decrease below "
              + stack.peek()
              + ")");
    }
    stack.push(measure);
  }

  public static void popMeasure(final String name) {
    final var stack = measures.get(name);
    if (stack != null && !stack.isEmpty()) {
      stack.pop();
    }
  }

  public static void arguments(final String[] values) {
    arguments.clear();
    arguments.addAll(Arrays.asList(values));
  }

  public static void cleanup() {
    BuiltinRegistration.closeHandles(handles, binaries);
  }
}
