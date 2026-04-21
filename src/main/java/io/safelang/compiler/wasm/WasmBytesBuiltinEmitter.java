package io.safelang.compiler.wasm;

import static io.safelang.compiler.wasm.WasmOpcode.*;

/**
 * Emits the {@code bytes} runtime builtins ({@code balloc}, {@code bget}, {@code bencode}, …) and
 * the simple hash functions ({@code fnv}, {@code crc}, {@code murmur}, {@code hashtext}). All of
 * these are thin "untag → call C builtin → re-tag" wrappers, so grouping them keeps {@link
 * WasmBuiltinEmitter} focused on the more interesting cases.
 */
final class WasmBytesBuiltinEmitter {

  private WasmBytesBuiltinEmitter() {}

  /**
   * Emit the body for {@code builtin} into {@code function}, or return {@code false} if this
   * category does not handle that name.
   */
  static boolean tryEmit(
      final WasmFunction function, final String builtin, final WasmBuiltinContext context) {
    final var runtime = context.runtime();
    final var builtins = context.builtins();
    final var support = context.support();

    switch (builtin) {
      case "fnv" -> {
        function.emitLocalGet(0);
        function.emitCall(runtime.untagPointer);
        function.emitCall(builtins.getOrDefault("safe_fnv", runtime.tagVoid));
        function.emitCall(runtime.tagInt);
      }
      case "crc" -> {
        function.emitLocalGet(0);
        function.emitCall(runtime.untagPointer);
        function.emitCall(builtins.getOrDefault("safe_crc32", runtime.tagVoid));
        function.emitCall(runtime.tagInt);
      }
      case "murmur" -> {
        function.emitLocalGet(0);
        function.emitCall(runtime.untagPointer);
        function.emitCall(builtins.getOrDefault("safe_murmur", runtime.tagVoid));
        function.emitCall(runtime.tagInt);
      }
      case "hashtext" -> {
        function.emitLocalGet(0);
        function.emitCall(runtime.untagPointer);
        function.emitCall(builtins.getOrDefault("safe_fnv", runtime.tagVoid));
        function.emitCall(runtime.tagInt);
      }
      case "balloc" -> {
        function.emitLocalGet(0);
        function.emitCall(runtime.untagInt);
        function.emit(I32_WRAP_I64);
        function.emitCall(builtins.get("safe_balloc"));
        WasmEmit.retagPointer(function, WasmRuntime.TAG_BYTES);
      }
      case "blength" -> {
        function.emitLocalGet(0);
        function.emitCall(runtime.untagPointer);
        function.emitCall(builtins.get("safe_blen"));
        function.emit(I64_EXTEND_I32_S);
        function.emitCall(runtime.tagInt);
      }
      case "bget" -> {
        function.emitLocalGet(0);
        function.emitCall(runtime.untagPointer);
        function.emitLocalGet(1);
        function.emitCall(runtime.untagInt);
        function.emit(I32_WRAP_I64);
        function.emitCall(builtins.get("safe_bget"));
        function.emit(I64_EXTEND_I32_U);
        function.emitCall(runtime.tagInt);
      }
      case "bset" -> {
        function.emitLocalGet(0);
        function.emitCall(runtime.untagPointer);
        function.emitLocalGet(1);
        function.emitCall(runtime.untagInt);
        function.emit(I32_WRAP_I64);
        function.emitLocalGet(2);
        function.emitCall(runtime.untagInt);
        function.emit(I32_WRAP_I64);
        function.emitCall(builtins.get("safe_bset"));
        WasmEmit.retagPointer(function, WasmRuntime.TAG_BYTES);
      }
      case "bencode" -> {
        function.emitLocalGet(0);
        function.emitCall(runtime.untagPointer);
        function.emitCall(builtins.get("safe_bencode"));
        WasmEmit.retagPointer(function, WasmRuntime.TAG_BYTES);
      }
      case "bdecode" -> {
        function.emitLocalGet(0);
        function.emitCall(runtime.untagPointer);
        function.emitCall(builtins.get("safe_bdecode"));
        function.emitCall(runtime.tagString);
      }
      case "bhex" -> {
        function.emitLocalGet(0);
        function.emitCall(runtime.untagPointer);
        function.emitCall(builtins.get("safe_bhex"));
        function.emitCall(runtime.tagString);
      }
      case "btostr" -> {
        function.emitLocalGet(0);
        function.emitCall(support.stringify());
      }
      case "bslice" -> {
        function.emitLocalGet(0);
        function.emitCall(runtime.untagPointer);
        function.emitLocalGet(1);
        function.emitCall(runtime.untagInt);
        function.emit(I32_WRAP_I64);
        function.emitLocalGet(2);
        function.emitCall(runtime.untagInt);
        function.emit(I32_WRAP_I64);
        function.emitCall(builtins.get("safe_bslice"));
        WasmEmit.retagPointer(function, WasmRuntime.TAG_BYTES);
      }
      case "bconcat" -> {
        function.emitLocalGet(0);
        function.emitCall(runtime.untagPointer);
        function.emitLocalGet(1);
        function.emitCall(runtime.untagPointer);
        function.emitCall(builtins.get("safe_bconcat"));
        WasmEmit.retagPointer(function, WasmRuntime.TAG_BYTES);
      }
      case "bpatch" -> {
        function.emitLocalGet(0);
        function.emitCall(runtime.untagPointer);
        function.emitLocalGet(1);
        function.emitCall(runtime.untagInt);
        function.emit(I32_WRAP_I64);
        function.emitLocalGet(2);
        function.emitCall(runtime.untagPointer);
        function.emitCall(builtins.get("safe_bpatch"));
        WasmEmit.retagPointer(function, WasmRuntime.TAG_BYTES);
      }
      case "bcompare" -> {
        function.emitLocalGet(0);
        function.emitCall(runtime.untagPointer);
        function.emitLocalGet(1);
        function.emitCall(runtime.untagPointer);
        function.emitCall(builtins.get("safe_bcompare"));
        function.emit(I64_EXTEND_I32_S);
        function.emitCall(runtime.tagInt);
      }
      case "bkind" -> {
        function.emitLocalGet(0);
        function.emitCall(support.stringify());
      }
      case "bunpack" -> {
        function.emitLocalGet(0);
        function.emitCall(runtime.untagPointer);
        function.emitLocalGet(1);
        function.emitCall(runtime.untagInt);
        function.emit(I32_WRAP_I64);
        function.emitLocalGet(2);
        function.emitCall(runtime.untagInt);
        function.emit(I32_WRAP_I64);
        function.emitCall(builtins.get("safe_bunpack"));
        function.emitCall(runtime.tagInt);
      }
      case "bpack" -> {
        function.emitLocalGet(0);
        function.emitCall(runtime.untagInt);
        function.emitLocalGet(1);
        function.emitCall(runtime.untagInt);
        function.emit(I32_WRAP_I64);
        function.emitCall(builtins.get("safe_bpack"));
        WasmEmit.retagPointer(function, WasmRuntime.TAG_BYTES);
      }
      case "bopen" -> {
        function.emitLocalGet(0);
        function.emitCall(runtime.untagPointer);
        function.emitLocalGet(1);
        function.emitCall(runtime.untagPointer);
        function.emitCall(builtins.get("safe_bopen"));
        function.emit(I64_EXTEND_I32_S);
        function.emitCall(runtime.tagInt);
      }
      case "bclose" -> {
        function.emitLocalGet(0);
        function.emitCall(runtime.untagInt);
        function.emit(I32_WRAP_I64);
        function.emitCall(builtins.get("safe_bclose"));
        function.emitCall(runtime.tagVoid);
      }
      case "bread" -> {
        function.emitLocalGet(0);
        function.emitCall(runtime.untagInt);
        function.emit(I32_WRAP_I64);
        function.emitLocalGet(1);
        function.emitCall(runtime.untagInt);
        function.emit(I32_WRAP_I64);
        function.emitCall(builtins.get("safe_bread"));
        WasmEmit.retagPointer(function, WasmRuntime.TAG_BYTES);
      }
      case "bwrite" -> {
        function.emitLocalGet(0);
        function.emitCall(runtime.untagInt);
        function.emit(I32_WRAP_I64);
        function.emitLocalGet(1);
        function.emitCall(runtime.untagPointer);
        function.emitCall(builtins.get("safe_bwrite"));
        function.emitLocalGet(1);
        function.emitCall(runtime.untagPointer);
        function.emitCall(builtins.get("safe_blen"));
        function.emit(I64_EXTEND_I32_S);
        function.emitCall(runtime.tagInt);
      }
      case "bseek" -> {
        function.emitLocalGet(0);
        function.emitCall(runtime.untagInt);
        function.emit(I32_WRAP_I64);
        function.emitLocalGet(1);
        function.emitCall(runtime.untagInt);
        function.emitCall(builtins.get("safe_bseek"));
        function.emitCall(runtime.tagVoid);
      }
      case "bsize" -> {
        function.emitLocalGet(0);
        function.emitCall(runtime.untagPointer);
        function.emitCall(context.requireBuiltin("safe_bsize"));
        function.emitCall(runtime.tagInt);
      }
      case "bflush" -> {
        function.emitLocalGet(0);
        function.emitCall(runtime.untagInt);
        function.emit(I32_WRAP_I64);
        function.emitCall(builtins.get("safe_bflush"));
        function.emitCall(runtime.tagVoid);
      }
      default -> {
        return false;
      }
    }
    return true;
  }
}
