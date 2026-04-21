package io.safelang.compiler.wasm;

import io.safelang.compiler.CompilerException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Type-checks a WASM function body before it is encoded.
 *
 * <p>Walks a {@link WasmInstruction} stream maintaining an abstract stack and a control frame
 * stack, exactly as the WASM spec describes for module validation. This catches mistakes in the
 * compiler (mismatched types, stack underflow, missing else, etc.) at the point they were emitted
 * rather than after wasmtime rejects the binary, which makes the error message actionable.
 *
 * <p>Extracted from {@link WasmFunction} so the function class can stay focused on encoding while
 * the validator owns its own ~350 lines of spec-driven dispatch.
 */
final class WasmStackValidator {

  static final int TYPE_UNKNOWN = -1;
  private final WasmModule module;
  private final int functionIndex;
  private final int[] functionResults;
  private final int[] locals;
  private final List<Integer> stack = new ArrayList<>();
  private final Deque<ControlFrame> frames = new ArrayDeque<>();

  /**
   * @param module the enclosing module (used to resolve type indices)
   * @param functionIndex the absolute function index, included in error messages
   * @param functionResults the function's declared result types
   * @param locals locals layout (params followed by declared locals)
   */
  WasmStackValidator(
      final WasmModule module,
      final int functionIndex,
      final int[] functionResults,
      final int[] locals) {
    this.module = module;
    this.functionIndex = functionIndex;
    this.functionResults = functionResults;
    this.locals = locals;
    frames.push(new ControlFrame(Kind.ROOT, 0, new int[] {}, functionResults));
  }

  /** Walk every instruction and assert that the function body is well-typed. */
  void validate(final List<WasmInstruction> instructions) {
    for (final var instruction : instructions) {
      apply(instruction);
    }
    finish();
  }

  private void apply(final WasmInstruction instruction) {
    switch (instruction) {
      case WasmInstruction.Simple op -> applySimple(op.opcode());
      case WasmInstruction.IntConst ignored -> push(WasmOpcode.TYPE_I32);
      case WasmInstruction.LongConst ignored -> push(WasmOpcode.TYPE_I64);
      case WasmInstruction.FloatConst ignored -> push(WasmOpcode.TYPE_F64);
      case WasmInstruction.Indexed op -> applyIndexed(op.opcode(), op.index());
      case WasmInstruction.Block op -> applyBlock(op.opcode(), op.type());
      case WasmInstruction.Memory op -> applyMemory(op.opcode());
      case WasmInstruction.CallIndirect op -> applyCallIndirect(op.type());
    }
  }

  private void finish() {
    if (frames.size() != 1) {
      throw error("unclosed control frame(s) at end of function");
    }
    final var root = frames.peek();
    popTypes(root.endTypes());
    requireHeight(root.height());
  }

