package io.safelang.interpreter.builtins;

import io.safelang.runtime.BuiltinExecutors;
import io.safelang.runtime.SAFEValue;
import java.util.zip.CRC32;

public final class HashBuiltins {

  private HashBuiltins() {}

  public static void register(final BuiltinExecutors executors) {
    // fnv(bytes) -> int — FNV-1a 64-bit
    executors.register(
        "fnv",
        args -> {
          final var data = args.getFirst().asBytes();
          long hash = 0xcbf29ce484222325L;
          for (final var b : data) {
            hash ^= (b & 0xFF);
            hash *= 0x100000001b3L;
          }
          return SAFEValue.ofInt(hash);
        });

    // crc(bytes) -> int — CRC32
    executors.register(
        "crc",
        args -> {
          final var data = args.getFirst().asBytes();
          final var crc = new CRC32();
          crc.update(data);
          return SAFEValue.ofInt(crc.getValue());
        });

    // murmur(bytes) -> int — MurmurHash3 128-bit (lower 64 bits)
    executors.register(
        "murmur",
        args -> {
          final var data = args.getFirst().asBytes();
          return SAFEValue.ofInt(murmur3(data));
        });
  }

  private static long murmur3(final byte[] data) {
    final long c1 = 0x87c37b91114253d5L;
    final long c2 = 0x4cf5ad432745937fL;
    final int length = data.length;
    long h1 = 0;
    final int blocks = length / 8;

    for (int i = 0; i < blocks; i++) {
      long k1 = 0;
      for (int j = 7; j >= 0; j--) {
        k1 = (k1 << 8) | (data[i * 8 + j] & 0xFF);
      }
      k1 *= c1;
      k1 = Long.rotateLeft(k1, 31);
      k1 *= c2;
      h1 ^= k1;
      h1 = Long.rotateLeft(h1, 27);
      h1 = h1 * 5 + 0x52dce729;
    }

    long k1 = 0;
    final int tail = blocks * 8;
    // MurmurHash3 tail mixing: each case deliberately falls through to the
    // next so bytes 7..1 all fold into k1 before the single case 1 mix.
    switch (length - tail) {
      case 7:
        k1 ^= ((long) data[tail + 6] & 0xFF) << 48;
        // fall through
      case 6:
        k1 ^= ((long) data[tail + 5] & 0xFF) << 40;
        // fall through
      case 5:
        k1 ^= ((long) data[tail + 4] & 0xFF) << 32;
        // fall through
      case 4:
        k1 ^= ((long) data[tail + 3] & 0xFF) << 24;
        // fall through
      case 3:
        k1 ^= ((long) data[tail + 2] & 0xFF) << 16;
        // fall through
      case 2:
        k1 ^= ((long) data[tail + 1] & 0xFF) << 8;
        // fall through
      case 1:
        k1 ^= (data[tail] & 0xFF);
        k1 *= c1;
        k1 = Long.rotateLeft(k1, 31);
        k1 *= c2;
        h1 ^= k1;
        break;
      default:
        // length - tail == 0: no tail bytes to mix.
        break;
    }

    h1 ^= length;
    h1 ^= h1 >>> 33;
    h1 *= 0xff51afd7ed558ccdL;
    h1 ^= h1 >>> 33;
    h1 *= 0xc4ceb9fe1a85ec53L;
    h1 ^= h1 >>> 33;

    return h1;
  }
}
