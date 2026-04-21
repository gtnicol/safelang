package io.safelang.interpreter.builtins;

import io.safelang.interpreter.InterpreterException;
import io.safelang.runtime.BinaryFileHandle;
import io.safelang.runtime.BuiltinExecutors;
import io.safelang.runtime.SAFEValue;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

public final class BinaryBuiltins {

  private BinaryBuiltins() {}

  public static void register(
      final BuiltinExecutors executors,
      final Map<Integer, BinaryFileHandle> handles,
      final AtomicInteger counter) {
    // balloc(int) -> bytes
    executors.register(
        "balloc",
        args -> {
          final var size = (int) args.getFirst().asInt();
          if (size < 0 || size > SAFEValue.MAX_LIST_SIZE) {
            throw new InterpreterException("balloc: invalid size " + size);
          }
          return SAFEValue.ofBytes(new byte[size]);
        });

    // bget(bytes, int) -> int
    executors.register(
        "bget",
        args -> {
          final var data = args.getFirst().asBytes();
          final var index = (int) args.get(1).asInt();
          if (index < 0 || index >= data.length) {
            throw new InterpreterException(
                "bget: index out of bounds " + index + " (length " + data.length + ")");
          }
          return SAFEValue.ofInt(data[index] & 0xFF);
        });

    // bset(bytes, int, int) -> bytes
    executors.register(
        "bset",
        args -> {
          // Explicit clone — we need to mutate without affecting the source
          // SAFEValue. Phase 3 of the third-round audit fix made asBytes()
          // return a live reference, so the COW step is now visible at the
          // call site instead of hidden inside the accessor.
          final var data = args.getFirst().asBytes().clone();
          final var index = (int) args.get(1).asInt();
          final var value = (int) args.get(2).asInt();
          if (index < 0 || index >= data.length) {
            throw new InterpreterException("bset: index out of bounds " + index);
          }
          if (value < 0 || value > 255) {
            throw new InterpreterException("bset: value must be 0-255, got " + value);
          }
          data[index] = (byte) value;
          return SAFEValue.ofBytes(data);
        });

    // bslice(bytes, int, int) -> bytes
    executors.register(
        "bslice",
        args -> {
          final var data = args.getFirst().asBytes();
          final var start = (int) args.get(1).asInt();
          final var end = (int) args.get(2).asInt();
          if (start < 0 || end < start || end > data.length) {
            throw new InterpreterException(
                "bslice: invalid range [" + start + ", " + end + ") for length " + data.length);
          }
          return SAFEValue.ofBytes(Arrays.copyOfRange(data, start, end));
        });

    // bconcat(bytes, bytes) -> bytes
    executors.register("bconcat", args -> SAFEValue.add(args.getFirst(), args.get(1)));

    // bencode(string) -> bytes
    executors.register(
        "bencode",
        args -> SAFEValue.ofBytes(args.getFirst().asString().getBytes(StandardCharsets.UTF_8)));

    // bdecode(bytes) -> string
    executors.register(
        "bdecode",
        args -> SAFEValue.ofString(new String(args.getFirst().asBytes(), StandardCharsets.UTF_8)));

    // bpack(int, int) -> bytes
    executors.register(
        "bpack",
        args -> {
          final var value = args.getFirst().asInt();
          final var width = (int) args.get(1).asInt();
          if (width != 1 && width != 2 && width != 4 && width != 8) {
            throw new InterpreterException("bpack: width must be 1, 2, 4, or 8, got " + width);
          }
          final var result = new byte[width];
          for (int i = width - 1; i >= 0; i--) {
            result[i] = (byte) (value >> ((width - 1 - i) * 8));
          }
          return SAFEValue.ofBytes(result);
        });

    // bunpack(bytes, int, int) -> int
    executors.register(
        "bunpack",
        args -> {
          final var data = args.getFirst().asBytes();
          final var offset = (int) args.get(1).asInt();
          final var width = (int) args.get(2).asInt();
          if (width != 1 && width != 2 && width != 4 && width != 8) {
            throw new InterpreterException("bunpack: width must be 1, 2, 4, or 8, got " + width);
          }
          if (offset < 0 || offset + width > data.length) {
            throw new InterpreterException(
                "bunpack: out of bounds at offset " + offset + " width " + width);
          }
          long result = 0;
          for (int i = 0; i < width; i++) {
            result = (result << 8) | (data[offset + i] & 0xFF);
          }
          return SAFEValue.ofInt(result);
        });

    // bpatch(bytes, int, bytes) -> bytes
    executors.register(
        "bpatch",
        args -> {
          // Explicit clone — see bset for the rationale.
          final var data = args.getFirst().asBytes().clone();
          final var offset = (int) args.get(1).asInt();
          final var patch = args.get(2).asBytes();
          if (offset < 0 || offset + patch.length > data.length) {
            throw new InterpreterException(
                "bpatch: patch at "
                    + offset
                    + " length "
                    + patch.length
                    + " exceeds buffer length "
                    + data.length);
          }
          System.arraycopy(patch, 0, data, offset, patch.length);
          return SAFEValue.ofBytes(data);
        });

    // bcompare(bytes, bytes) -> int
    executors.register(
        "bcompare",
        args -> {
          final var result =
              Arrays.compareUnsigned(args.getFirst().asBytes(), args.get(1).asBytes());
          return SAFEValue.ofInt(result < 0 ? -1 : result > 0 ? 1 : 0);
        });

    // bhex(bytes) -> string
    executors.register("bhex", args -> SAFEValue.ofString(args.getFirst().asString()));

    // Binary file I/O

    // bopen(string, string) -> int
    executors.register(
        "bopen",
        args -> {
          final var path = args.getFirst().asString();
          final var mode = args.get(1).asString();
          try {
            final var id = counter.getAndIncrement();
            final var handle = new BinaryFileHandle(id, path, mode);
            handles.put(id, handle);
            return SAFEValue.ofInt(id);
          } catch (IOException exception) {
            throw new InterpreterException(
                "bopen: cannot open file: " + exception.getMessage(), exception);
          }
        });

    // bclose(int) -> void
    executors.register(
        "bclose",
        args -> {
          final var id = (int) args.getFirst().asInt();
          final var handle = handles.get(id);
          if (handle != null) {
            try {
              handle.close();
            } catch (IOException exception) {
              throw new InterpreterException("bclose: " + exception.getMessage(), exception);
            } finally {
              handles.remove(id);
            }
          }
          return SAFEValue.ofVoid();
        });

    // bread(int, int) -> bytes
    executors.register(
        "bread",
        args -> {
          final var id = (int) args.getFirst().asInt();
          final var count = (int) args.get(1).asInt();
          final var handle = handles.get(id);
          if (handle == null) throw new InterpreterException("bread: invalid handle " + id);
          try {
            return SAFEValue.ofBytes(handle.read(count));
          } catch (IOException exception) {
            throw new InterpreterException("bread: " + exception.getMessage(), exception);
          }
        });

    // bwrite(int, bytes) -> int
    executors.register(
        "bwrite",
        args -> {
          final var id = (int) args.getFirst().asInt();
          final var data = args.get(1).asBytes();
          final var handle = handles.get(id);
          if (handle == null) throw new InterpreterException("bwrite: invalid handle " + id);
          try {
            handle.write(data);
            return SAFEValue.ofInt(data.length);
          } catch (IOException exception) {
            throw new InterpreterException("bwrite: " + exception.getMessage(), exception);
          }
        });

    // bseek(int, int) -> void
    executors.register(
        "bseek",
        args -> {
          final var id = (int) args.getFirst().asInt();
          final var offset = args.get(1).asInt();
          final var handle = handles.get(id);
          if (handle == null) throw new InterpreterException("bseek: invalid handle " + id);
          try {
            handle.seek(offset);
          } catch (IOException exception) {
            throw new InterpreterException("bseek: " + exception.getMessage(), exception);
          }
          return SAFEValue.ofVoid();
        });

    // bsize(string) -> int
    executors.register(
        "bsize",
        args -> {
          final var path = args.getFirst().asString();
          final var file = new File(path);
          return SAFEValue.ofInt(file.length());
        });

    // bflush(int) -> void
    executors.register(
        "bflush",
        args -> {
          final var id = (int) args.getFirst().asInt();
          final var handle = handles.get(id);
          if (handle == null) throw new InterpreterException("bflush: invalid handle " + id);
          try {
            handle.flush();
          } catch (IOException exception) {
            throw new InterpreterException("bflush: " + exception.getMessage(), exception);
          }
          return SAFEValue.ofVoid();
        });
  }
}
