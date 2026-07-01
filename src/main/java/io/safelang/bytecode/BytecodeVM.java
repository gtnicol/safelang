package io.safelang.bytecode;

import io.safelang.SAFEException;
import io.safelang.runtime.BinaryFileHandle;
import io.safelang.runtime.BuiltinExecutors;
import io.safelang.runtime.BuiltinFunction;
import io.safelang.runtime.BuiltinRegistry;
import io.safelang.runtime.FileHandle;
import io.safelang.runtime.Measures;
import io.safelang.runtime.RangeSemantics;
import io.safelang.runtime.SAFEValue;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Scanner;
import java.util.concurrent.atomic.AtomicInteger;

/** Stack-based virtual machine for executing SAFE bytecode. */
public class BytecodeVM {

  private static final int MAX_DEPTH = 1000;
  private static final int CONTRACT_SENTINEL = -2;
  private final BytecodeModule module;
  private final ConstantPool pool;
  private final Deque<SAFEValue> stack;
  private final List<CallFrame> frames;
  private final Map<String, SAFEValue> globals;
  private final Map<String, Boolean> constants; // tracks const globals
  // Iterator state: keyed by tag(depth, slot)
  private final Map<Long, int[]> indices;
  private final Map<Long, List<SAFEValue>> sequences;
  private final Map<Integer, FileHandle> handles;
  private final Map<Integer, BinaryFileHandle> binaries;
  private final Map<Integer, io.safelang.runtime.StreamHandle> streams;
  private final Map<String, Deque<Long>> measures = new HashMap<>();
  private final BuiltinExecutors builtins = new BuiltinExecutors();
  private final Map<String, Integer> lookup = new HashMap<>();
  private byte[] obligation; // non-null when executing a contract chunk
  private byte[] bytecode; // bytecode currently executing
  private int pc; // program counter
  private int active; // -1 for main
  private boolean halted;
  private Writer output; // optional capture writer; null = System.out

  public BytecodeVM(final BytecodeModule module) {
    this(module, List.of());
  }

  public BytecodeVM(final BytecodeModule module, final List<String> arguments) {
    this(module, arguments, io.safelang.runtime.HostPolicy.trusted());
  }

  public BytecodeVM(
      final BytecodeModule module,
      final List<String> arguments,
      final io.safelang.runtime.Capabilities capabilities) {
    this(module, arguments, io.safelang.runtime.HostPolicy.of(capabilities));
  }

  public BytecodeVM(
      final BytecodeModule module,
      final List<String> arguments,
      final io.safelang.runtime.HostPolicy policy) {
    this.module = module;
    this.pool = module.pool();
    this.stack = new ArrayDeque<>();
    this.frames = new ArrayList<>();
    this.globals = new HashMap<>();
    this.constants = new HashMap<>();
    this.indices = new HashMap<>();
    this.sequences = new HashMap<>();
    this.handles = new HashMap<>();
    this.binaries = new HashMap<>();
    this.streams = new HashMap<>();
    this.halted = false;

    // Register system globals, skipping any whose capability this policy did not grant (the
    // host-dependent OS/ARCH/OS_VERSION/PLATFORM require ENVIRONMENT), matching the interpreter.
    BuiltinRegistry.variables()
        .forEach(
            (name, value) -> {
              final var capability = BuiltinRegistry.variableCapability(name);
              if (capability == null || policy.capabilities().granted(capability)) {
                globals.put(name, value);
              }
            });

    // Register unit enum variants as globals
    for (final var info : module.enums()) {
      for (int i = 0; i < info.variants().size(); i++) {
        final var variant = info.variants().get(i);
        if (!variant.hasFields()) {
          final var value = SAFEValue.ofEnum(info.name(), variant.name(), new ArrayList<>());
          globals.put(variant.name(), value);
        }
      }
    }

    // Register name-based builtin executors (shared with interpreter).
    // The () -> output supplier lets test runners install a capture writer
    // via setOutput() instead of rebinding System.out globally.
    final var counter = new AtomicInteger(0);
    final var scanner = new Scanner(System.in, StandardCharsets.UTF_8);
    final Random[] random = {new Random()};
    io.safelang.interpreter.builtins.BuiltinRegistration.registerAll(
        builtins,
        () -> output,
        scanner,
        random,
        handles,
        binaries,
        streams,
        counter,
        arguments,
        policy);

    // Build name-to-index lookup for user functions
    for (int i = 0; i < module.functions().size(); i++) {
      lookup.put(module.function(i).name(), i);
    }
  }

