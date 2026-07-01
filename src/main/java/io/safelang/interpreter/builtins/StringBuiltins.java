package io.safelang.interpreter.builtins;

import io.safelang.interpreter.InterpreterException;
import io.safelang.runtime.BuiltinExecutors;
import io.safelang.runtime.SAFEValue;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

public final class StringBuiltins {

  // Step budget for a single regex evaluation. The Java regex engine reads the input via charAt
  // during (back)tracking, so a pathological pattern like (a+)+b trips this and traps instead of
  // hanging the host. Package-private and non-final so tests can shrink it.
  static long MAX_REGEX_STEPS = 10_000_000L;

  // Compiled patterns are reused across calls (they were recompiled every invocation before).
  private static final Map<String, Pattern> PATTERNS =
      Collections.synchronizedMap(
          new LinkedHashMap<>(64, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(final Map.Entry<String, Pattern> eldest) {
              return size() > 256;
            }
          });

  private StringBuiltins() {}

  private static Pattern pattern(final String regex) {
    return PATTERNS.computeIfAbsent(regex, Pattern::compile);
  }

  /** Wrap {@code input} so the regex engine trips {@link #MAX_REGEX_STEPS} instead of hanging. */
  private static CharSequence bounded(final String input) {
    return new BoundedCharSequence(input, new long[] {MAX_REGEX_STEPS});
  }

  /** Thrown when a regex evaluation exceeds the step budget (catastrophic backtracking guard). */
  private static final class RegexBudgetExceeded extends RuntimeException {}

  private static final class BoundedCharSequence implements CharSequence {
    private final CharSequence inner;
    private final long[] budget; // shared across subSequence views

    BoundedCharSequence(final CharSequence inner, final long[] budget) {
      this.inner = inner;
      this.budget = budget;
    }

    @Override
    public int length() {
      return inner.length();
    }

    @Override
    public char charAt(final int index) {
      if (--budget[0] < 0) {
        throw new RegexBudgetExceeded();
      }
      return inner.charAt(index);
    }

    @Override
    public CharSequence subSequence(final int start, final int end) {
      return new BoundedCharSequence(inner.subSequence(start, end), budget);
    }

    @Override
    public String toString() {
      return inner.toString();
    }
  }

  public static void register(final BuiltinExecutors executors) {
    executors.register(
        "substring",
        args -> {
          final var source = args.getFirst().asString();
          final var start = (int) args.get(1).asInt();
          final var end = (int) args.get(2).asInt();
          if (start < 0 || end < start || end > source.length()) {
            throw new InterpreterException(
                "substring: index out of bounds (start="
                    + start
                    + ", end="
                    + end
                    + ", length="
                    + source.length()
                    + ")");
          }
          return SAFEValue.ofString(source.substring(start, end));
        });

    executors.register(
        "indexOf",
        args -> {
          final var source = args.getFirst().asString();
          final var target = args.get(1).asString();
          return SAFEValue.ofInt(source.indexOf(target));
        });

    executors.register(
        "charAt",
        args -> {
          final var source = args.getFirst().asString();
          final var position = (int) args.get(1).asInt();
          if (position < 0 || position >= source.length()) {
            throw new InterpreterException(
                "charAt: index out of bounds (" + position + ", length=" + source.length() + ")");
          }
          return SAFEValue.ofString(String.valueOf(source.charAt(position)));
        });

    executors.register(
        "split",
        args -> {
          final var source = args.getFirst().asString();
          final var delimiter = args.get(1).asString();
          final var parts = source.split(Pattern.quote(delimiter), -1);
          final List<SAFEValue> result = new ArrayList<>();
          for (final var part : parts) {
            result.add(SAFEValue.ofString(part));
          }
          return SAFEValue.ofList(result);
        });

    executors.register("trim", args -> SAFEValue.ofString(args.getFirst().asString().trim()));

    executors.register(
        "upper", args -> SAFEValue.ofString(args.getFirst().asString().toUpperCase()));

    executors.register(
        "lower", args -> SAFEValue.ofString(args.getFirst().asString().toLowerCase()));

    executors.register(
        "replace",
        args -> {
          final var source = args.getFirst().asString();
          final var target = args.get(1).asString();
          final var replacement = args.get(2).asString();
          return SAFEValue.ofString(source.replace(target, replacement));
        });

    executors.register(
        "starts",
        args -> SAFEValue.ofBoolean(args.getFirst().asString().startsWith(args.get(1).asString())));

    executors.register(
        "ends",
        args -> SAFEValue.ofBoolean(args.getFirst().asString().endsWith(args.get(1).asString())));

    executors.register(
        "join",
        args -> {
          final var list = args.getFirst().asList();
          final var delimiter = args.get(1).asString();
          final var builder = new StringBuilder();
          for (int i = 0; i < list.size(); i++) {
            if (i > 0) builder.append(delimiter);
            builder.append(list.get(i).asString());
          }
          return SAFEValue.ofString(builder.toString());
        });

    executors.register(
        "chars",
        args -> {
          final var source = args.getFirst().asString();
          final List<SAFEValue> result = new ArrayList<>();
          for (int i = 0; i < source.length(); i++) {
            result.add(SAFEValue.ofString(String.valueOf(source.charAt(i))));
          }
          return SAFEValue.ofList(result);
        });

    // B4 — Regex. Each match runs against a step-bounded view of the input so a pathological
    // pattern (catastrophic backtracking) traps instead of hanging the host.
    executors.register(
        "matches",
        args -> {
          try {
            return SAFEValue.ofBoolean(
                pattern(args.get(1).asString())
                    .matcher(bounded(args.getFirst().asString()))
                    .matches());
          } catch (final PatternSyntaxException exception) {
            throw new InterpreterException(
                "Invalid regex pattern: " + exception.getMessage(), exception);
          } catch (final RegexBudgetExceeded exceeded) {
            throw new InterpreterException(
                "Regex evaluation exceeded the " + MAX_REGEX_STEPS + "-step budget");
          }
        });

    executors.register(
        "findall",
        args -> {
          try {
            final var matcher =
                pattern(args.get(1).asString()).matcher(bounded(args.getFirst().asString()));
            final List<SAFEValue> results = new ArrayList<>();
            final int limit = 100_000;
            int found = 0;
            while (matcher.find() && found < limit) {
              results.add(SAFEValue.ofString(matcher.group()));
              found++;
            }
            return SAFEValue.ofList(results);
          } catch (final PatternSyntaxException exception) {
            throw new InterpreterException(
                "Invalid regex pattern: " + exception.getMessage(), exception);
          } catch (final RegexBudgetExceeded exceeded) {
            throw new InterpreterException(
                "Regex evaluation exceeded the " + MAX_REGEX_STEPS + "-step budget");
          }
        });

    executors.register(
        "replaceall",
        args -> {
          try {
            return SAFEValue.ofString(
                pattern(args.get(1).asString())
                    .matcher(bounded(args.getFirst().asString()))
                    .replaceAll(args.get(2).asString()));
          } catch (final PatternSyntaxException exception) {
            throw new InterpreterException(
                "Invalid regex pattern: " + exception.getMessage(), exception);
          } catch (final RegexBudgetExceeded exceeded) {
            throw new InterpreterException(
                "Regex evaluation exceeded the " + MAX_REGEX_STEPS + "-step budget");
          }
        });
  }
}
