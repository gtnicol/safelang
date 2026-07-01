package io.safelang.interpreter.builtins;

import io.safelang.interpreter.InterpreterException;
import io.safelang.runtime.BuiltinExecutors;
import io.safelang.runtime.FileHandle;
import io.safelang.runtime.SAFEValue;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

public final class FileBuiltins {

  // Bound whole-file reads (and the write-handle buffer) so a guest script cannot exhaust the host
  // heap by opening a multi-gigabyte file. Package-private and non-final so tests can shrink it.
  static long MAX_FILE_BYTES = 64L * 1024 * 1024; // 64 MiB

  private FileBuiltins() {}

  /** True when {@code path} is a regular file larger than {@link #MAX_FILE_BYTES}. */
  private static boolean oversized(final Path path) {
    try {
      return Files.isRegularFile(path) && Files.size(path) > MAX_FILE_BYTES;
    } catch (final IOException exception) {
      return false; // let the actual read surface the I/O error
    }
  }

  public static void register(
      final BuiltinExecutors executors,
      final Map<Integer, FileHandle> handles,
      final AtomicInteger counter,
      final io.safelang.runtime.HostPolicy policy) {
    // Simple file I/O
    executors.register(
        "read",
        args -> {
          try {
            final var path = args.getFirst().asString();
            final var resolved = policy.resolve(path);
            if (oversized(resolved)) {
              throw new InterpreterException(
                  "File exceeds the " + MAX_FILE_BYTES + "-byte read limit: " + path);
            }
            return SAFEValue.ofString(Files.readString(resolved));
          } catch (IOException exception) {
            throw new InterpreterException(
                "Cannot read file: " + exception.getMessage(), exception);
          }
        });

    executors.register(
        "write",
        args -> {
          try {
            final var path = args.getFirst().asString();
            final var content = args.get(1).asString();
            Files.writeString(policy.resolve(path), content);
            return SAFEValue.ofVoid();
          } catch (IOException exception) {
            throw new InterpreterException(
                "Cannot write file: " + exception.getMessage(), exception);
          }
        });

    executors.register(
        "appendfile",
        args -> {
          try {
            final var path = args.getFirst().asString();
            final var content = args.get(1).asString();
            Files.writeString(
                policy.resolve(path),
                content,
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND);
            return SAFEValue.ofVoid();
          } catch (IOException exception) {
            throw new InterpreterException(
                "Cannot append to file: " + exception.getMessage(), exception);
          }
        });

    executors.register(
        "exists",
        args -> {
          try {
            final var path = args.getFirst().asString();
            return SAFEValue.ofBoolean(Files.exists(policy.resolve(path)));
          } catch (IOException escape) {
            return SAFEValue.ofBoolean(false); // outside the jail ⇒ not visible
          }
        });

    executors.register(
        "delete",
        args -> {
          try {
            final var path = args.getFirst().asString();
            return SAFEValue.ofBoolean(Files.deleteIfExists(policy.resolve(path)));
          } catch (IOException exception) {
            throw new InterpreterException(
                "Cannot delete file: " + exception.getMessage(), exception);
          }
        });

    executors.register(
        "lines",
        args -> {
          try {
            final var path = args.getFirst().asString();
            final var resolved = policy.resolve(path);
            if (oversized(resolved)) {
              throw new InterpreterException(
                  "File exceeds the " + MAX_FILE_BYTES + "-byte read limit: " + path);
            }
            final var lines = Files.readAllLines(resolved);
            final List<SAFEValue> result = new ArrayList<>();
            for (final var line : lines) {
              result.add(SAFEValue.ofString(line));
            }
            return SAFEValue.ofList(result);
          } catch (IOException exception) {
            throw new InterpreterException(
                "Cannot read file: " + exception.getMessage(), exception);
          }
        });

    // Handle-based file I/O
    executors.register(
        "fileopen",
        args -> {
          final var path = args.getFirst().asString();
          final var mode = args.get(1).asString();
          if (!"r".equals(mode) && !"w".equals(mode) && !"a".equals(mode)) {
            return SAFEValue.ofEnum(
                "OpenResult",
                "Err",
                List.of(SAFEValue.ofString("Invalid mode: " + mode + ". Use r, w, or a")));
          }
          final Path resolved;
          try {
            resolved = policy.resolve(path);
          } catch (IOException escape) {
            return SAFEValue.ofEnum(
                "OpenResult",
                "Err",
                List.of(SAFEValue.ofString("Cannot open file: " + escape.getMessage())));
          }
          final var id = counter.getAndIncrement();
          final var handle = new FileHandle(id, resolved.toString(), mode);
          if ("r".equals(mode)) {
            if (oversized(resolved)) {
              return SAFEValue.ofEnum(
                  "OpenResult",
                  "Err",
                  List.of(
                      SAFEValue.ofString(
                          "File exceeds the " + MAX_FILE_BYTES + "-byte read limit: " + path)));
            }
            try {
              final var content = Files.readString(resolved);
              handle.setContent(content);
            } catch (IOException exception) {
              return SAFEValue.ofEnum(
                  "OpenResult",
                  "Err",
                  List.of(SAFEValue.ofString("Cannot open file: " + exception.getMessage())));
            }
          } else {
            handle.setBuffer(new StringBuilder());
          }
          handles.put(id, handle);
          return SAFEValue.ofEnum("OpenResult", "Ok", List.of(file(id, path)));
        });

    executors.register(
        "fileclose",
        args -> {
          final var id = (int) args.getFirst().asInt();
          final var handle = handles.get(id);
          if (handle == null || !handle.isOpen()) {
            return SAFEValue.ofVoid();
          }
          // close() flushes the write/append buffer to disk (the same flush the interpreter/VM
          // cleanup path now invokes), so an early-exiting script does not lose buffered writes.
          try {
            handle.close();
          } catch (IOException exception) {
            throw new InterpreterException(
                "Cannot write file on close: " + exception.getMessage(), exception);
          } finally {
            handles.remove(id);
          }
          return SAFEValue.ofVoid();
        });

    executors.register(
        "fileread",
        args -> {
          final var id = (int) args.getFirst().asInt();
          final var handle = handles.get(id);
          if (handle == null || !handle.isOpen()) {
            return SAFEValue.ofEnum(
                "ReadResult", "Err", List.of(SAFEValue.ofString("Invalid or closed file handle")));
          }
          if (!"r".equals(handle.mode())) {
            return SAFEValue.ofEnum(
                "ReadResult",
                "Err",
                List.of(SAFEValue.ofString("Cannot read from a write-mode handle")));
          }
          return SAFEValue.ofEnum(
              "ReadResult", "Ok", List.of(SAFEValue.ofString(handle.content())));
        });

    executors.register(
        "filewrite",
        args -> {
          final var id = (int) args.getFirst().asInt();
          final var content = args.get(1).asString();
          final var handle = handles.get(id);
          if (handle == null || !handle.isOpen()) {
            return SAFEValue.ofEnum(
                "WriteResult", "Err", List.of(SAFEValue.ofString("Invalid or closed file handle")));
          }
          if ("r".equals(handle.mode())) {
            return SAFEValue.ofEnum(
                "WriteResult",
                "Err",
                List.of(SAFEValue.ofString("Cannot write to a read-mode handle")));
          }
          // The write handle buffers in memory until fileclose — bound it like a read.
          if ((long) handle.buffer().length() + content.length() > MAX_FILE_BYTES) {
            return SAFEValue.ofEnum(
                "WriteResult",
                "Err",
                List.of(
                    SAFEValue.ofString(
                        "Buffered write exceeds the " + MAX_FILE_BYTES + "-byte limit")));
          }
          handle.buffer().append(content);
          return SAFEValue.ofEnum("WriteResult", "Done", List.of());
        });

    executors.register(
        "filereadlines",
        args -> {
          final var id = (int) args.getFirst().asInt();
          final var handle = handles.get(id);
          if (handle == null || !handle.isOpen()) {
            return SAFEValue.ofEnum(
                "LinesResult", "Err", List.of(SAFEValue.ofString("Invalid or closed file handle")));
          }
          if (!"r".equals(handle.mode())) {
            return SAFEValue.ofEnum(
                "LinesResult",
                "Err",
                List.of(SAFEValue.ofString("Cannot read lines from a write-mode handle")));
          }
          final var lines = handle.content().split("\n", -1);
          final List<SAFEValue> result = new ArrayList<>();
          for (final var line : lines) {
            result.add(SAFEValue.ofString(line));
          }
          return SAFEValue.ofEnum("LinesResult", "Ok", List.of(SAFEValue.ofList(result)));
        });

    executors.register(
        "filevalid",
        args -> {
          final var id = (int) args.getFirst().asInt();
          final var handle = handles.get(id);
          return SAFEValue.ofBoolean(handle != null && handle.isOpen());
        });

    executors.register(
        "fileload",
        args -> {
          final var path = args.getFirst().asString();
          try {
            final var resolved = policy.resolve(path);
            if (oversized(resolved)) {
              return SAFEValue.ofEnum(
                  "ReadResult",
                  "Err",
                  List.of(
                      SAFEValue.ofString(
                          "File exceeds the " + MAX_FILE_BYTES + "-byte read limit: " + path)));
            }
            final var content = Files.readString(resolved);
            return SAFEValue.ofEnum("ReadResult", "Ok", List.of(SAFEValue.ofString(content)));
          } catch (IOException exception) {
            return SAFEValue.ofEnum(
                "ReadResult",
                "Err",
                List.of(SAFEValue.ofString("Cannot read file: " + exception.getMessage())));
          }
        });

    executors.register(
        "filesave",
        args -> {
          final var path = args.getFirst().asString();
          final var content = args.get(1).asString();
          try {
            Files.writeString(policy.resolve(path), content);
            return SAFEValue.ofEnum("WriteResult", "Done", List.of());
          } catch (IOException exception) {
            return SAFEValue.ofEnum(
                "WriteResult",
                "Err",
                List.of(SAFEValue.ofString("Cannot write file: " + exception.getMessage())));
          }
        });

    // B5 — Directory ops
    executors.register(
        "listdir",
        args -> {
          final var path = args.getFirst().asString();
          // Sort by filename so iteration order is deterministic across
          // filesystems — Files.list() yields OS-specific order otherwise.
          try (var stream = Files.list(policy.resolve(path))) {
            final var results = new ArrayList<SAFEValue>();
            stream
                .map(p -> p.getFileName().toString())
                .sorted()
                .forEach(name -> results.add(SAFEValue.ofString(name)));
            return SAFEValue.ofList(results);
          } catch (final IOException exception) {
            throw new InterpreterException(
                "Cannot list directory '" + path + "': " + exception.getMessage(), exception);
          }
        });

    executors.register(
        "mkdir",
        args -> {
          try {
            Files.createDirectories(policy.resolve(args.getFirst().asString()));
            return SAFEValue.ofBoolean(true);
          } catch (IOException e) {
            return SAFEValue.ofBoolean(false);
          }
        });

    executors.register(
        "rmdir",
        args -> {
          try {
            final var path = policy.resolve(args.getFirst().asString());
            if (!Files.isDirectory(path)) {
              return SAFEValue.ofBoolean(false);
            }
            return SAFEValue.ofBoolean(Files.deleteIfExists(path));
          } catch (IOException e) {
            return SAFEValue.ofBoolean(false);
          }
        });

    executors.register(
        "isdir",
        args -> {
          try {
            return SAFEValue.ofBoolean(
                Files.isDirectory(policy.resolve(args.getFirst().asString())));
          } catch (IOException escape) {
            return SAFEValue.ofBoolean(false);
          }
        });
  }

  private static SAFEValue file(final int id, final String path) {
    final Map<String, SAFEValue> fields = new LinkedHashMap<>();
    fields.put("id", SAFEValue.ofInt(id));
    fields.put("path", SAFEValue.ofString(path));
    return SAFEValue.ofObject("File", fields);
  }
}