  private void applySimple(final int opcode) {
    switch (opcode) {
      case WasmOpcode.NOP -> {}
      case WasmOpcode.UNREACHABLE -> setUnreachable();
      case WasmOpcode.ELSE -> handleElse();
      case WasmOpcode.END -> handleEnd();
      case WasmOpcode.RETURN -> {
        popTypes(functionResults);
        setUnreachable();
      }
      case WasmOpcode.DROP -> popAny();
      case WasmOpcode.SELECT -> {
        pop(WasmOpcode.TYPE_I32);
        final var right = popAny();
        final var left = popAny();
        push(mergeTypes(left, right));
      }
      case WasmOpcode.I32_EQZ -> unary(WasmOpcode.TYPE_I32, WasmOpcode.TYPE_I32);
      case WasmOpcode.I32_EQ,
              WasmOpcode.I32_NE,
              WasmOpcode.I32_LT_S,
              WasmOpcode.I32_LT_U,
              WasmOpcode.I32_GT_S,
              WasmOpcode.I32_GT_U,
              WasmOpcode.I32_LE_S,
              WasmOpcode.I32_LE_U,
              WasmOpcode.I32_GE_S,
              WasmOpcode.I32_GE_U ->
          binary(WasmOpcode.TYPE_I32, WasmOpcode.TYPE_I32);
      case WasmOpcode.I64_EQZ -> unary(WasmOpcode.TYPE_I64, WasmOpcode.TYPE_I32);
      case WasmOpcode.I64_EQ,
              WasmOpcode.I64_NE,
              WasmOpcode.I64_LT_S,
              WasmOpcode.I64_LT_U,
              WasmOpcode.I64_GT_S,
              WasmOpcode.I64_GT_U,
              WasmOpcode.I64_LE_S,
              WasmOpcode.I64_LE_U,
              WasmOpcode.I64_GE_S,
              WasmOpcode.I64_GE_U ->
          binary(WasmOpcode.TYPE_I64, WasmOpcode.TYPE_I32);
      case WasmOpcode.F64_EQ,
              WasmOpcode.F64_NE,
              WasmOpcode.F64_LT,
              WasmOpcode.F64_GT,
              WasmOpcode.F64_LE,
              WasmOpcode.F64_GE ->
          binary(WasmOpcode.TYPE_F64, WasmOpcode.TYPE_I32);
      case WasmOpcode.I32_ADD,
              WasmOpcode.I32_SUB,
              WasmOpcode.I32_MUL,
              WasmOpcode.I32_DIV_S,
              WasmOpcode.I32_DIV_U,
              WasmOpcode.I32_REM_S,
              WasmOpcode.I32_REM_U,
              WasmOpcode.I32_AND,
              WasmOpcode.I32_OR,
              WasmOpcode.I32_XOR,
              WasmOpcode.I32_SHL,
              WasmOpcode.I32_SHR_S,
              WasmOpcode.I32_SHR_U ->
          binary(WasmOpcode.TYPE_I32, WasmOpcode.TYPE_I32);
      case WasmOpcode.I64_CLZ, WasmOpcode.I64_CTZ ->
          unary(WasmOpcode.TYPE_I64, WasmOpcode.TYPE_I64);
      case WasmOpcode.I64_ADD,
              WasmOpcode.I64_SUB,
              WasmOpcode.I64_MUL,
              WasmOpcode.I64_DIV_S,
              WasmOpcode.I64_DIV_U,
              WasmOpcode.I64_REM_S,
              WasmOpcode.I64_REM_U,
              WasmOpcode.I64_AND,
              WasmOpcode.I64_OR,
              WasmOpcode.I64_XOR,
              WasmOpcode.I64_SHL,
              WasmOpcode.I64_SHR_S,
              WasmOpcode.I64_SHR_U ->
          binary(WasmOpcode.TYPE_I64, WasmOpcode.TYPE_I64);
      case WasmOpcode.F64_ABS,
              WasmOpcode.F64_NEG,
              WasmOpcode.F64_CEIL,
              WasmOpcode.F64_FLOOR,
              WasmOpcode.F64_TRUNC,
              WasmOpcode.F64_NEAREST,
              WasmOpcode.F64_SQRT ->
          unary(WasmOpcode.TYPE_F64, WasmOpcode.TYPE_F64);
      case WasmOpcode.F64_ADD,
              WasmOpcode.F64_SUB,
              WasmOpcode.F64_MUL,
              WasmOpcode.F64_DIV,
              WasmOpcode.F64_MIN,
              WasmOpcode.F64_MAX,
              WasmOpcode.F64_COPYSIGN ->
          binary(WasmOpcode.TYPE_F64, WasmOpcode.TYPE_F64);
      case WasmOpcode.I32_WRAP_I64 -> unary(WasmOpcode.TYPE_I64, WasmOpcode.TYPE_I32);
      case WasmOpcode.I32_TRUNC_F64_S, WasmOpcode.I32_TRUNC_F64_U ->
          unary(WasmOpcode.TYPE_F64, WasmOpcode.TYPE_I32);
      case WasmOpcode.I64_EXTEND_I32_S, WasmOpcode.I64_EXTEND_I32_U ->
          unary(WasmOpcode.TYPE_I32, WasmOpcode.TYPE_I64);
      case WasmOpcode.I64_TRUNC_F64_S, WasmOpcode.I64_TRUNC_F64_U ->
          unary(WasmOpcode.TYPE_F64, WasmOpcode.TYPE_I64);
      case WasmOpcode.F64_CONVERT_I32_S, WasmOpcode.F64_CONVERT_I32_U ->
          unary(WasmOpcode.TYPE_I32, WasmOpcode.TYPE_F64);
      case WasmOpcode.F64_CONVERT_I64_S, WasmOpcode.F64_CONVERT_I64_U ->
          unary(WasmOpcode.TYPE_I64, WasmOpcode.TYPE_F64);
      case WasmOpcode.I64_REINTERPRET_F64 -> unary(WasmOpcode.TYPE_F64, WasmOpcode.TYPE_I64);
      case WasmOpcode.F64_REINTERPRET_I64 -> unary(WasmOpcode.TYPE_I64, WasmOpcode.TYPE_F64);
      case WasmOpcode.MEMORY_SIZE -> push(WasmOpcode.TYPE_I32);
      case WasmOpcode.MEMORY_GROW -> {
        pop(WasmOpcode.TYPE_I32);
        push(WasmOpcode.TYPE_I32);
      }
      default -> throw error("unsupported opcode 0x%02X in validator".formatted(opcode));
    }
  }