  private static long tag(final int depth, final int slot) {
    return ((long) depth << 32) | (slot & 0xFFFFL);
  }

  /**
   * Install a capture writer for IO builtins (print/println). When set, all output goes through
   * this writer instead of {@link System#out}. The writer is consumed by the registered {@code
   * IoBuiltins} via the {@code () -> output} supplier passed at construction.
   */
  public void setOutput(final Writer writer) {
    this.output = writer;
  }

  /** Execute the loaded bytecode module. */
  public void execute() {
    bytecode = module.main();
    pc = 0;
    active = -1;

    // Create a synthetic call frame for main code (for local variables)
    final var frame = new CallFrame("__main__", 0, -1, module.locals());
    frames.add(frame);

    io.safelang.runtime.HostCallback.set(this::apply);
    try {
      while (!halted && pc < bytecode.length) {
        step();
      }
    } finally {
      cleanup();
    }
  }

  private void cleanup() {
    io.safelang.runtime.HostCallback.clear();
    io.safelang.interpreter.builtins.BuiltinRegistration.closeHandles(handles, binaries, streams);
  }

  /**
   * Push a call frame for a closure value and switch the VM to its bytecode. Shared by the {@code
   * CALL_VALUE} opcode (which then continues the main dispatch loop) and {@link #apply} (which runs
   * a bounded nested loop until the frame returns).
   */
  private void pushClosureFrame(
      final SAFEValue.FunctionValue functionValue, final SAFEValue[] args) {
    final var closure = functionValue.asClosure();
    reject(closure.name(), args);
    final var position = lookup.getOrDefault(closure.name(), -1);
    if (position < 0) {
      throw new BytecodeException("Unknown closure target: " + closure.name());
    }
    final var info = module.function(position);
    if (frames.size() >= MAX_DEPTH) {
      throw new BytecodeException("Stack overflow: maximum call depth exceeded");
    }
    // The frame records the caller's pc/active so RETURN restores them.
    frames.add(new CallFrame(info.name(), pc, active, info.locals()));
    final var frame = frames.getLast();
    for (int i = 0; i < args.length; i++) {
      frame.setLocal(i, args[i]);
    }
    final var captured = closure.captures();
    if (captured != null) {
      for (int i = 0; i < captured.length; i++) {
        frame.setLocal(info.parameters() + i, captured[i]);
      }
    }
    requires(info);
    decreases(info);
    bytecode = info.bytecode();
    pc = 0;
    active = position;
  }

  /**
   * Synchronously invoke a SAFE function value from host code (the {@link
   * io.safelang.runtime.HostCallback} entry point used by {@code http:serve}). Sets up the callee
   * frame, then steps a nested loop until that frame returns — leaving the result on the stack —
   * before restoring the caller's dispatch state.
   */
  SAFEValue apply(final SAFEValue function, final java.util.List<SAFEValue> arguments) {
    if (!(function instanceof SAFEValue.FunctionValue functionValue)) {
      throw new BytecodeException("Cannot call non-function value: " + function.type());
    }
    final var args = arguments.toArray(new SAFEValue[0]);
    final var savedBytecode = bytecode;
    final var savedPc = pc;
    final var savedActive = active;
    pushClosureFrame(functionValue, args);
    final var depth = frames.size();
    while (frames.size() >= depth && !halted) {
      step();
    }
    final var result = pop();
    bytecode = savedBytecode;
    pc = savedPc;
    active = savedActive;
    return result;
  }

