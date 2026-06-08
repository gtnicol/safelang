package io.safelang.interpreter.builtins;

import io.safelang.interpreter.InterpreterException;
import io.safelang.runtime.BuiltinExecutors;
import io.safelang.runtime.SAFEValue;
import java.util.ArrayList;
import java.util.List;

public final class SystemBuiltins {

  private SystemBuiltins() {}

  public static void register(final BuiltinExecutors executors, final List<String> arguments) {
    executors.register(
        "len",
        args ->
            switch (args.getFirst()) {
              case SAFEValue.ListValue list -> SAFEValue.ofInt(list.asList().size());
              case SAFEValue.StringValue(String s) -> SAFEValue.ofInt(s.length());
              case SAFEValue.MapValue map -> SAFEValue.ofInt(map.asMap().size());
              case SAFEValue.SetValue set -> SAFEValue.ofInt(set.asSet().size());
              case SAFEValue.BytesValue(byte[] b) -> SAFEValue.ofInt(b.length);
              default ->
                  throw new InterpreterException(
                      "len() requires a list, string, map, set, or bytes argument");
            });

    // range(end) | range(start, end) | range(start, end, step). End-exclusive, Python-style;
    // step may be negative for a descending range. Curried by argument count.
    executors.register(
        "range",
        args -> {
          final long start;
          final long end;
          final long step;
          if (args.size() == 1) {
            start = 0;
            end = args.getFirst().asInt();
            step = 1;
          } else {
            start = args.getFirst().asInt();
            end = args.get(1).asInt();
            step = args.size() >= 3 ? args.get(2).asInt() : 1;
          }
          if (step == 0) {
            throw new InterpreterException("range step cannot be zero");
          }
          final List<SAFEValue> result = new ArrayList<>();
          if ((step > 0 && start >= end) || (step < 0 && start <= end)) {
            return SAFEValue.ofList(result);
          }
          // Overflow-safe element count: |end - start| via subtractExact (a span wider than
          // Long range is rejected, not wrapped), then ceil-divided by |step|.
          final long span;
          try {
            span = Math.subtractExact(step > 0 ? end : start, step > 0 ? start : end);
          } catch (final ArithmeticException overflow) {
            throw new InterpreterException(
                "range size exceeds maximum of " + SAFEValue.MAX_LIST_SIZE);
          }
          final var magnitude = Math.abs(step);
          final var size = span / magnitude + (span % magnitude == 0 ? 0 : 1);
          if (size > SAFEValue.MAX_LIST_SIZE || size < 0) {
            throw new InterpreterException(
                "range size " + size + " exceeds maximum of " + SAFEValue.MAX_LIST_SIZE);
          }
          for (long i = 0, value = start; i < size; i++, value += step) {
            result.add(SAFEValue.ofInt(value));
          }
          return SAFEValue.ofList(result);
        });

    executors.register("str", args -> SAFEValue.ofString(args.getFirst().asString()));

    executors.register("int", args -> SAFEValue.ofInt(args.getFirst().asInt()));

    executors.register("integer", args -> SAFEValue.ofInt(args.getFirst().asInt()));

    executors.register("float", args -> SAFEValue.ofFloat(args.getFirst().asFloat()));

    executors.register("decimal", args -> SAFEValue.ofFloat(args.getFirst().asFloat()));

    executors.register(
        "typeof", args -> SAFEValue.ofString(args.getFirst().type().name().toLowerCase()));

    executors.register(
        "exit",
        args -> {
          throw new io.safelang.interpreter.ExitException((int) args.getFirst().asInt());
        });

    executors.register(
        "args",
        args -> {
          final List<SAFEValue> result = new ArrayList<>();
          for (final var arg : arguments) {
            result.add(SAFEValue.ofString(arg));
          }
          return SAFEValue.ofList(result);
        });

    executors.register("time", args -> SAFEValue.ofInt(System.currentTimeMillis()));

    executors.register(
        "getenv",
        args -> {
          final var value = System.getenv(args.getFirst().asString());
          return SAFEValue.ofString(value != null ? value : "");
        });
  }
}
