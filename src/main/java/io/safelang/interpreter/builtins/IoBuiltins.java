package io.safelang.interpreter.builtins;

import io.safelang.interpreter.InterpreterException;
import io.safelang.runtime.BuiltinExecutors;
import io.safelang.runtime.SAFEValue;
import java.io.IOException;
import java.io.Writer;
import java.util.Scanner;
import java.util.function.Supplier;

public final class IoBuiltins {

  private IoBuiltins() {}

  public static void register(
      final BuiltinExecutors executors, final Supplier<Writer> output, final Scanner scanner) {
    executors.register(
        "print",
        args -> {
          final var text = args.getFirst().asString();
          final var writer = output.get();
          if (writer != null) {
            try {
              writer.write(text);
              writer.flush();
            } catch (IOException exception) {
              throw new InterpreterException(
                  "Failed to write output: " + exception.getMessage(), exception);
            }
          } else {
            System.out.print(text);
          }
          return SAFEValue.ofVoid();
        });

    executors.register(
        "println",
        args -> {
          final var text = args.getFirst().asString();
          final var writer = output.get();
          if (writer != null) {
            try {
              writer.write(text);
              writer.write(System.lineSeparator());
              writer.flush();
            } catch (IOException exception) {
              throw new InterpreterException(
                  "Failed to write output: " + exception.getMessage(), exception);
            }
          } else {
            System.out.println(text);
          }
          return SAFEValue.ofVoid();
        });

    executors.register(
        "input",
        args -> {
          final var prompt = args.getFirst().asString();
          final var writer = output.get();
          if (writer != null) {
            try {
              writer.write(prompt);
              writer.flush();
            } catch (IOException exception) {
              throw new InterpreterException(
                  "Failed to write output: " + exception.getMessage(), exception);
            }
          } else {
            System.out.print(prompt);
          }
          return SAFEValue.ofString(scanner.nextLine());
        });
  }
}