  private void step() {
    final var opcode = bytecode[pc++] & 0xFF;
    OpCode op;
    try {
      op = OpCode.fromCode(opcode);
    } catch (BytecodeException e) {
      throw new BytecodeException(
          "Unknown opcode 0x" + Integer.toHexString(opcode) + " at pc=" + (pc - 1));
    }

    switch (op) {
        // Constants
      case CONST_INT:
        {
          final var index = readShort();
          push(SAFEValue.ofInt(pool.getInt(index)));
          break;
        }
      case CONST_FLOAT:
        {
          final var index = readShort();
          push(SAFEValue.ofFloat(pool.getFloat(index)));
          break;
        }
      case CONST_STR:
        {
          final var index = readShort();
          push(SAFEValue.ofString(pool.getString(index)));
          break;
        }
      case CONST_UINT:
        {
          final var index = readShort();
          push(SAFEValue.ofUint(pool.getInt(index)));
          break;
        }
      case PUSH_TRUE:
        push(SAFEValue.ofBoolean(true));
        break;
      case PUSH_FALSE:
        push(SAFEValue.ofBoolean(false));
        break;
      case PUSH_VOID:
        push(SAFEValue.ofVoid());
        break;

        // Stack
      case POP:
        pop();
        break;
      case DUP:
        push(peek());
        break;
      case NOP:
        break;

        // Variables
      case LOAD_LOCAL:
        {
          final var slot = readShort();
          push(frame().local(slot));
          break;
        }
      case STORE_LOCAL:
        {
          final var slot = readShort();
          frame().setLocal(slot, pop().copy());
          break;
        }
      case LOAD_GLOBAL:
        {
          final var index = readShort();
          final var name = pool.getString(index);
          final var value = globals.get(name);
          if (value == null) {
            throw new BytecodeException("Undefined global variable: " + name);
          }
          push(value);
          break;
        }
      case STORE_GLOBAL:
        {
          final var index = readShort();
          final var name = pool.getString(index);
          final var value = pop();
          if (constants.getOrDefault(name, false)) {
            throw new BytecodeException("Cannot assign to const variable: " + name);
          }
          globals.put(name, value.copy());
          // Mark as const after first store (initialization) if declared const
          for (final var global : module.globals()) {
            if (global.name().equals(name) && global.isConst()) {
              constants.put(name, true);
              break;
            }
          }
          break;
        }

        // Arithmetic
      case ADD:
        add();
        break;
      case SUB:
        subtract();
        break;
      case MUL:
        multiply();
        break;
      case DIV:
        divide();
        break;
      case MOD:
        modulo();
        break;
      case NEG:
        negate();
        break;

        // Comparison
      case CMP_EQ:
        {
          final var r = pop();
          final var l = pop();
          push(SAFEValue.ofBoolean(l.equals(r)));
          break;
        }
      case CMP_NE:
        {
          final var r = pop();
          final var l = pop();
          push(SAFEValue.ofBoolean(!l.equals(r)));
          break;
        }
      case CMP_LT:
        {
          final var r = pop();
          final var l = pop();
          push(SAFEValue.ofBoolean(compare(l, r) < 0));
          break;
        }
      case CMP_LE:
        {
          final var r = pop();
          final var l = pop();
          push(SAFEValue.ofBoolean(compare(l, r) <= 0));
          break;
        }
      case CMP_GT:
        {
          final var r = pop();
          final var l = pop();
          push(SAFEValue.ofBoolean(compare(l, r) > 0));
          break;
        }
      case CMP_GE:
        {
          final var r = pop();
          final var l = pop();
          push(SAFEValue.ofBoolean(compare(l, r) >= 0));
          break;
        }

        // Logic
      case NOT:
        {
          final var v = pop();
          push(SAFEValue.ofBoolean(!v.asBoolean()));
          break;
        }

        // Control flow
      case JUMP:
        {
          final var offset = readSignedShort();
          pc += offset;
          break;
        }
      case JUMP_FALSE:
        {
          final var offset = readSignedShort();
          final var cond = pop();
          if (!cond.asBoolean()) {
            pc += offset;
          }
          break;
        }
      case JUMP_TRUE:
        {
          final var offset = readSignedShort();
          final var cond = pop();
          if (cond.asBoolean()) {
            pc += offset;
          }
          break;
        }
      case RETURN:
        {
          final var result = pop();
          // If only the main frame is left, we're returning from main itself
          if (frames.size() <= 1) {
            push(result);
            halted = true;
            return;
          }

          // Check ensures contract and pop decreases before popping the frame
          if (active >= 0) {
            final var returning = module.function(active);
            try {
              ensures(returning, result);
            } finally {
              measure(returning);
            }
          }

          // Pop the function frame and restore the caller
          final var frame = frames.removeLast();

          if (frame.chunk() == CONTRACT_SENTINEL) {
            // Returning into a contract chunk (requires/ensures/decreases)
            bytecode = obligation;
          } else {
            bytecode =
                frame.chunk() == -1 ? module.main() : module.function(frame.chunk()).bytecode();
          }
          pc = frame.address();
          active = frame.chunk();
          push(result);
          break;
        }
      case HALT:
        {
          halted = true;
          break;
        }

        // Functions
      case CALL:
        {
          final var index = readShort();
          final var argc = readByte();
          final var name = pool.getString(index);
          // User functions take priority over builtins (shadowing)
          final var target = lookup.get(name);
          if (target != null) {
            call(target, argc);
          } else {
            final var executor = builtins.get(name);
            if (executor != null) {
              builtin(name, executor, argc);
            } else {
              throw new BytecodeException("Undefined function: " + name);
            }
          }
          break;
        }

        // Objects
      case NEW_OBJECT:
        {
          final var index = readShort();
          final var count = readByte();
          final var type = module.type(index);
          // Stack has pairs of (name_string, value) in source order; pop in reverse.
          final var names = new String[count];
          final var values = new SAFEValue[count];
          for (int i = count - 1; i >= 0; i--) {
            values[i] = pop();
            names[i] = pop().asString();
          }
          final var assigned = new HashMap<String, SAFEValue>();
          for (int i = 0; i < count; i++) {
            assigned.put(names[i], values[i]);
          }
          // Populate in declaration order so print output mirrors the type.
          final Map<String, SAFEValue> fields = new LinkedHashMap<>();
          for (final var fi : type.fields()) {
            fields.put(fi.name(), assigned.getOrDefault(fi.name(), SAFEValue.ofVoid()));
          }
          for (final var entry : assigned.entrySet()) {
            fields.putIfAbsent(entry.getKey(), entry.getValue());
          }
          push(SAFEValue.ofObject(type.name(), fields));
          break;
        }
      case GET_FIELD:
        {
          final var index = readShort();
          final var field = pool.getString(index);
          final var obj = pop();
          if (!obj.isObject()) {
            throw new BytecodeException("Cannot get field '" + field + "' from non-object");
          }
          final var fields = obj.fields();
          final var value = fields.get(field);
          if (value == null) {
            throw new BytecodeException("Field not found: " + field);
          }
          push(value);
          break;
        }
      case SET_FIELD:
        {
          final var index = readShort();
          final var field = pool.getString(index);
          final var value = pop();
          final var obj = pop();
          if (!obj.isObject()) {
            throw new BytecodeException("Cannot set field on non-object");
          }
          obj.setField(field, value);
          push(value);
          break;
        }

        // Collections
      case NEW_LIST:
        {
          final var count = readShort();
          List<SAFEValue> elements = new ArrayList<>();
          // Elements are on stack in order, need to reverse pop
          final var arr = new SAFEValue[count];
          for (int i = count - 1; i >= 0; i--) {
            arr[i] = pop();
          }
          Collections.addAll(elements, arr);
          push(SAFEValue.ofList(elements));
          break;
        }
      case NEW_MAP:
        {
          final var count = readShort();
          Map<SAFEValue, SAFEValue> map = new LinkedHashMap<>();
          // Pairs pushed as key, value; need reverse pop
          final var keys = new SAFEValue[count];
          final var vals = new SAFEValue[count];
          for (int i = count - 1; i >= 0; i--) {
            vals[i] = pop();
            keys[i] = pop();
          }
          for (int i = 0; i < count; i++) {
            map.put(keys[i], vals[i]);
          }
          push(SAFEValue.ofMap(map));
          break;
        }
      case GET_INDEX:
        {
          final var index = pop();
          final var container = pop();
          switch (container) {
            case SAFEValue.ListValue list -> push(list.element((int) index.asInt()));
            case SAFEValue.TupleValue(List<SAFEValue> elements) -> {
              final var position = (int) index.asInt();
              if (position < 0 || position >= elements.size()) {
                throw new BytecodeException("Tuple index out of bounds: " + position);
              }
              push(elements.get(position));
            }
            case SAFEValue.MapValue map -> push(map.entry(index));
            case SAFEValue.StringValue(String s) -> {
              final var position = (int) index.asInt();
              if (position < 0 || position >= s.length()) {
                throw new BytecodeException("String index out of bounds: " + position);
              }
              push(SAFEValue.ofString(String.valueOf(s.charAt(position))));
            }
            default -> throw new BytecodeException("Cannot index into " + container.type());
          }
          break;
        }
      case SET_INDEX:
        {
          final var value = pop();
          final var index = pop();
          final var container = pop();
          switch (container) {
            case SAFEValue.ListValue list -> list.setElement((int) index.asInt(), value);
            case SAFEValue.MapValue map -> map.setEntry(index, value);
            default -> throw new BytecodeException("Cannot index-assign into " + container.type());
          }
          break;
        }

        // Enums
      case NEW_ENUM:
        {
          final var index = readShort();
          final var variant = readShort();
          final var count = readByte();
          final var enumeration = module.enumeration(index);
          final var info = enumeration.variants().get(variant);
          List<SAFEValue> data = new ArrayList<>();
          final var elements = new SAFEValue[count];
          for (int i = count - 1; i >= 0; i--) {
            elements[i] = pop();
          }
          Collections.addAll(data, elements);
          push(SAFEValue.ofEnum(enumeration.name(), info.name(), data));
          break;
        }
      case MATCH_ENUM:
        {
          final var variant = readShort();
          final var offset = readSignedShort();
          final var top = pop(); // the DUPed enum value
          if (top instanceof SAFEValue.EnumValue enumValue) {
            // Find which enum this belongs to and check variant index
            final var name = enumValue.variant();
            final var enumeration = module.variant(name);
            if (enumeration != null && enumeration.getVariantIndex(name) == variant) {
              // Match! Push it back (the original is still on stack)
              // Actually, the DUP was already consumed. The original subject
              // is still deeper in the stack (from visitCaseExpression).
              break; // continue to extract bindings
            }
          }
          // No match — jump
          pc += offset;
          break;
        }
      case ENUM_DATA:
        {
          final var index = readByte();
          final var value = peek();
          if (!value.isEnum()) {
            throw new BytecodeException("ENUM_DATA on non-enum value");
          }
          final var data = value.data();
          if (index >= data.size()) {
            throw new BytecodeException("Enum data index out of bounds: " + index);
          }
          push(data.get(index));
          break;
        }

        // Assert
      case ASSERT:
        {
          final var index = readShort();
          final var cond = pop();
          if (!cond.asBoolean()) {
            final var msg = pool.getString(index);
            throw new BytecodeException(msg);
          }
          break;
        }
      case ASSERT_EXPR:
        {
          final var msg = pop();
          final var cond = pop();
          if (!cond.asBoolean()) {
            throw new BytecodeException(msg.asString());
          }
          break;
        }

        // Iteration
      case ITER_INIT:
        {
          final var slot = readShort();
          final var iterable = pop();
          final List<SAFEValue> items =
              switch (iterable) {
                case SAFEValue.ListValue list -> list.asList();
                case SAFEValue.StringValue(String chars) -> {
                  final var result = new ArrayList<SAFEValue>(chars.length());
                  for (int i = 0; i < chars.length(); i++) {
                    result.add(SAFEValue.ofString(String.valueOf(chars.charAt(i))));
                  }
                  yield result;
                }
                case SAFEValue.SetValue set -> new ArrayList<>(set.asSet());
                case SAFEValue.MapValue map -> new ArrayList<>(map.asMap().keySet());
                default ->
                    throw new BytecodeException(
                        "Cannot iterate over non-list/non-string/non-set/non-map");
              };
          // Store the list and initialize index to 0
          final var key = tag(frames.size(), slot);
          sequences.put(key, items);
          indices.put(key, new int[] {0});
          frame().setLocal(slot, SAFEValue.ofInt(0)); // iterator slot
          break;
        }
      case ITER_NEXT:
        {
          final var slot = readShort();
          final var offset = readSignedShort();
          // Find the iterator slot — it's the slot before slot
          final var position = slot - 1;
          final var key = tag(frames.size(), position);
          final var list = sequences.get(key);
          final var cursor = indices.get(key);

          if (list == null || cursor == null || cursor[0] >= list.size()) {
            // Exhausted — jump to end
            pc += offset;
            // Clean up
            if (list != null) {
              sequences.remove(key);
              indices.remove(key);
            }
          } else {
            // Get next element, store in slot
            final var element = list.get(cursor[0]);
            frame().setLocal(slot, element);
            cursor[0]++;
          }
          break;
        }

        // In check
      case IN_CHECK:
        {
          final var container = pop();
          final var element = pop();
          push(
              switch (container) {
                case SAFEValue.ListValue list -> {
                  boolean found = false;
                  for (final var item : list.asList()) {
                    if (element.equals(item)) {
                      found = true;
                      break;
                    }
                  }
                  yield SAFEValue.ofBoolean(found);
                }
                case SAFEValue.MapValue map ->
                    SAFEValue.ofBoolean(map.asMap().containsKey(element));
                case SAFEValue.SetValue set -> SAFEValue.ofBoolean(set.asSet().contains(element));
                case SAFEValue.StringValue(String s) ->
                    SAFEValue.ofBoolean(s.contains(element.asString()));
                default -> throw new BytecodeException("'in' requires list, map, set, or string");
              });
          break;
        }

      case NEW_TUPLE:
        {
          final var count = readShort();
          if (count > SAFEValue.MAX_TUPLE_SIZE) {
            throw new BytecodeException(
                "Tuple size " + count + " exceeds maximum of " + SAFEValue.MAX_TUPLE_SIZE);
          }
          final var elements = new SAFEValue[count];
          for (int i = count - 1; i >= 0; i--) {
            elements[i] = pop();
          }
          push(SAFEValue.ofTuple(Arrays.asList(elements)));
          break;
        }

      case CLOSURE:
        {
          final var index = readShort();
          final var captures = readByte();
          SAFEValue[] captured = null;
          if (captures > 0) {
            captured = new SAFEValue[captures];
            for (int i = captures - 1; i >= 0; i--) {
              captured[i] = pop().copy();
            }
          }
          push(
              SAFEValue.ofFunction(
                  io.safelang.runtime.Closure.bytecode(module.function(index).name(), captured)));
          break;
        }

      case CALL_VALUE:
        {
          final var argc = readByte();
          final var args = new SAFEValue[argc];
          for (int i = argc - 1; i >= 0; i--) {
            args[i] = pop();
          }
          final var function = pop();
          if (!(function instanceof SAFEValue.FunctionValue functionValue)) {
            throw new BytecodeException("Cannot call non-function value: " + function.type());
          }
          pushClosureFrame(functionValue, args);
          break;
        }

      case NEW_SET:
        {
          final var count = readShort();
          final var elements = new LinkedHashSet<SAFEValue>();
          final var temp = new SAFEValue[count];
          for (int i = count - 1; i >= 0; i--) {
            temp[i] = pop();
          }
          Collections.addAll(elements, temp);
          push(SAFEValue.ofSet(elements));
          break;
        }

      case NEW_RANGE:
        {
          final var end = pop().asInt();
          final var start = pop().asInt();
          try {
            push(SAFEValue.ofList(RangeSemantics.build(start, end, 1)));
          } catch (final SAFEException error) {
            throw new BytecodeException(error.getMessage());
          }
          break;
        }

        // Bitwise operations
      case BIT_AND:
        {
          final var right = pop();
          final var left = pop();
          try {
            push(SAFEValue.bitwiseAnd(left, right));
          } catch (RuntimeException e) {
            throw new BytecodeException(e.getMessage());
          }
          break;
        }
      case BIT_OR:
        {
          final var right = pop();
          final var left = pop();
          try {
            push(SAFEValue.bitwiseOr(left, right));
          } catch (RuntimeException e) {
            throw new BytecodeException(e.getMessage());
          }
          break;
        }
      case BIT_XOR:
        {
          final var right = pop();
          final var left = pop();
          try {
            push(SAFEValue.bitwiseXor(left, right));
          } catch (RuntimeException e) {
            throw new BytecodeException(e.getMessage());
          }
          break;
        }
      case BIT_NOT:
        {
          try {
            push(SAFEValue.bitwiseNot(pop()));
          } catch (RuntimeException e) {
            throw new BytecodeException(e.getMessage());
          }
          break;
        }
      case BIT_SHL:
        {
          final var right = pop();
          final var left = pop();
          try {
            push(SAFEValue.shiftLeft(left, right));
          } catch (RuntimeException e) {
            throw new BytecodeException(e.getMessage());
          }
          break;
        }
      case BIT_SHR:
        {
          final var right = pop();
          final var left = pop();
          try {
            push(SAFEValue.shiftRight(left, right));
          } catch (RuntimeException e) {
            throw new BytecodeException(e.getMessage());
          }
          break;
        }

        // Range with step
      case NEW_RANGE_STEP:
        {
          final var increment = pop().asInt();
          final var end = pop().asInt();
          final var start = pop().asInt();
          try {
            push(SAFEValue.ofList(RangeSemantics.build(start, end, increment)));
          } catch (final SAFEException error) {
            throw new BytecodeException(error.getMessage());
          }
          break;
        }

        // Tail call optimization
      case TAIL_CALL:
        {
          final var index = readShort();
          final var argc = readByte();
          final var name = pool.getString(index);
          final var target = lookup.get(name);
          if (target == null) {
            throw new BytecodeException("Undefined function for tail call: " + name);
          }
          final var function = module.function(target);
          // Collect arguments
          final var args = new SAFEValue[argc];
          for (int i = argc - 1; i >= 0; i--) {
            args[i] = pop();
          }
          reject(function.name(), args);
          // Reuse current frame - reset locals with new args
          final var current = frame();
          for (int i = 0; i < current.locals(); i++) {
            current.setLocal(i, SAFEValue.ofVoid());
          }
          for (int i = 0; i < argc; i++) {
            current.setLocal(i, args[i]);
          }
          requires(function);
          // Pop previous decreases entry before pushing new one to prevent leak
          measure(function);
          decreases(function);
          // Jump back to start of function
          bytecode = function.bytecode();
          pc = 0;
          active = target;
          break;
        }

      default:
        throw new BytecodeException("Unimplemented opcode: " + op.mnemonic());
    }
  }

