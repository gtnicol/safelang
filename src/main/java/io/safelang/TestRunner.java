package io.safelang;

import io.safelang.bytecode.*;
import io.safelang.compiler.CompilerFrontEnd;
import io.safelang.compiler.bytecode.*;
import io.safelang.compiler.c.CCodeGenerator;
import io.safelang.compiler.wasm.WasmPipeline;
import io.safelang.interpreter.Interpreter;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.stream.*;

public class TestRunner {

  private final boolean strict;
  private final boolean native_;
  private final boolean bytecode;
  private final boolean wasm;
  private final boolean jvm;

  TestRunner(final boolean strict, final boolean native_, final boolean bytecode) {
    this(strict, native_, bytecode, false, false);
  }

  TestRunner(
      final boolean strict, final boolean native_, final boolean bytecode, final boolean wasm) {
    this(strict, native_, bytecode, wasm, false);
  }

  TestRunner(
      final boolean strict,
      final boolean native_,
      final boolean bytecode,
      final boolean wasm,
      final boolean jvm) {
    this.strict = strict;
    this.native_ = native_;
    this.bytecode = bytecode;
    this.wasm = wasm;
    this.jvm = jvm;
  }

  int execute(final String target) {
    final var path = Path.of(target);
    final List<Path> files;

    if (Files.isDirectory(path)) {
      try (var stream = Files.list(path)) {
        files =
            stream
                .filter(f -> f.toString().endsWith(".safe"))
                .sorted()
                .collect(Collectors.toList());
      } catch (IOException e) {
        System.err.println("Error listing directory: " + e.getMessage());
        return 1;
      }
    } else {
      files = List.of(path);
    }

    if (files.isEmpty()) {
      System.err.println("No .safe files found in: " + target);
      return 1;
    }

    final var results = new ArrayList<Result>();
    for (final var file : files) {
      final var result = run(file);
      results.add(result);
      print(result);
    }

    summary(results);

    return results.stream().anyMatch(r -> r.failed() > 0 || r.error() != null) ? 1 : 0;
  }

  private Result run(final Path file) {
    if (wasm) {
      return runWasm(file);
    }
    if (jvm) {
      return runJvm(file);
    }
    if (native_) {
      return runNative(file);
    }
    if (bytecode) {
      return runBytecode(file);
    }
    return runInterpreted(file);
  }

  private Result runJvm(final Path file) {
    final var name = file.getFileName().toString();
    final var capture = new StringWriter();
    try {
      final var source = Files.readString(file);
      final var parsed = CompilerFrontEnd.parse(source, file.toString(), strict);
      SafeRuntime.emit(parsed.warnings());
      final var bytes =
          io.safelang.compiler.jvm.JvmBackend.classBytes(
              parsed.program(), parsed.registry(), "io/safelang/generated/Test");
      io.safelang.compiler.jvm.JvmRuntime.setOutput(capture);
      try {
        final var loaded = new ClassBytes().define("io.safelang.generated.Test", bytes);
        loaded.getMethod("main", String[].class).invoke(null, (Object) new String[0]);
      } finally {
        io.safelang.compiler.jvm.JvmRuntime.clearOutput();
      }
      return parse(name, capture.toString());
    } catch (Throwable throwable) {
      final var cause =
          throwable instanceof java.lang.reflect.InvocationTargetException invocation
                  && invocation.getCause() != null
              ? invocation.getCause()
              : throwable;
      final var result = parse(name, capture.toString());
      if (result.failed() == 0) {
        final var message =
            cause.getMessage() != null ? cause.getMessage() : cause.getClass().getSimpleName();
        final var failures = new ArrayList<>(result.failures());
        failures.add(message);
        return new Result(name, result.passed(), 1, failures, null);
      }
      return result;
    }
  }

  private static final class ClassBytes extends ClassLoader {
    Class<?> define(final String binaryName, final byte[] bytes) {
      return defineClass(binaryName, bytes, 0, bytes.length);
    }
  }

  private Result runInterpreted(final Path file) {
    final var name = file.getFileName().toString();
    final var capture = new StringWriter();
    try {
      final var source = Files.readString(file);
      final var parsed = CompilerFrontEnd.parse(source, file.toString(), strict);
      SafeRuntime.emit(parsed.warnings());
      final var interpreter = new Interpreter();
      interpreter.setRegistry(parsed.registry());
      interpreter.setOutput(capture);
      interpreter.interpret(parsed.program());
      return parse(name, capture.toString());
    } catch (Exception e) {
      final var message = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
      // Try to parse any output captured before the error
      return new Result(name, 0, 1, List.of(message), message);
    }
  }

  private Result runBytecode(final Path file) {
    final var name = file.getFileName().toString();
    final var capture = new StringWriter();
    try {
      final var source = Files.readString(file);
      final var parsed = CompilerFrontEnd.parse(source, file.toString(), strict);
      SafeRuntime.emit(parsed.warnings());
      final var compiler = new BytecodeCompiler();
      compiler.setRegistry(parsed.registry());
      final var module = compiler.compile(parsed.program());
      final var vm = new BytecodeVM(module);
      vm.setOutput(capture);
      vm.execute();
      return parse(name, capture.toString());
    } catch (Exception e) {
      final var message = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
      return new Result(name, 0, 1, List.of(message), message);
    }
  }