  private void applyIndexed(final int opcode, final int index) {
    switch (opcode) {
      case WasmOpcode.LOCAL_GET -> push(localType(index));
      case WasmOpcode.LOCAL_SET -> pop(localType(index));
      case WasmOpcode.LOCAL_TEE -> {
        pop(localType(index));
        push(localType(index));
      }
      case WasmOpcode.GLOBAL_GET -> push(module.globalType(index));
      case WasmOpcode.GLOBAL_SET -> pop(module.globalType(index));
      case WasmOpcode.CALL -> {
        final var type = module.functionType(index);
        popTypes(type.params());
        pushTypes(type.results());
      }
      case WasmOpcode.BR -> {
        final var frame = labelFrame(index);
        popTypes(frame.labelTypes());
        setUnreachable();
      }
      case WasmOpcode.BR_IF -> {
        pop(WasmOpcode.TYPE_I32);
        final var frame = labelFrame(index);
        popTypes(frame.labelTypes());
        pushTypes(frame.labelTypes());
      }
      default -> throw error("unsupported indexed opcode 0x%02X in validator".formatted(opcode));
    }
  }

  private void applyBlock(final int opcode, final int type) {
    final var endTypes = type == WasmOpcode.TYPE_VOID ? new int[] {} : new int[] {type};
    final int[] labelTypes;
    final Kind kind;
    switch (opcode) {
      case WasmOpcode.BLOCK -> {
        kind = Kind.BLOCK;
        labelTypes = endTypes;
      }
      case WasmOpcode.LOOP -> {
        kind = Kind.LOOP;
        labelTypes = new int[] {};
      }
      case WasmOpcode.IF -> {
        pop(WasmOpcode.TYPE_I32);
        kind = Kind.IF;
        labelTypes = endTypes;
      }
      default -> throw error("unsupported block opcode 0x%02X in validator".formatted(opcode));
    }
    frames.push(new ControlFrame(kind, stack.size(), labelTypes, endTypes));
  }

  private void applyMemory(final int opcode) {
    switch (opcode) {
      case WasmOpcode.I32_LOAD,
          WasmOpcode.I32_LOAD8_S,
          WasmOpcode.I32_LOAD8_U,
          WasmOpcode.I32_LOAD16_S,
          WasmOpcode.I32_LOAD16_U -> {
        pop(WasmOpcode.TYPE_I32);
        push(WasmOpcode.TYPE_I32);
      }
      case WasmOpcode.I64_LOAD,
          WasmOpcode.I64_LOAD8_S,
          WasmOpcode.I64_LOAD8_U,
          WasmOpcode.I64_LOAD16_S,
          WasmOpcode.I64_LOAD16_U,
          WasmOpcode.I64_LOAD32_S,
          WasmOpcode.I64_LOAD32_U -> {
        pop(WasmOpcode.TYPE_I32);
        push(WasmOpcode.TYPE_I64);
      }
      case WasmOpcode.F64_LOAD -> {
        pop(WasmOpcode.TYPE_I32);
        push(WasmOpcode.TYPE_F64);
      }
      case WasmOpcode.I32_STORE, WasmOpcode.I32_STORE8, WasmOpcode.I32_STORE16 -> {
        pop(WasmOpcode.TYPE_I32);
        pop(WasmOpcode.TYPE_I32);
      }
      case WasmOpcode.I64_STORE,
          WasmOpcode.I64_STORE8,
          WasmOpcode.I64_STORE16,
          WasmOpcode.I64_STORE32 -> {
        pop(WasmOpcode.TYPE_I64);
        pop(WasmOpcode.TYPE_I32);
      }
      case WasmOpcode.F64_STORE -> {
        pop(WasmOpcode.TYPE_F64);
        pop(WasmOpcode.TYPE_I32);
      }
      default -> throw error("unsupported memory opcode 0x%02X in validator".formatted(opcode));
    }
  }

  private void applyCallIndirect(final int typeIndex) {
    pop(WasmOpcode.TYPE_I32);
    final var type = module.type(typeIndex);
    popTypes(type.params());
    pushTypes(type.results());
  }

  private void handleElse() {
    final var frame = currentFrame();
    if (frame.kind() != Kind.IF) {
      throw error("else without matching if");
    }
    if (frame.elseSeen()) {
      throw error("duplicate else in if frame");
    }
    popTypes(frame.endTypes());
    requireHeight(frame.height());
    frame.setElseSeen(true);
    frame.setUnreachable(false);
  }