  private void call(final int index, final int argc) {
    final var function = module.function(index);

    // Collect arguments from stack
    final var args = new SAFEValue[argc];
    for (int i = argc - 1; i >= 0; i--) {
      args[i] = pop();
    }

    reject(function.name(), args);

    // Push call frame
    final var frame =
        new CallFrame(
            function.name(),
            pc, // return to current PC
            active, // return to current function
            function.locals());

    // Bind parameters to local slots
    for (int i = 0; i < argc; i++) {
      frame.setLocal(i, args[i]);
    }

    if (frames.size() >= MAX_DEPTH) {
      throw new BytecodeException("Maximum recursion depth exceeded (" + MAX_DEPTH + ")");
    }

    frames.add(frame);

    requires(function);
    decreases(function);

    // Execute function bytecode
    bytecode = function.bytecode();
    pc = 0;
    active = index;
  }

  private void builtin(final String name, final BuiltinFunction executor, final int argc) {
    if (argc > stack.size()) {
      throw new BytecodeException(
          "Stack underflow: builtin "
              + name
              + " requires "
              + argc
              + " args but stack has "
              + stack.size());
    }
    final var args = new ArrayList<SAFEValue>(argc);
    for (int i = 0; i < argc; i++) {
      args.add(null);
    }
    for (int i = argc - 1; i >= 0; i--) {
      args.set(i, pop());
    }
    try {
      final var result = executor.execute(args);
      push(result != null ? result : SAFEValue.ofVoid());
    } catch (final RuntimeException error) {
      throw new BytecodeException(name + "(): " + error.getMessage());
    }
  }