  private Result runNative(final Path file) {
    final var name = file.getFileName().toString();
    Path directory = null;
    try {
      final var source = Files.readString(file);
      final var parsed = CompilerFrontEnd.parse(source, file.toString(), strict);
      SafeRuntime.emit(parsed.warnings());
      final var generator = new CCodeGenerator();
      generator.setRegistry(parsed.registry());
      final var code = generator.generate(parsed.program());

      directory = Files.createTempDirectory("safe_test_");
      final var target = directory.resolve("test.c");
      Files.writeString(target, code);

      // Extract runtime headers (safe_runtime.h + its dependency safe_refcount.h)
      for (final var header : new String[] {"safe_runtime.h", "safe_refcount.h"}) {
        try (var stream = SafeMain.class.getResourceAsStream("/" + header)) {
          if (stream != null) {
            Files.write(directory.resolve(header), stream.readAllBytes());
          }
        }
      }

      // Compile via the shared driver — single source of truth for compiler
      // resolution, arguments, and stdout/stderr capture.
      final var binary = directory.resolve("test");
      try {
        io.safelang.compiler.c.CBuildDriver.build(target, binary);
      } catch (final SAFEException exception) {
        return new Result(name, 0, 1, List.of(exception.getMessage()), "C compilation failed");
      }

      // Run
      final var run = new ProcessBuilder(binary.toString()).redirectErrorStream(true).start();
      final var capture = new String(run.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
      final var exit = run.waitFor();

      final var result = parse(name, capture);
      if (exit != 0 && result.failed() == 0) {
        // Non-zero exit but no FAIL lines — assert killed the process
        final var failures = new ArrayList<>(result.failures());
        failures.add("Process exited with code " + exit);
        return new Result(name, result.passed(), 1, failures, null);
      }
      return result;
    } catch (Exception e) {
      final var message = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
      return new Result(name, 0, 1, List.of(message), message);
    } finally {
      if (directory != null) {
        cleanup(directory);
      }
    }
  }

  private Result runWasm(final Path file) {
    final var name = file.getFileName().toString();
    try {
      final var source = Files.readString(file);
      final var parsed = CompilerFrontEnd.parse(source, file.toString(), strict);
      SafeRuntime.emit(parsed.warnings());
      final var pipeline = new WasmPipeline(parsed.registry());
      final var compiled = pipeline.compile(parsed.program());

      // Drive wasmtime through WasmPipeline.runWithStatus so the production
      // command-building logic is the single source of truth — no more
      // duplicating --preload / --dir / --env construction here.
      final var status = WasmPipeline.runWithStatus(compiled, WasmPipeline.RunOptions.permissive());
      final var result = parse(name, status.output());
      if (status.exit() != 0 && result.failed() == 0) {
        final var failures = new ArrayList<>(result.failures());
        final var detail =
            status.output().isBlank()
                ? "wasmtime exited with code " + status.exit()
                : "wasmtime exited with code " + status.exit() + ": " + status.output().strip();
        failures.add(detail);
        return new Result(name, result.passed(), 1, failures, null);
      }
      return result;
    } catch (Exception exception) {
      final var message =
          exception.getMessage() != null
              ? exception.getMessage()
              : exception.getClass().getSimpleName();
      return new Result(name, 0, 1, List.of(message), message);
    }
  }

  private Result parse(final String file, final String output) {
    var passed = 0;
    var failed = 0;
    final var failures = new ArrayList<String>();

    for (final var line : output.split("\n")) {
      final var trimmed = line.trim();
      if (trimmed.startsWith("[PASS]")) {
        passed++;
      } else if (trimmed.startsWith("[FAIL]")) {
        failed++;
        failures.add(trimmed.substring(7).trim());
      }
    }

    return new Result(file, passed, failed, failures, null);
  }

  private void print(final Result result) {
    System.out.println("=== " + result.file() + " ===");
    if (result.error() != null) {
      System.out.println("  ERROR: " + result.error());
    } else {
      System.out.println("  " + result.passed() + " passed, " + result.failed() + " failed");
      for (final var failure : result.failures()) {
        System.out.println("  FAIL: " + failure);
      }
    }
    System.out.println();
  }

  private void summary(final List<Result> results) {
    System.out.println("=== RESULTS ===");
    var total = 0;
    var failures = 0;
    for (final var result : results) {
      final var status = result.error() != null ? "ERROR" : result.failed() > 0 ? "FAIL" : "OK";
      System.out.printf(
          "  %-30s %3d passed  %2d failed  [%s]%n",
          result.file(), result.passed(), result.failed(), status);
      total += result.passed();
      failures += result.failed();
    }
    System.out.println();
    System.out.println("TOTAL: " + total + " passed, " + failures + " failed");
  }

  private void cleanup(final Path directory) {
    try (var walk = Files.walk(directory)) {
      walk.sorted(Comparator.reverseOrder())
          .forEach(
              p -> {
                try {
                  Files.deleteIfExists(p);
                } catch (IOException ignored) {
                }
              });
    } catch (IOException ignored) {
    }
  }

  record Result(String file, int passed, int failed, List<String> failures, String error) {}
}
