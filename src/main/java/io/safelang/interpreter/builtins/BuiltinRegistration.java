package io.safelang.interpreter.builtins;

import io.safelang.runtime.BinaryFileHandle;
import io.safelang.runtime.BuiltinExecutors;
import io.safelang.runtime.FileHandle;
import java.io.Writer;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Scanner;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * Single entry point for registering every built-in executor with a fresh {@link BuiltinExecutors}
 * table. Both {@link io.safelang.interpreter.Interpreter} and {@link
 * io.safelang.bytecode.BytecodeVM} call this so the set of builtins and the order in which they are
 * registered stays in lockstep — adding or removing a category happens in one place instead of two.
 */
public final class BuiltinRegistration {

  private BuiltinRegistration() {}

  /**
   * Register every builtin category against {@code executors}. The supplier and handle tables let
   * callers install their own output writer / stdin scanner / random source / file-handle maps;
   * this helper does not own any shared state beyond delegating the eight {@code
   * *Builtins.register} calls.
   */
  public static void registerAll(
      final BuiltinExecutors executors,
      final Supplier<Writer> output,
      final Scanner scanner,
      final Random[] random,
      final Map<Integer, FileHandle> handles,
      final Map<Integer, BinaryFileHandle> binaries,
      final Map<Integer, io.safelang.runtime.StreamHandle> streams,
      final AtomicInteger counter,
      final List<String> arguments,
      final io.safelang.runtime.HostPolicy policy) {
    IoBuiltins.register(executors, output, scanner);
    MathBuiltins.register(executors, random);
    StringBuiltins.register(executors);
    CollectionBuiltins.register(executors);
    FileBuiltins.register(executors, handles, counter, policy);
    BinaryBuiltins.register(executors, binaries, counter, policy);
    StreamBuiltins.register(executors, streams, counter, policy);
    HashBuiltins.register(executors);
    SystemBuiltins.register(executors, arguments);
    HttpBuiltins.register(executors, policy);
    ExecBuiltins.register(executors, policy);
    gate(executors, policy.capabilities());
  }

  /**
   * Enforce the host capability policy at runtime: replace every builtin that requires a capability
   * the program was not granted with a stub that throws when called. This is the single seam shared
   * by the interpreter, bytecode VM, and JVM, so it protects the {@code .safeb} bytecode path that
   * source-level {@code --strict} analysis cannot reach.
   */
  private static void gate(
      final BuiltinExecutors executors, final io.safelang.runtime.Capabilities capabilities) {
    for (final var builtin : io.safelang.runtime.BuiltinRegistry.all()) {
      final var capability = io.safelang.runtime.BuiltinRegistry.capability(builtin.name());
      if (capability != null && !capabilities.granted(capability)) {
        executors.register(
            builtin.name(),
            args -> {
              throw new io.safelang.interpreter.InterpreterException(
                  "capability denied: '"
                      + capability
                      + "' is required by '"
                      + builtin.name()
                      + "' but was not granted to this program");
            });
      }
    }
  }

  /**
   * Close every open file and binary handle, swallowing close-time I/O errors. Matching shutdown
   * helper for the handle tables populated by {@link FileBuiltins} and {@link BinaryBuiltins}. Both
   * the interpreter and the bytecode VM call this from their {@code cleanup} path so the "close,
   * ignore errors, clear" sequence stays in one place.
   */
  public static void closeHandles(
      final Map<Integer, FileHandle> handles,
      final Map<Integer, BinaryFileHandle> binaries,
      final Map<Integer, io.safelang.runtime.StreamHandle> streams) {
    for (final var handle : handles.values()) {
      try {
        handle.close();
      } catch (Exception ignored) {
      }
    }
    handles.clear();
    for (final var handle : binaries.values()) {
      try {
        handle.close();
      } catch (Exception ignored) {
      }
    }
    binaries.clear();
    for (final var handle : streams.values()) {
      try {
        handle.close(); // flush + close any stream left open on early exit
      } catch (Exception ignored) {
      }
    }
    streams.clear();
  }
}
