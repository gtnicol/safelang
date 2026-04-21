package io.safelang.compiler.wasm;

import static io.safelang.compiler.wasm.WasmOpcode.*;

/**
 * Emits the {@code file} stdlib builtins — both the handle-based ops ({@code fileopen}, {@code
 * fileread}, …) and the convenience aliases ({@code read}, {@code write}, {@code lines}, …) and
 * directory ops ({@code mkdir}, {@code rmdir}, {@code listdir}, {@code isdir}).
 *
 * <p>Several of these wrap their result in a SAFE enum variant from {@code stdlib/file.safe}
 * ({@code ReadResult.Ok/Err}, {@code WriteResult}, {@code LinesResult}); that wrapping uses {@link
 * #wrapEnum} which knows the file module's enum layout.
 */
final class WasmFileBuiltinEmitter {

  private WasmFileBuiltinEmitter() {}

  static boolean tryEmit(
      final WasmFunction function, final String builtin, final WasmBuiltinContext context) {
    final var runtime = context.runtime();
    final var builtins = context.builtins();
    final var types = context.types();
    final var interner = context.interner();

    switch (builtin) {
      case "fileopen" -> {
        function.emitLocalGet(0);
        function.emitCall(runtime.untagPointer);
        function.emitLocalGet(1);
        function.emitCall(runtime.untagPointer);
        function.emitCall(builtins.get("safe_fileopen"));
        final var handle = function.addLocal(TYPE_I32);
        function.emitLocalSet(handle);
        function.emitLocalGet(handle);
        function.emitI32Const(-1);
        function.emit(I32_NE);
        function.emitIf(TYPE_I64);
        function.emitLocalGet(handle);
        function.emit(I64_EXTEND_I32_S);
        function.emitCall(runtime.tagInt);
        function.emit(ELSE);
        function.emitI64Const(-1);
        function.emitCall(runtime.tagInt);
        function.emit(END);
      }
      case "fileclose" -> {
        function.emitLocalGet(0);
        function.emitCall(runtime.untagInt);
        function.emit(I32_WRAP_I64);
        function.emitCall(builtins.get("safe_fileclose"));
        function.emitCall(runtime.tagVoid);
      }
      case "fileread" -> {
        function.emitLocalGet(0);
        function.emitCall(runtime.untagInt);
        function.emit(I32_WRAP_I64);
        function.emitCall(builtins.get("safe_fileread"));
        final var pointer = function.addLocal(TYPE_I32);
        function.emitLocalSet(pointer);
        function.emitLocalGet(pointer);
        function.emitIf(TYPE_I64);
        function.emitLocalGet(pointer);
        function.emitCall(runtime.tagString);
        wrapEnum(function, types, runtime, "ReadResult", 0);
        function.emit(ELSE);
        function.emitI32Const(interner.applyAsInt("read failed"));
        function.emitCall(runtime.tagString);
        wrapEnum(function, types, runtime, "ReadResult", 1);
        function.emit(END);
      }
      case "filewrite" -> {
        function.emitLocalGet(0);
        function.emitCall(runtime.untagInt);
        function.emit(I32_WRAP_I64);
        function.emitLocalGet(1);
        function.emitCall(runtime.untagPointer);
        function.emitCall(builtins.get("safe_filewrite"));
        final var result = function.addLocal(TYPE_I32);
        function.emitLocalSet(result);
        function.emitLocalGet(result);
        function.emitI32Const(0);
        function.emit(I32_GE_S);
        function.emitIf(TYPE_I64);
        emitWriteOk(function, types, runtime);
        function.emit(ELSE);
        function.emitI32Const(interner.applyAsInt("write failed"));
        function.emitCall(runtime.tagString);
        wrapEnum(function, types, runtime, "WriteResult", 1);
        function.emit(END);
      }
      case "filereadlines" -> {
        function.emitLocalGet(0);
        function.emitCall(runtime.untagInt);
        function.emit(I32_WRAP_I64);
        function.emitCall(builtins.get("safe_filereadlines"));
        final var pointer = function.addLocal(TYPE_I32);
        function.emitLocalSet(pointer);
        function.emitLocalGet(pointer);
        function.emitIf(TYPE_I64);
        function.emitLocalGet(pointer);
        WasmEmit.retagPointer(function, WasmRuntime.TAG_LIST);
        wrapEnum(function, types, runtime, "LinesResult", 0);
        function.emit(ELSE);
        function.emitI32Const(interner.applyAsInt("readlines failed"));
        function.emitCall(runtime.tagString);
        wrapEnum(function, types, runtime, "LinesResult", 1);
        function.emit(END);
      }
      case "filevalid" -> {
        function.emitLocalGet(0);
        function.emitCall(runtime.untagInt);
        function.emit(I32_WRAP_I64);
        function.emitCall(builtins.get("safe_filevalid"));
        WasmEmit.retagPointer(function, WasmRuntime.TAG_BOOL);
      }
      case "read" -> {
        function.emitLocalGet(0);
        function.emitCall(runtime.untagPointer);
        function.emitCall(builtins.get("safe_fileload"));
        function.emitCall(runtime.tagString);
      }
      case "write" -> {
        function.emitLocalGet(0);
        function.emitCall(runtime.untagPointer);
        function.emitLocalGet(1);
        function.emitCall(runtime.untagPointer);
        function.emitCall(builtins.get("safe_filesave"));
        function.emitCall(runtime.tagVoid);
      }
      case "appendfile" -> {
        function.emitLocalGet(0);
        function.emitCall(runtime.untagPointer);
        function.emitLocalGet(1);
        function.emitCall(runtime.untagPointer);
        function.emitCall(builtins.get("safe_fileappend"));
        function.emitCall(runtime.tagVoid);
      }
      case "exists" -> {
        function.emitLocalGet(0);
        function.emitCall(runtime.untagPointer);
        function.emitCall(builtins.get("safe_fileexists"));
        WasmEmit.retagPointer(function, WasmRuntime.TAG_BOOL);
      }
      case "delete" -> {
        function.emitLocalGet(0);
        function.emitCall(runtime.untagPointer);
        function.emitCall(builtins.get("safe_filedelete"));
        WasmEmit.retagPointer(function, WasmRuntime.TAG_BOOL);
      }
      case "lines" -> {
        function.emitLocalGet(0);
        function.emitCall(runtime.untagPointer);
        function.emitCall(builtins.get("safe_filereadlines"));
        WasmEmit.retagPointer(function, WasmRuntime.TAG_LIST);
      }
      case "fileload" -> {
        function.emitLocalGet(0);
        function.emitCall(runtime.untagPointer);
        function.emitCall(builtins.get("safe_fileload"));
        final var result = function.addLocal(TYPE_I32);
        function.emitLocalSet(result);
        function.emitLocalGet(result);
        function.emitIf(TYPE_I64);
        function.emitLocalGet(result);
        function.emitCall(runtime.tagString);
        wrapEnum(function, types, runtime, "ReadResult", 0);
        function.emit(ELSE);
        function.emitI32Const(interner.applyAsInt("read failed"));
        function.emitCall(runtime.tagString);
        wrapEnum(function, types, runtime, "ReadResult", 1);
        function.emit(END);
      }
      case "filesave" -> {
        function.emitLocalGet(0);
        function.emitCall(runtime.untagPointer);
        function.emitLocalGet(1);
        function.emitCall(runtime.untagPointer);
        function.emitCall(builtins.get("safe_filesave"));
        emitWriteOk(function, types, runtime);
      }
      case "mkdir" -> {
        function.emitLocalGet(0);
        function.emitCall(runtime.untagPointer);
        function.emitCall(builtins.get("safe_mkdir"));
        WasmEmit.retagPointer(function, WasmRuntime.TAG_BOOL);
      }
      case "rmdir" -> {
        function.emitLocalGet(0);
        function.emitCall(runtime.untagPointer);
        function.emitCall(builtins.get("safe_rmdir"));
        WasmEmit.retagPointer(function, WasmRuntime.TAG_BOOL);
      }
      case "listdir" -> {
        function.emitLocalGet(0);
        function.emitCall(runtime.untagPointer);
        function.emitCall(builtins.get("safe_listdir"));
        WasmEmit.retagPointer(function, WasmRuntime.TAG_LIST);
      }
      case "isdir" -> {
        function.emitLocalGet(0);
        function.emitCall(runtime.untagPointer);
        function.emitCall(builtins.get("safe_isdir"));
        WasmEmit.retagPointer(function, WasmRuntime.TAG_BOOL);
      }
      default -> {
        return false;
      }
    }
    return true;
  }

  /** Allocate a zero-field {@code WriteResult.Ok} variant on the heap. */
  private static void emitWriteOk(
      final WasmFunction function, final TypeRegistry types, final WasmRuntimeContext runtime) {
    final var typeId = types.type("file", "WriteResult");
    WasmEmit.emitVariant(function, runtime, typeId, 0, new int[] {});
  }

  /** Box the value on top of the stack into a one-field enum variant. */
  private static void wrapEnum(
      final WasmFunction function,
      final TypeRegistry types,
      final WasmRuntimeContext runtime,
      final String enumName,
      final int variant) {
    final var typeId = types.type("file", enumName);
    final var wrapped = function.addLocal(TYPE_I64);
    function.emitLocalSet(wrapped);
    WasmEmit.emitVariant(function, runtime, typeId, variant, new int[] {wrapped});
  }
}
