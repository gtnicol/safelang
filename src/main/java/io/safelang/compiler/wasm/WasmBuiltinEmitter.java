package io.safelang.compiler.wasm;

import static io.safelang.compiler.wasm.WasmOpcode.*;

import io.safelang.compiler.CompilerException;
import java.util.Map;
import java.util.function.ToIntFunction;

final class WasmBuiltinEmitter {

  private final WasmModule module;
  private final WasmRuntimeContext runtime;
  private final TypeRegistry types;
  private final Map<String, Integer> builtins;
  private final WasmBuiltinSupport support;
  private final ToIntFunction<String> interner;
  private final WasmBuiltinContext context;
  private final io.safelang.runtime.Capabilities capabilities;

  WasmBuiltinEmitter(
      final WasmModule module,
      final WasmRuntimeContext runtime,
      final TypeRegistry types,
      final Map<String, Integer> builtins,
      final WasmBuiltinSupport support,
      final ToIntFunction<String> interner,
      final io.safelang.runtime.Capabilities capabilities) {
    this.module = module;
    this.runtime = runtime;
    this.types = types;
    this.builtins = builtins;
    this.support = support;
    this.interner = interner;
    this.capabilities =
        capabilities != null ? capabilities : io.safelang.runtime.Capabilities.all();
    this.context = new WasmBuiltinContext(module, runtime, types, builtins, support, interner);
  }