  private void reject(final String name, final SAFEValue[] args) {
    for (final var argument : args) {
      if (argument.isFloat() && Double.isNaN(argument.asFloat())) {
        throw new BytecodeException("NaN is not allowed as an argument to function '" + name + "'");
      }
    }
  }

  /**
   * Execute a contract/clause bytecode chunk (requires, ensures, or decreases) within the current
   * call frame context. Handles nested function calls by using a sentinel function index (-2) and
   * tracking the active contract chunk, so RETURN from nested calls properly restores the chunk.
   */
  private SAFEValue execute(final byte[] chunk, final String label) {
    final var depth = stack.size();
    final var saved = bytecode;
    final var address = pc;
    final var caller = active;
    final var contract = obligation;
    obligation = chunk;
    bytecode = chunk;
    active = CONTRACT_SENTINEL;
    pc = 0;
    try {
      while (pc < bytecode.length) step();
      final var result = pop();
      if (stack.size() != depth) {
        throw new BytecodeException("Contract bytecode left unbalanced stack for: " + label);
      }
      return result;
    } finally {
      obligation = contract;
      bytecode = saved;
      pc = address;
      active = caller;
    }
  }

  private void requires(final FunctionDefinition function) {
    if (!function.hasRequires()) return;
    final var result = execute(function.requires(), function.name());
    if (!result.asBoolean()) {
      throw new BytecodeException("Requires contract failed for function: " + function.name());
    }
  }

