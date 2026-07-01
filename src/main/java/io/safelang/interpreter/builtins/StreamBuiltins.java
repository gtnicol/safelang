package io.safelang.interpreter.builtins;

import io.safelang.interpreter.InterpreterException;
import io.safelang.runtime.BuiltinExecutors;
import io.safelang.runtime.SAFEValue;
import io.safelang.runtime.StreamHandle;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Streaming file I/O builtins ({@code s*}) — a handle API modeled on the {@code binary} module's
 * {@code b*} handles. Unlike the whole-file {@code file} builtins, these read/write incrementally
 * so large files are processed without buffering them into the heap. Each path is jailed via the
 * host policy; the builtins are registered under the {@code file} module so they require {@code
 * FILESYSTEM}.
 */
public final class StreamBuiltins {

  private StreamBuiltins() {}

  public static void register(
      final BuiltinExecutors executors,
      final Map<Integer, StreamHandle> streams,
      final AtomicInteger counter,
      final io.safelang.runtime.HostPolicy policy) {

    executors.register(
        "sopen",
        args -> {
          final var path = args.getFirst().asString();
          final var mode = args.get(1).asString();
          try {
            final var resolved = policy.resolve(path).toString();
            final var id = counter.getAndIncrement();
            streams.put(id, new StreamHandle(id, resolved, mode));
            return SAFEValue.ofEnum("StreamResult", "Ok", List.of(stream(id, resolved)));
          } catch (final IOException exception) {
            return SAFEValue.ofEnum(
                "StreamResult",
                "Err",
                List.of(SAFEValue.ofString("Cannot open stream: " + exception.getMessage())));
          }
        });

    executors.register(
        "sclose",
        args -> {
          final var handle = streams.remove(idOf(args.getFirst()));
          if (handle != null) {
            try {
              handle.close();
            } catch (final IOException exception) {
              throw new InterpreterException("sclose: " + exception.getMessage(), exception);
            }
          }
          return SAFEValue.ofVoid();
        });

    executors.register(
        "sline",
        args -> {
          final var handle = require(streams, args.getFirst(), "sline");
          try {
            final var line = handle.readLine();
            return line == null
                ? SAFEValue.ofEnum("LineResult", "End", List.of())
                : SAFEValue.ofEnum("LineResult", "Line", List.of(SAFEValue.ofString(line)));
          } catch (final IOException exception) {
            return SAFEValue.ofEnum(
                "LineResult",
                "Err",
                List.of(SAFEValue.ofString("sline: " + exception.getMessage())));
          }
        });

    executors.register(
        "sread",
        args -> {
          final var handle = require(streams, args.getFirst(), "sread");
          final var count = args.get(1).asInt();
          if (count < 0 || count > StreamHandle.maxRead()) {
            return SAFEValue.ofEnum(
                "ReadResult", "Err", List.of(SAFEValue.ofString("sread: invalid count " + count)));
          }
          try {
            return SAFEValue.ofEnum(
                "ReadResult", "Ok", List.of(SAFEValue.ofString(handle.read((int) count))));
          } catch (final IOException exception) {
            return SAFEValue.ofEnum(
                "ReadResult",
                "Err",
                List.of(SAFEValue.ofString("sread: " + exception.getMessage())));
          }
        });

    executors.register(
        "swrite",
        args -> {
          final var handle = require(streams, args.getFirst(), "swrite");
          try {
            handle.write(args.get(1).asString());
            return SAFEValue.ofEnum("WriteResult", "Done", List.of());
          } catch (final IOException exception) {
            return SAFEValue.ofEnum(
                "WriteResult",
                "Err",
                List.of(SAFEValue.ofString("swrite: " + exception.getMessage())));
          }
        });

    executors.register(
        "sflush",
        args -> {
          final var handle = require(streams, args.getFirst(), "sflush");
          try {
            handle.flush();
            return SAFEValue.ofEnum("WriteResult", "Done", List.of());
          } catch (final IOException exception) {
            return SAFEValue.ofEnum(
                "WriteResult",
                "Err",
                List.of(SAFEValue.ofString("sflush: " + exception.getMessage())));
          }
        });
  }

  private static int idOf(final SAFEValue stream) {
    return (int) stream.fields().get("id").asInt();
  }

  private static StreamHandle require(
      final Map<Integer, StreamHandle> streams, final SAFEValue stream, final String op) {
    final var handle = streams.get(idOf(stream));
    if (handle == null) {
      throw new InterpreterException(op + ": invalid stream handle");
    }
    return handle;
  }

  private static SAFEValue stream(final int id, final String path) {
    final Map<String, SAFEValue> fields = new LinkedHashMap<>();
    fields.put("id", SAFEValue.ofInt(id));
    fields.put("path", SAFEValue.ofString(path));
    return SAFEValue.ofObject("Stream", fields);
  }
}