  void compile(final String builtin, final int index, final int type, final int arity) {
    // Capability gate: refuse to emit a builtin whose host capability the build did not grant.
    // WASM tree-shakes (only reachable builtins reach here), so this gate is precise. Mirrors the
    // C (CCodeGenerator.gatedResolve) and JVM (JvmCodeGenerator.builtin) AOT gates.
    final var capability = io.safelang.runtime.BuiltinRegistry.capability(builtin);
    if (capability != null && !capabilities.granted(capability)) {
      throw new CompilerException(
          "builtin '"
              + builtin
              + "' requires capability "
              + capability
              + ", which this build did not grant; rebuild with --allow "
              + capability.name().toLowerCase());
    }
    final var function = new WasmFunction(index, type, arity);

    // Delegate to category emitters first; fall through to the inline switch
    // for the cases that aren't yet split out.
    if (WasmBytesBuiltinEmitter.tryEmit(function, builtin, context)
        || WasmFileBuiltinEmitter.tryEmit(function, builtin, context)) {
      module.addCode(index, function.encode(module));
      return;
    }

    switch (builtin) {
      case "println" -> {
        function.emitLocalGet(0);
        function.emitCall(runtime.printlnTagged);
        function.emitCall(runtime.tagVoid);
      }
      case "print" -> {
        function.emitLocalGet(0);
        function.emitCall(runtime.printTagged);
        function.emitCall(runtime.tagVoid);
      }
      case "str" -> {
        function.emitLocalGet(0);
        function.emitCall(support.stringify());
      }
      case "len" -> {
        function.emitLocalGet(0);
        function.emitI64Const(WasmRuntime.TAG_MASK);
        function.emit(I64_AND);
        function.emitI64Const(WasmRuntime.TAG_STRING);
        function.emit(I64_EQ);
        function.emitIf(TYPE_I64);
        function.emitLocalGet(0);
        function.emitCall(support.stringLength());
        function.emit(ELSE);
        function.emitLocalGet(0);
        function.emitI64Const(WasmRuntime.TAG_MASK);
        function.emit(I64_AND);
        function.emitI64Const(WasmRuntime.TAG_MAP);
        function.emit(I64_EQ);
        function.emitIf(TYPE_I64);
        function.emitLocalGet(0);
        function.emitCall(support.mapLength());
        function.emit(ELSE);
        function.emitLocalGet(0);
        function.emitCall(support.listLength());
        function.emit(END);
        function.emit(END);
      }
      case "range" -> emitRange(function);
      case "append" -> {
        function.emitLocalGet(0);
        function.emitLocalGet(1);
        function.emitCall(support.listAppend());
      }
      case "size" -> {
        // Polymorphic: list / string / map / set. Dispatch on the tag
        // the same way `len` does — without this, `size(map)` reads the
        // map's body-pointer slot as if it were a list-length field.
        function.emitLocalGet(0);
        function.emitI64Const(WasmRuntime.TAG_MASK);
        function.emit(I64_AND);
        function.emitI64Const(WasmRuntime.TAG_STRING);
        function.emit(I64_EQ);
        function.emitIf(TYPE_I64);
        function.emitLocalGet(0);
        function.emitCall(support.stringLength());
        function.emit(ELSE);
        function.emitLocalGet(0);
        function.emitI64Const(WasmRuntime.TAG_MASK);
        function.emit(I64_AND);
        function.emitI64Const(WasmRuntime.TAG_MAP);
        function.emit(I64_EQ);
        function.emitIf(TYPE_I64);
        function.emitLocalGet(0);
        function.emitCall(support.mapLength());
        function.emit(ELSE);
        function.emitLocalGet(0);
        function.emitCall(support.listLength());
        function.emit(END);
        function.emit(END);
      }
      case "contains" -> {
        function.emitLocalGet(0);
        function.emitI64Const(WasmRuntime.TAG_MASK);
        function.emit(I64_AND);
        function.emitI64Const(WasmRuntime.TAG_MAP);
        function.emit(I64_EQ);
        function.emitIf(TYPE_I64);
        function.emitLocalGet(0);
        function.emitLocalGet(1);
        function.emitCall(support.mapContains());
        function.emit(ELSE);
        function.emitLocalGet(0);
        function.emitCall(runtime.untagPointer);
        function.emitLocalGet(1);
        function.emitCall(builtins.getOrDefault("safe_list_contains", runtime.valuesEqual));
        WasmEmit.retagBool(function);
        function.emit(END);
      }
      case "keys" -> {
        function.emitLocalGet(0);
        function.emitCall(support.mapKeys());
      }
      case "values" -> {
        function.emitLocalGet(0);
        function.emitCall(support.mapValues());
      }
      case "remove" -> {
        function.emitLocalGet(0);
        function.emitLocalGet(1);
        function.emitCall(support.listRemoveAt());
      }
      case "sqrt" -> {
        function.emitLocalGet(0);
        function.emitCall(runtime.untagFloat);
        function.emit(F64_SQRT);
        function.emitCall(runtime.tagFloat);
      }
      case "abs" -> {
        function.emitLocalGet(0);
        function.emitI64Const(WasmRuntime.TAG_MASK);
        function.emit(I64_AND);
        function.emitI64Const(WasmRuntime.TAG_INT);
        function.emit(I64_EQ);
        function.emitIf(TYPE_I64);
        function.emitLocalGet(0);
        function.emitCall(runtime.untagInt);
        final var value = function.addLocal(TYPE_I64);
        function.emitLocalSet(value);
        function.emitLocalGet(value);
        function.emitI64Const(0);
        function.emit(I64_LT_S);
        function.emitIf(TYPE_I64);
        function.emitI64Const(0);
        function.emitLocalGet(value);
        function.emit(I64_SUB);
        function.emitCall(runtime.tagInt);
        function.emit(ELSE);
        function.emitLocalGet(value);
        function.emitCall(runtime.tagInt);
        function.emit(END);
        function.emit(ELSE);
        function.emitLocalGet(0);
        function.emitCall(runtime.untagFloat);
        function.emit(F64_ABS);
        function.emitCall(runtime.tagFloat);
        function.emit(END);
      }
      case "floor" -> {
        function.emitLocalGet(0);
        function.emitCall(runtime.untagFloat);
        function.emit(F64_FLOOR);
        function.emit(I64_TRUNC_F64_S);
        function.emitCall(runtime.tagInt);
      }
      case "ceil" -> {
        function.emitLocalGet(0);
        function.emitCall(runtime.untagFloat);
        function.emit(F64_CEIL);
        function.emit(I64_TRUNC_F64_S);
        function.emitCall(runtime.tagInt);
      }
      case "round" -> {
        function.emitLocalGet(0);
        function.emitCall(runtime.untagFloat);
        function.emit(F64_NEAREST);
        function.emit(I64_TRUNC_F64_S);
        function.emitCall(runtime.tagInt);
      }
      case "exit" -> {
        function.emitLocalGet(0);
        function.emitCall(runtime.untagInt);
        function.emit(I32_WRAP_I64);
        function.emitCall(runtime.wasiExit);
        function.emitCall(runtime.tagVoid);
      }
      case "integer", "int" -> {
        // Dispatch on the arg's tag: INT passes through, FLOAT truncates,
        // STRING parses. Matches the interpreter / bytecode VM / native C
        // behaviour — prior version only handled STRING and returned 0 for
        // every other tag (breaking decreases(std:integer(float)) etc).
        function.emitLocalGet(0);
        function.emitI64Const(WasmRuntime.TAG_MASK);
        function.emit(I64_AND);
        function.emitI64Const(WasmRuntime.TAG_INT);
        function.emit(I64_EQ);
        function.emitIf(TYPE_I64);
        function.emitLocalGet(0); // already int-tagged — pass through
        function.emit(ELSE);
        function.emitLocalGet(0);
        function.emitI64Const(WasmRuntime.TAG_MASK);
        function.emit(I64_AND);
        function.emitI64Const(WasmRuntime.TAG_FLOAT);
        function.emit(I64_EQ);
        function.emitIf(TYPE_I64);
        function.emitLocalGet(0);
        function.emitCall(runtime.untagFloat);
        function.emit(I64_TRUNC_F64_S);
        function.emitCall(runtime.tagInt);
        function.emit(ELSE);
        final var strToInt = builtins.get("safe_str_to_int");
        if (strToInt != null) {
          function.emitLocalGet(0);
          function.emitCall(runtime.untagPointer);
          function.emitCall(strToInt);
          function.emitCall(runtime.tagInt);
        } else {
          function.emitCall(runtime.tagVoid);
        }
        function.emit(END);
        function.emit(END);
      }
      case "decimal", "float" -> {
        // Dispatch on the arg's tag: FLOAT passes through, INT widens,
        // STRING parses. Symmetric fix to std:integer above.
        function.emitLocalGet(0);
        function.emitI64Const(WasmRuntime.TAG_MASK);
        function.emit(I64_AND);
        function.emitI64Const(WasmRuntime.TAG_FLOAT);
        function.emit(I64_EQ);
        function.emitIf(TYPE_I64);
        function.emitLocalGet(0);
        function.emit(ELSE);
        function.emitLocalGet(0);
        function.emitI64Const(WasmRuntime.TAG_MASK);
        function.emit(I64_AND);
        function.emitI64Const(WasmRuntime.TAG_INT);
        function.emit(I64_EQ);
        function.emitIf(TYPE_I64);
        function.emitLocalGet(0);
        function.emitCall(runtime.untagInt);
        function.emit(F64_CONVERT_I64_S);
        function.emitCall(runtime.tagFloat);
        function.emit(ELSE);
        final var strToFloat = builtins.get("safe_str_to_float");
        if (strToFloat != null) {
          function.emitLocalGet(0);
          function.emitCall(runtime.untagPointer);
          function.emitCall(strToFloat);
          function.emitCall(runtime.tagFloat);
        } else {
          function.emitCall(runtime.tagVoid);
        }
        function.emit(END);
        function.emit(END);
      }
      case "min" -> {
        function.emitLocalGet(0);
        function.emitI64Const(WasmRuntime.TAG_MASK);
        function.emit(I64_AND);
        function.emitI64Const(WasmRuntime.TAG_INT);
        function.emit(I64_EQ);
        function.emitIf(TYPE_I64);
        function.emitLocalGet(0);
        function.emitCall(runtime.untagInt);
        function.emitLocalGet(1);
        function.emitCall(runtime.untagInt);
        function.emit(I64_LT_S);
        function.emitIf(TYPE_I64);
        function.emitLocalGet(0);
        function.emit(ELSE);
        function.emitLocalGet(1);
        function.emit(END);
        function.emit(ELSE);
        function.emitLocalGet(0);
        function.emitCall(runtime.untagFloat);
        function.emitLocalGet(1);
        function.emitCall(runtime.untagFloat);
        function.emit(F64_LT);
        function.emitIf(TYPE_I64);
        function.emitLocalGet(0);
        function.emit(ELSE);
        function.emitLocalGet(1);
        function.emit(END);
        function.emit(END);
      }
      case "max" -> {
        function.emitLocalGet(0);
        function.emitI64Const(WasmRuntime.TAG_MASK);
        function.emit(I64_AND);
        function.emitI64Const(WasmRuntime.TAG_INT);
        function.emit(I64_EQ);
        function.emitIf(TYPE_I64);
        function.emitLocalGet(0);
        function.emitCall(runtime.untagInt);
        function.emitLocalGet(1);
        function.emitCall(runtime.untagInt);
        function.emit(I64_GT_S);
        function.emitIf(TYPE_I64);
        function.emitLocalGet(0);
        function.emit(ELSE);
        function.emitLocalGet(1);
        function.emit(END);
        function.emit(ELSE);
        function.emitLocalGet(0);
        function.emitCall(runtime.untagFloat);
        function.emitLocalGet(1);
        function.emitCall(runtime.untagFloat);
        function.emit(F64_GT);
        function.emitIf(TYPE_I64);
        function.emitLocalGet(0);
        function.emit(ELSE);
        function.emitLocalGet(1);
        function.emit(END);
        function.emit(END);
      }
      case "pow" -> {
        function.emitLocalGet(0);
        function.emitCall(runtime.untagFloat);
        function.emitLocalGet(1);
        function.emitCall(runtime.untagFloat);
        function.emitCall(builtins.get("safe_pow"));
        function.emitCall(runtime.tagFloat);
      }
      case "sin" -> {
        function.emitLocalGet(0);
        function.emitCall(runtime.untagFloat);
        function.emitCall(builtins.get("safe_sin"));
        function.emitCall(runtime.tagFloat);
      }
      case "cos" -> {
        function.emitLocalGet(0);
        function.emitCall(runtime.untagFloat);
        function.emitCall(builtins.get("safe_cos"));
        function.emitCall(runtime.tagFloat);
      }
      case "tan" -> {
        function.emitLocalGet(0);
        function.emitCall(runtime.untagFloat);
        function.emitCall(builtins.get("safe_tan"));
        function.emitCall(runtime.tagFloat);
      }
      case "asin" -> {
        function.emitLocalGet(0);
        function.emitCall(runtime.untagFloat);
        function.emitCall(builtins.get("safe_asin"));
        function.emitCall(runtime.tagFloat);
      }
      case "acos" -> {
        function.emitLocalGet(0);
        function.emitCall(runtime.untagFloat);
        function.emitCall(builtins.get("safe_acos"));
        function.emitCall(runtime.tagFloat);
      }
      case "atan" -> {
        function.emitLocalGet(0);
        function.emitCall(runtime.untagFloat);
        function.emitCall(builtins.get("safe_atan"));
        function.emitCall(runtime.tagFloat);
      }
      case "atan2" -> {
        function.emitLocalGet(0);
        function.emitCall(runtime.untagFloat);
        function.emitLocalGet(1);
        function.emitCall(runtime.untagFloat);
        function.emitCall(builtins.get("safe_atan2"));
        function.emitCall(runtime.tagFloat);
      }
      case "log" -> {
        function.emitLocalGet(0);
        function.emitCall(runtime.untagFloat);
        function.emitCall(builtins.get("safe_log"));
        function.emitCall(runtime.tagFloat);
      }
      case "exp" -> {
        function.emitLocalGet(0);
        function.emitCall(runtime.untagFloat);
        function.emitCall(builtins.get("safe_exp"));
        function.emitCall(runtime.tagFloat);
      }
      case "log10" -> {
        function.emitLocalGet(0);
        function.emitCall(runtime.untagFloat);
        function.emitCall(builtins.get("safe_log10"));
        function.emitCall(runtime.tagFloat);
      }
      case "time" -> {
        function.emitCall(requireBuiltin("safe_time"));
        function.emitCall(runtime.tagInt);
      }
      case "args" -> {
        function.emitCall(requireBuiltin("safe_args"));
        WasmEmit.retagPointer(function, WasmRuntime.TAG_LIST);
      }
      case "typeof" -> {
        function.emitLocalGet(0);
        function.emitCall(runtime.tag);
        final var tag = function.addLocal(TYPE_I32);
        function.emitLocalSet(tag);
        function.emitLocalGet(tag);
        function.emitI32Const(WasmRuntime.TAG_INT);
        function.emit(I32_EQ);
        function.emitIf(TYPE_I64);
        function.emitI32Const(interner.applyAsInt("int"));
        function.emitCall(runtime.tagString);
        function.emit(ELSE);
        function.emitLocalGet(tag);
        function.emitI32Const(WasmRuntime.TAG_FLOAT);
        function.emit(I32_EQ);
        function.emitIf(TYPE_I64);
        function.emitI32Const(interner.applyAsInt("float"));
        function.emitCall(runtime.tagString);
        function.emit(ELSE);
        function.emitLocalGet(tag);
        function.emitI32Const(WasmRuntime.TAG_BOOL);
        function.emit(I32_EQ);
        function.emitIf(TYPE_I64);
        function.emitI32Const(interner.applyAsInt("boolean"));
        function.emitCall(runtime.tagString);
        function.emit(ELSE);
        function.emitLocalGet(tag);
        function.emitI32Const(WasmRuntime.TAG_STRING);
        function.emit(I32_EQ);
        function.emitIf(TYPE_I64);
        function.emitI32Const(interner.applyAsInt("string"));
        function.emitCall(runtime.tagString);
        function.emit(ELSE);
        function.emitLocalGet(tag);
        function.emitI32Const(WasmRuntime.TAG_LIST);
        function.emit(I32_EQ);
        function.emitIf(TYPE_I64);
        function.emitI32Const(interner.applyAsInt("list"));
        function.emitCall(runtime.tagString);
        function.emit(ELSE);
        function.emitLocalGet(tag);
        function.emitI32Const(WasmRuntime.TAG_MAP);
        function.emit(I32_EQ);
        function.emitIf(TYPE_I64);
        function.emitI32Const(interner.applyAsInt("map"));
        function.emitCall(runtime.tagString);
        function.emit(ELSE);
        function.emitLocalGet(tag);
        function.emitI32Const(WasmRuntime.TAG_BYTES);
        function.emit(I32_EQ);
        function.emitIf(TYPE_I64);
        function.emitI32Const(interner.applyAsInt("bytes"));
        function.emitCall(runtime.tagString);
        function.emit(ELSE);
        function.emitI32Const(interner.applyAsInt("void"));
        function.emitCall(runtime.tagString);
        function.emit(END);
        function.emit(END);
        function.emit(END);
        function.emit(END);
        function.emit(END);
        function.emit(END);
        function.emit(END);
      }
      case "sort" -> {
        final var target = builtins.get("safe_list_sort");
        if (target != null) {
          function.emitLocalGet(0);
          function.emitCall(runtime.untagPointer);
          function.emitCall(target);
          WasmEmit.retagPointer(function, WasmRuntime.TAG_LIST);
        } else {
          function.emitLocalGet(0);
        }
      }
      case "reverse" -> {
        function.emitLocalGet(0);
        function.emitCall(support.listReverse());
      }
      case "slice" -> {
        function.emitLocalGet(0);
        function.emitLocalGet(1);
        function.emitLocalGet(2);
        function.emitCall(support.listSlice());
      }
      case "join" -> {
        final var result = function.addLocal(TYPE_I64);
        final var length = function.addLocal(TYPE_I64);
        final var indexLocal = function.addLocal(TYPE_I64);
        function.emitI32Const(interner.applyAsInt(""));
        function.emitCall(runtime.tagString);
        function.emitLocalSet(result);
        function.emitLocalGet(0);
        function.emitCall(support.listLength());
        function.emitCall(runtime.untagInt);
        function.emitLocalSet(length);
        function.emitI64Const(0);
        function.emitLocalSet(indexLocal);
        function.emitBlock(TYPE_VOID);
        function.emitLoop(TYPE_VOID);
        function.emitLocalGet(indexLocal);
        function.emitLocalGet(length);
        function.emit(I64_GE_S);
        function.emitBrIf(1);
        function.emitLocalGet(indexLocal);
        function.emitI64Const(0);
        function.emit(I64_GT_S);
        function.emitIf(TYPE_VOID);
        function.emitLocalGet(result);
        function.emitLocalGet(1);
        function.emitCall(support.stringConcat());
        function.emitLocalSet(result);
        function.emit(END);
        function.emitLocalGet(result);
        function.emitLocalGet(0);
        function.emitLocalGet(indexLocal);
        function.emitCall(runtime.tagInt);
        function.emitCall(support.listGet());
        function.emitCall(support.stringify());
        function.emitCall(support.stringConcat());
        function.emitLocalSet(result);
        function.emitLocalGet(indexLocal);
        function.emitI64Const(1);
        function.emit(I64_ADD);
        function.emitLocalSet(indexLocal);
        function.emitBr(0);
        function.emit(END);
        function.emit(END);
        function.emitLocalGet(result);
      }
      case "input" -> {
        function.emitLocalGet(0);
        function.emitCall(runtime.untagPointer);
        function.emitCall(requireBuiltin("safe_input"));
        function.emitCall(runtime.tagString);
      }
      case "seed" -> {
        final var target = builtins.get("safe_seed");
        if (target != null) {
          function.emitLocalGet(0);
          function.emitCall(runtime.untagInt);
          function.emitCall(target);
        }
        function.emitCall(runtime.tagVoid);
      }
      case "rand" -> {
        final var target = builtins.get("safe_rand");
        if (target != null) {
          function.emitCall(target);
          function.emitCall(runtime.tagFloat);
        } else {
          function.emitCall(runtime.tagVoid);
        }
      }
      case "randint" -> {
        final var target = builtins.get("safe_randint");
        if (target != null) {
          function.emitLocalGet(0);
          function.emitCall(runtime.untagInt);
          function.emitLocalGet(1);
          function.emitCall(runtime.untagInt);
          function.emitCall(target);
          function.emitCall(runtime.tagInt);
        } else {
          function.emitCall(runtime.tagVoid);
        }
      }
      case "chars" -> {
        final var target = builtins.get("safe_str_chars");
        if (target != null) {
          function.emitLocalGet(0);
          function.emitCall(runtime.untagPointer);
          function.emitCall(target);
          WasmEmit.retagPointer(function, WasmRuntime.TAG_LIST);
        } else {
          function.emitCall(support.listCreate());
          function.emitLocalGet(0);
          function.emitCall(support.listAppend());
        }
      }
      case "split" -> {
        final var target = builtins.get("safe_str_split");
        if (target != null) {
          function.emitLocalGet(0);
          function.emitCall(runtime.untagPointer);
          function.emitLocalGet(1);
          function.emitCall(runtime.untagPointer);
          function.emitCall(target);
          WasmEmit.retagPointer(function, WasmRuntime.TAG_LIST);
        } else {
          function.emitCall(support.listCreate());
          function.emitLocalGet(0);
          function.emitCall(support.listAppend());
        }
      }
      case "substring" -> {
        final var target = builtins.get("safe_str_substring");
        if (target != null) {
          function.emitLocalGet(0);
          function.emitCall(runtime.untagPointer);
          function.emitLocalGet(1);
          function.emitCall(runtime.untagInt);
          function.emit(I32_WRAP_I64);
          function.emitLocalGet(2);
          function.emitCall(runtime.untagInt);
          function.emit(I32_WRAP_I64);
          function.emitCall(target);
          function.emitCall(runtime.tagString);
        } else {
          function.emitLocalGet(0);
        }
      }
      case "indexOf" -> {
        final var target = builtins.get("safe_str_indexof");
        if (target != null) {
          function.emitLocalGet(0);
          function.emitCall(runtime.untagPointer);
          function.emitLocalGet(1);
          function.emitCall(runtime.untagPointer);
          function.emitCall(target);
          function.emit(I64_EXTEND_I32_S);
          function.emitCall(runtime.tagInt);
        } else {
          function.emitI64Const(-1);
          function.emitCall(runtime.tagInt);
        }
      }
      case "charAt" -> {
        final var target = builtins.get("safe_str_charat");
        if (target != null) {
          function.emitLocalGet(0);
          function.emitCall(runtime.untagPointer);
          function.emitLocalGet(1);
          function.emitCall(runtime.untagInt);
          function.emit(I32_WRAP_I64);
          function.emitCall(target);
          function.emitCall(runtime.tagString);
        } else {
          function.emitI32Const(interner.applyAsInt(""));
          function.emitCall(runtime.tagString);
        }
      }
      case "trim" -> {
        function.emitLocalGet(0);
        function.emitCall(runtime.untagPointer);
        final var target = builtins.get("safe_str_trim");
        if (target != null) {
          function.emitCall(target);
          function.emitCall(runtime.tagString);
        } else {
          function.emit(DROP);
          function.emitCall(runtime.tagVoid);
        }
      }
      case "upper" -> {
        function.emitLocalGet(0);
        function.emitCall(runtime.untagPointer);
        final var target = builtins.get("safe_str_upper");
        if (target != null) {
          function.emitCall(target);
          function.emitCall(runtime.tagString);
        } else {
          function.emit(DROP);
          function.emitCall(runtime.tagVoid);
        }
      }
      case "lower" -> {
        function.emitLocalGet(0);
        function.emitCall(runtime.untagPointer);
        final var target = builtins.get("safe_str_lower");
        if (target != null) {
          function.emitCall(target);
          function.emitCall(runtime.tagString);
        } else {
          function.emit(DROP);
          function.emitCall(runtime.tagVoid);
        }
      }
      case "replace" -> {
        function.emitLocalGet(0);
        function.emitCall(runtime.untagPointer);
        function.emitLocalGet(1);
        function.emitCall(runtime.untagPointer);
        function.emitLocalGet(2);
        function.emitCall(runtime.untagPointer);
        final var target = builtins.get("safe_str_replace");
        if (target != null) {
          function.emitCall(target);
          function.emitCall(runtime.tagString);
        } else {
          function.emit(DROP);
          function.emit(DROP);
          function.emit(DROP);
          function.emitCall(runtime.tagVoid);
        }
      }
      case "starts" -> {
        function.emitLocalGet(0);
        function.emitCall(runtime.untagPointer);
        function.emitLocalGet(1);
        function.emitCall(runtime.untagPointer);
        final var target = builtins.get("safe_str_starts");
        if (target != null) {
          function.emitCall(target);
          function.emitCall(runtime.tagBool);
        } else {
          function.emit(DROP);
          function.emit(DROP);
          function.emitCall(runtime.tagVoid);
        }
      }
      case "ends" -> {
        function.emitLocalGet(0);
        function.emitCall(runtime.untagPointer);
        function.emitLocalGet(1);
        function.emitCall(runtime.untagPointer);
        final var target = builtins.get("safe_str_ends");
        if (target != null) {
          function.emitCall(target);
          function.emitCall(runtime.tagBool);
        } else {
          function.emit(DROP);
          function.emit(DROP);
          function.emitCall(runtime.tagVoid);
        }
      }
      case "add" -> {
        function.emitLocalGet(0);
        function.emitCall(runtime.untagPointer);
        function.emitLocalGet(1);
        final var target = builtins.get("safe_set_add");
        if (target != null) {
          function.emitCall(target);
          WasmEmit.retagPointer(function, WasmRuntime.TAG_SET);
        } else {
          function.emit(DROP);
          function.emit(DROP);
          function.emitCall(runtime.tagVoid);
        }
      }
      case "union" -> {
        function.emitLocalGet(0);
        function.emitCall(runtime.untagPointer);
        function.emitLocalGet(1);
        function.emitCall(runtime.untagPointer);
        final var target = builtins.get("safe_set_union");
        if (target != null) {
          function.emitCall(target);
          WasmEmit.retagPointer(function, WasmRuntime.TAG_SET);
        } else {
          function.emit(DROP);
          function.emit(DROP);
          function.emitCall(runtime.tagVoid);
        }
      }
      case "intersect" -> {
        function.emitLocalGet(0);
        function.emitCall(runtime.untagPointer);
        function.emitLocalGet(1);
        function.emitCall(runtime.untagPointer);
        final var target = builtins.get("safe_set_intersect");
        if (target != null) {
          function.emitCall(target);
          WasmEmit.retagPointer(function, WasmRuntime.TAG_SET);
        } else {
          function.emit(DROP);
          function.emit(DROP);
          function.emitCall(runtime.tagVoid);
        }
      }
      case "difference" -> {
        function.emitLocalGet(0);
        function.emitCall(runtime.untagPointer);
        function.emitLocalGet(1);
        function.emitCall(runtime.untagPointer);
        final var target = builtins.get("safe_set_difference");
        if (target != null) {
          function.emitCall(target);
          WasmEmit.retagPointer(function, WasmRuntime.TAG_SET);
        } else {
          function.emit(DROP);
          function.emit(DROP);
          function.emitCall(runtime.tagVoid);
        }
      }
      case "unique" -> {
        function.emitLocalGet(0);
        function.emitCall(runtime.untagPointer);
        final var target = builtins.get("safe_list_unique");
        if (target != null) {
          function.emitCall(target);
          WasmEmit.retagPointer(function, WasmRuntime.TAG_LIST);
        } else {
          function.emit(DROP);
          function.emitCall(runtime.tagVoid);
        }
      }
      case "matches" -> {
        function.emitLocalGet(0);
        function.emitCall(runtime.untagPointer);
        function.emitLocalGet(1);
        function.emitCall(runtime.untagPointer);
        final var target = builtins.get("safe_regex_matches");
        if (target != null) {
          function.emitCall(target);
          function.emitCall(runtime.tagBool);
        } else {
          function.emit(DROP);
          function.emit(DROP);
          function.emitCall(runtime.tagVoid);
        }
      }
      case "findall" -> {
        function.emitLocalGet(0);
        function.emitCall(runtime.untagPointer);
        function.emitLocalGet(1);
        function.emitCall(runtime.untagPointer);
        final var target = builtins.get("safe_regex_findall");
        if (target != null) {
          function.emitCall(target);
          WasmEmit.retagPointer(function, WasmRuntime.TAG_LIST);
        } else {
          function.emit(DROP);
          function.emit(DROP);
          function.emitCall(runtime.tagVoid);
        }
      }
      case "replaceall" -> {
        function.emitLocalGet(0);
        function.emitCall(runtime.untagPointer);
        function.emitLocalGet(1);
        function.emitCall(runtime.untagPointer);
        function.emitLocalGet(2);
        function.emitCall(runtime.untagPointer);
        final var target = builtins.get("safe_regex_replaceall");
        if (target != null) {
          function.emitCall(target);
          function.emitCall(runtime.tagString);
        } else {
          function.emit(DROP);
          function.emit(DROP);
          function.emit(DROP);
          function.emitCall(runtime.tagVoid);
        }
      }
      case "getenv" -> {
        function.emitLocalGet(0);
        function.emitCall(runtime.untagPointer);
        final var target = builtins.get("safe_getenv");
        if (target != null) {
          function.emitCall(target);
          function.emitCall(runtime.tagString);
        } else {
          function.emit(DROP);
          function.emitI32Const(0);
          function.emitCall(runtime.alloc);
          function.emitCall(runtime.tagString);
        }
      }
      case "http_get", "http_post", "http_request", "http_serve", "system_exec" ->
          throw new CompilerException(
              "Network/process builtin '"
                  + builtin
                  + "' is not available in the WASM backend; use the interpreter, bytecode VM, JVM,"
                  + " or native C backend");
      case "sopen", "sclose", "sline", "sread", "swrite", "sflush" ->
          throw new CompilerException(
              "Streaming file I/O builtin '"
                  + builtin
                  + "' is not supported in the WASM backend; use the interpreter, bytecode VM, or"
                  + " JVM");
      default -> throw new CompilerException("Unsupported builtin stub: " + builtin);
    }

    module.addCode(index, function.encode(module));
  }

