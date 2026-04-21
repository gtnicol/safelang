package io.safelang.interpreter.builtins;

import io.safelang.interpreter.InterpreterException;
import io.safelang.runtime.BuiltinExecutors;
import io.safelang.runtime.SAFEValue;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;

public final class CollectionBuiltins {

  private CollectionBuiltins() {}

  public static void register(final BuiltinExecutors executors) {
    executors.register(
        "append",
        args -> {
          final var target = args.getFirst();
          if (!target.isList()) {
            throw new InterpreterException("append() first argument must be a list");
          }
          return SAFEValue.ofList(target.asPersistentList().append(args.get(1)));
        });

    executors.register(
        "keys",
        args -> {
          final var arg = args.getFirst();
          if (!arg.isMap()) {
            throw new InterpreterException("keys() requires a map argument");
          }
          return SAFEValue.ofList(new ArrayList<>(arg.asMap().keySet()));
        });

    executors.register(
        "values",
        args -> {
          final var arg = args.getFirst();
          if (!arg.isMap()) {
            throw new InterpreterException("values() requires a map argument");
          }
          return SAFEValue.ofList(new ArrayList<>(arg.asMap().values()));
        });

    executors.register(
        "contains",
        args ->
            switch (args.getFirst()) {
              case SAFEValue.MapValue map ->
                  SAFEValue.ofBoolean(map.asMap().containsKey(args.get(1)));
              case SAFEValue.SetValue set -> SAFEValue.ofBoolean(set.asSet().contains(args.get(1)));
              case SAFEValue.ListValue list ->
                  SAFEValue.ofBoolean(list.asList().contains(args.get(1)));
              default ->
                  throw new InterpreterException(
                      "contains() first argument must be a list, map, or set");
            });

    executors.register(
        "size",
        args ->
            switch (args.getFirst()) {
              case SAFEValue.ListValue list -> SAFEValue.ofInt(list.asList().size());
              case SAFEValue.MapValue map -> SAFEValue.ofInt(map.asMap().size());
              case SAFEValue.StringValue(String s) -> SAFEValue.ofInt(s.length());
              case SAFEValue.SetValue set -> SAFEValue.ofInt(set.asSet().size());
              default ->
                  throw new InterpreterException(
                      "size() requires a list, map, string, or set argument");
            });

    executors.register(
        "remove",
        args -> {
          final var list = args.getFirst().asList();
          final var position = (int) args.get(1).asInt();
          if (position < 0 || position >= list.size()) {
            throw new InterpreterException(
                "remove: index out of bounds (" + position + ", size=" + list.size() + ")");
          }
          final List<SAFEValue> result = new ArrayList<>(list);
          result.remove(position);
          return SAFEValue.ofList(result);
        });

    executors.register(
        "slice",
        args -> {
          final var list = args.getFirst().asList();
          final var start = (int) args.get(1).asInt();
          final var end = (int) args.get(2).asInt();
          if (start < 0 || end < start || end > list.size()) {
            throw new InterpreterException(
                "slice: index out of bounds (start="
                    + start
                    + ", end="
                    + end
                    + ", size="
                    + list.size()
                    + ")");
          }
          return SAFEValue.ofList(new ArrayList<>(list.subList(start, end)));
        });

    executors.register(
        "reverse",
        args -> {
          final var list = args.getFirst().asList();
          final List<SAFEValue> result = new ArrayList<>(list);
          Collections.reverse(result);
          return SAFEValue.ofList(result);
        });

    executors.register(
        "sort",
        args -> {
          final var list = args.getFirst().asList();
          final List<SAFEValue> result = new ArrayList<>(list);
          result.sort(
              (final SAFEValue left, final SAFEValue right) -> {
                if (left.isInt() && right.isInt()) return Long.compare(left.asInt(), right.asInt());
                if (left.isUint() && right.isUint())
                  return Long.compareUnsigned(left.asUint(), right.asUint());
                if (left.isFloat() || right.isFloat())
                  return Double.compare(left.asFloat(), right.asFloat());
                if (left.isString() && right.isString())
                  return left.asString().compareTo(right.asString());
                if (left.isBoolean() && right.isBoolean())
                  return Boolean.compare(left.asBoolean(), right.asBoolean());
                throw new InterpreterException(
                    "Cannot compare " + left.type() + " and " + right.type());
              });
          return SAFEValue.ofList(result);
        });

    // Set operations
    executors.register(
        "add",
        args -> {
          final var elements = new LinkedHashSet<>(args.getFirst().asSet());
          elements.add(args.get(1));
          return SAFEValue.ofSet(elements);
        });

    executors.register(
        "union",
        args -> {
          final var result = new LinkedHashSet<>(args.getFirst().asSet());
          result.addAll(args.get(1).asSet());
          return SAFEValue.ofSet(result);
        });

    executors.register(
        "intersect",
        args -> {
          final var result = new LinkedHashSet<>(args.getFirst().asSet());
          result.retainAll(args.get(1).asSet());
          return SAFEValue.ofSet(result);
        });

    executors.register(
        "difference",
        args -> {
          final var result = new LinkedHashSet<>(args.getFirst().asSet());
          result.removeAll(args.get(1).asSet());
          return SAFEValue.ofSet(result);
        });
  }
}
