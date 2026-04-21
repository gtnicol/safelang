package io.safelang.interpreter.builtins;

import io.safelang.interpreter.InterpreterException;
import io.safelang.runtime.BuiltinExecutors;
import io.safelang.runtime.SAFEValue;
import java.util.Random;
import java.util.function.DoubleUnaryOperator;

public final class MathBuiltins {

  private MathBuiltins() {}

  public static void register(final BuiltinExecutors executors, final Random[] random) {
    operator(executors, "sqrt", Math::sqrt);
    operator(executors, "log", Math::log);
    operator(executors, "sin", Math::sin);
    operator(executors, "cos", Math::cos);
    operator(executors, "tan", Math::tan);
    operator(executors, "asin", Math::asin);
    operator(executors, "acos", Math::acos);
    operator(executors, "atan", Math::atan);
    operator(executors, "exp", Math::exp);
    operator(executors, "log10", Math::log10);

    executors.register(
        "pow",
        args -> SAFEValue.ofFloat(Math.pow(args.getFirst().asFloat(), args.get(1).asFloat())));

    executors.register(
        "abs",
        args -> {
          if (args.getFirst() instanceof SAFEValue.IntValue(long v)) {
            if (v == Long.MIN_VALUE) {
              throw new InterpreterException(
                  "Integer overflow: abs(" + v + ") is not representable");
            }
            return SAFEValue.ofInt(Math.abs(v));
          }
          return SAFEValue.ofFloat(Math.abs(args.getFirst().asFloat()));
        });

    executors.register(
        "min",
        args -> {
          final var left = args.getFirst();
          final var right = args.get(1);
          if (left.isInt() && right.isInt()) {
            return SAFEValue.ofInt(Math.min(left.asInt(), right.asInt()));
          }
          return SAFEValue.ofFloat(Math.min(left.asFloat(), right.asFloat()));
        });

    executors.register(
        "max",
        args -> {
          final var left = args.getFirst();
          final var right = args.get(1);
          if (left.isInt() && right.isInt()) {
            return SAFEValue.ofInt(Math.max(left.asInt(), right.asInt()));
          }
          return SAFEValue.ofFloat(Math.max(left.asFloat(), right.asFloat()));
        });

    executors.register(
        "floor", args -> SAFEValue.ofInt((long) Math.floor(args.getFirst().asFloat())));

    executors.register(
        "ceil", args -> SAFEValue.ofInt((long) Math.ceil(args.getFirst().asFloat())));

    executors.register("round", args -> SAFEValue.ofInt(Math.round(args.getFirst().asFloat())));

    executors.register(
        "atan2",
        args -> SAFEValue.ofFloat(Math.atan2(args.getFirst().asFloat(), args.get(1).asFloat())));

    // B2 — Random
    executors.register("rand", args -> SAFEValue.ofFloat(random[0].nextDouble()));

    executors.register(
        "randint",
        args -> {
          final var low = args.getFirst().asInt();
          final var high = args.get(1).asInt();
          if (high <= low) return SAFEValue.ofInt(low);
          return SAFEValue.ofInt(low + (long) (random[0].nextDouble() * (high - low)));
        });

    executors.register(
        "seed",
        args -> {
          random[0] = new Random(args.getFirst().asInt());
          return SAFEValue.ofVoid();
        });
  }

  private static void operator(
      final BuiltinExecutors executors, final String name, final DoubleUnaryOperator op) {
    executors.register(
        name, args -> SAFEValue.ofFloat(op.applyAsDouble(args.getFirst().asFloat())));
  }
}