  // range(end) | range(start, end) | range(start, end, step). Locals 0/1/2 carry the (VOID-padded)
  // arguments. End-exclusive, Python-style; step may be negative for a descending range.
  private void emitRange(final WasmFunction function) {
    final var start = function.addLocal(TYPE_I64);
    final var end = function.addLocal(TYPE_I64);
    final var step = function.addLocal(TYPE_I64);
    final var index = function.addLocal(TYPE_I64);
    final var list = function.addLocal(TYPE_I64);

    // step: local 2 VOID -> default 1, else untag.
    function.emitLocalGet(2);
    function.emitCall(runtime.tag);
    function.emitI32Const(WasmRuntime.TAG_VOID);
    function.emit(I32_EQ);
    function.emitIf(TYPE_VOID);
    function.emitI64Const(1);
    function.emitLocalSet(step);
    function.emit(ELSE);
    function.emitLocalGet(2);
    function.emitCall(runtime.untagInt);
    function.emitLocalSet(step);
    function.emit(END);

    // start/end: local 1 VOID -> 1-arg (start=0, end=arg0); else start=arg0, end=arg1.
    function.emitLocalGet(1);
    function.emitCall(runtime.tag);
    function.emitI32Const(WasmRuntime.TAG_VOID);
    function.emit(I32_EQ);
    function.emitIf(TYPE_VOID);
    function.emitI64Const(0);
    function.emitLocalSet(start);
    function.emitLocalGet(0);
    function.emitCall(runtime.untagInt);
    function.emitLocalSet(end);
    function.emit(ELSE);
    function.emitLocalGet(0);
    function.emitCall(runtime.untagInt);
    function.emitLocalSet(start);
    function.emitLocalGet(1);
    function.emitCall(runtime.untagInt);
    function.emitLocalSet(end);
    function.emit(END);

    function.emitCall(support.listCreate());
    function.emitLocalSet(list);
    function.emitLocalGet(start);
    function.emitLocalSet(index);

    // step == 0 would spin forever for a descending start; guard it (the interpreter, bytecode and
    // JVM backends trap on a zero step, so an empty list is the safe WASM-side behavior).
    function.emitLocalGet(step);
    function.emitI64Const(0);
    function.emit(I64_NE);
    function.emitIf(TYPE_VOID);
    function.emitBlock(TYPE_VOID);
    function.emitLoop(TYPE_VOID);
    // done = step > 0 ? index >= end : index <= end
    function.emitLocalGet(step);
    function.emitI64Const(0);
    function.emit(I64_GT_S);
    function.emitIf(TYPE_I32);
    function.emitLocalGet(index);
    function.emitLocalGet(end);
    function.emit(I64_GE_S);
    function.emit(ELSE);
    function.emitLocalGet(index);
    function.emitLocalGet(end);
    function.emit(I64_LE_S);
    function.emit(END);
    function.emitBrIf(1);
    function.emitLocalGet(list);
    function.emitLocalGet(index);
    function.emitCall(runtime.tagInt);
    function.emitCall(support.listAppend());
    function.emitLocalSet(list);
    function.emitLocalGet(index);
    function.emitLocalGet(step);
    function.emit(I64_ADD);
    function.emitLocalSet(index);
    function.emitBr(0);
    function.emit(END);
    function.emit(END);
    function.emit(END);

    function.emitLocalGet(list);
  }

  private void emitWrapInEnum(
      final WasmFunction function,
      final String moduleName,
      final String enumName,
      final int variant) {
    final var typeId = types.type(moduleName, enumName);
    final var wrapped = function.addLocal(TYPE_I64);
    function.emitLocalSet(wrapped);
    WasmEmit.emitVariant(function, runtime, typeId, variant, new int[] {wrapped});
  }

  private int requireBuiltin(final String name) {
    final var target = builtins.get(name);
    if (target == null) {
      throw new CompilerException("Missing C builtin import: " + name);
    }
    return target;
  }
}