  private void ensures(final FunctionDefinition function, final SAFEValue result) {
    if (!function.hasEnsures()) return;
    final var slot = function.locals() - 1;
    frame().setLocal(slot, result);
    final var check = execute(function.ensures(), function.name());
    if (!check.asBoolean()) {
      throw new BytecodeException("Ensures contract failed for function: " + function.name());
    }
  }

  private void decreases(final FunctionDefinition function) {
    if (!function.hasDecreases()) return;
    final var measure = execute(function.decreases(), function.name()).asInt();
    try {
      Measures.push(measures, function.name(), measure);
    } catch (final SAFEException error) {
      throw new BytecodeException(error.getMessage());
    }
  }

  private void measure(final FunctionDefinition function) {
    if (!function.hasDecreases()) return;
    Measures.pop(measures, function.name());
  }

  private void add() {
    final var right = pop();
    final var left = pop();
    try {
      push(SAFEValue.add(left, right));
    } catch (RuntimeException e) {
      throw new BytecodeException(e.getMessage());
    }
  }

  private void subtract() {
    final var right = pop();
    final var left = pop();
    try {
      push(SAFEValue.subtract(left, right));
    } catch (RuntimeException e) {
      throw new BytecodeException(e.getMessage());
    }
  }

  private void multiply() {
    final var right = pop();
    final var left = pop();
    try {
      push(SAFEValue.multiply(left, right));
    } catch (RuntimeException e) {
      throw new BytecodeException(e.getMessage());
    }
  }