  private void handleEnd() {
    if (frames.size() == 1) {
      throw error("unexpected end at function scope");
    }
    final var frame = frames.pop();
    if (frame.kind() == Kind.IF && !frame.elseSeen() && frame.endTypes().length > 0) {
      throw error("if without else cannot produce a value");
    }
    popTypes(frame.endTypes());
    requireHeight(frame.height());
    pushTypes(frame.endTypes());
  }

  private void unary(final int paramType, final int resultType) {
    pop(paramType);
    push(resultType);
  }

  private void binary(final int paramType, final int resultType) {
    pop(paramType);
    pop(paramType);
    push(resultType);
  }

  private void pushTypes(final int[] types) {
    for (final var type : types) {
      push(type);
    }
  }

  private void popTypes(final int[] types) {
    for (var i = types.length - 1; i >= 0; i--) {
      pop(types[i]);
    }
  }

  private void push(final int type) {
    stack.add(type);
  }

  private int pop(final int expectedType) {
    final var frame = currentFrame();
    if (stack.size() == frame.height() && frame.unreachable()) {
      return expectedType;
    }
    if (stack.size() <= frame.height()) {
      throw error("stack underflow while popping " + typeName(expectedType));
    }
    final var actual = stack.remove(stack.size() - 1);
    if (actual != TYPE_UNKNOWN && expectedType != TYPE_UNKNOWN && actual != expectedType) {
      throw error(
          "type mismatch: expected %s but found %s"
              .formatted(typeName(expectedType), typeName(actual)));
    }
    return actual;
  }

  private int popAny() {
    final var frame = currentFrame();
    if (stack.size() == frame.height() && frame.unreachable()) {
      return TYPE_UNKNOWN;
    }
    if (stack.size() <= frame.height()) {
      throw error("stack underflow while popping value");
    }
    return stack.remove(stack.size() - 1);
  }

  private void requireHeight(final int height) {
    if (stack.size() != height) {
      throw error("expected stack height %d but found %d".formatted(height, stack.size()));
    }
  }

  private void setUnreachable() {
    final var frame = currentFrame();
    while (stack.size() > frame.height()) {
      stack.remove(stack.size() - 1);
    }
    frame.setUnreachable(true);
  }

  private ControlFrame currentFrame() {
    return frames.peek();
  }

  private ControlFrame labelFrame(final int depth) {
    var i = 0;
    for (final var frame : frames) {
      if (i == depth) return frame;
      i++;
    }
    throw error("invalid branch depth " + depth);
  }

  private int localType(final int index) {
    if (index < 0 || index >= locals.length) {
      throw error("invalid local index " + index);
    }
    return locals[index];
  }

  private int mergeTypes(final int left, final int right) {
    if (left == TYPE_UNKNOWN) return right;
    if (right == TYPE_UNKNOWN) return left;
    if (left == right) return left;
    throw error(
        "select operands have incompatible types %s and %s"
            .formatted(typeName(left), typeName(right)));
  }

  private CompilerException error(final String message) {
    return new CompilerException(
        "WASM stack validation failed in function " + functionIndex + ": " + message);
  }

  private String typeName(final int type) {
    return switch (type) {
      case TYPE_UNKNOWN -> "unknown";
      case WasmOpcode.TYPE_I32 -> "i32";
      case WasmOpcode.TYPE_I64 -> "i64";
      case WasmOpcode.TYPE_F64 -> "f64";
      case WasmOpcode.TYPE_VOID -> "void";
      default -> "0x%02X".formatted(type);
    };
  }

  private enum Kind {
    ROOT,
    BLOCK,
    LOOP,
    IF
  }

  private static final class ControlFrame {
    private final Kind kind;
    private final int height;
    private final int[] labelTypes;
    private final int[] endTypes;
    private boolean unreachable;
    private boolean elseSeen;

    private ControlFrame(
        final Kind kind, final int height, final int[] labelTypes, final int[] endTypes) {
      this.kind = kind;
      this.height = height;
      this.labelTypes = labelTypes;
      this.endTypes = endTypes;
    }

    private Kind kind() {
      return kind;
    }

    private int height() {
      return height;
    }

    private int[] labelTypes() {
      return labelTypes;
    }

    private int[] endTypes() {
      return endTypes;
    }

    private boolean unreachable() {
      return unreachable;
    }

    private void setUnreachable(final boolean unreachable) {
      this.unreachable = unreachable;
    }

    private boolean elseSeen() {
      return elseSeen;
    }

    private void setElseSeen(final boolean elseSeen) {
      this.elseSeen = elseSeen;
    }
  }
}
