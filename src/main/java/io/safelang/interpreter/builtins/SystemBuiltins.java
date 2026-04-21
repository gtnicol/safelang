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

    executors.register(
        "range",
        args -> {
          if (args.size() == 1) {
            final var end = args.getFirst().asInt();
            if (end > SAFEValue.MAX_LIST_SIZE) {
              throw new InterpreterException(
                  "range size " + end + " exceeds maximum of " + SAFEValue.MAX_LIST_SIZE);
            }
            final List<SAFEValue> result = new ArrayList<>();
            for (long i = 0; i < end; i++) {
              result.add(SAFEValue.ofInt(i));
            }
            return SAFEValue.ofList(result);
          } else {
            final var start = args.getFirst().asInt();
            final var end = args.get(1).asInt();
            final var count = end - start;
            if (count > SAFEValue.MAX_LIST_SIZE) {
              throw new InterpreterException(
                  "range size " + count + " exceeds maximum of " + SAFEValue.MAX_LIST_SIZE);
            }
            final List<SAFEValue> result = new ArrayList<>();
            for (long i = start; i < end; i++) {
              result.add(SAFEValue.ofInt(i));
            }
            return SAFEValue.ofList(result);
          }
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