  private void divide() {
    final var right = pop();
    final var left = pop();
    try {
      push(SAFEValue.divide(left, right));
    } catch (RuntimeException e) {
      throw new BytecodeException(e.getMessage());
    }
  }

  private int compare(final SAFEValue left, final SAFEValue right) {
    try {
      return SAFEValue.compare(left, right);
    } catch (RuntimeException e) {
      throw new BytecodeException(e.getMessage());
    }
  }

  private void modulo() {
    final var right = pop();
    final var left = pop();
    try {
      push(SAFEValue.modulo(left, right));
    } catch (RuntimeException e) {
      throw new BytecodeException(e.getMessage());
    }
  }

  private void negate() {
    try {
      push(SAFEValue.negate(pop()));
    } catch (RuntimeException e) {
      throw new BytecodeException(e.getMessage());
    }
  }

  private void push(final SAFEValue value) {
    stack.push(value);
  }

  private SAFEValue pop() {
    if (stack.isEmpty()) {
      throw new BytecodeException("Stack underflow");
    }
    return stack.pop();
  }

  private SAFEValue peek() {
    if (stack.isEmpty()) {
      throw new BytecodeException("Stack underflow on peek");
    }
    return stack.peek();
  }

  private CallFrame frame() {
    if (frames.isEmpty()) {
      throw new BytecodeException("No call frame available");
    }
    return frames.getLast();
  }

  private int readByte() {
    return bytecode[pc++] & 0xFF;
  }

  private int readShort() {
    final var high = bytecode[pc++] & 0xFF;
    final var low = bytecode[pc++] & 0xFF;
    return (high << 8) | low;
  }

  private int readSignedShort() {
    int value = readShort();
    if (value > 32767) value -= 65536;
    return value;
  }
}
