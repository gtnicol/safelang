package io.safelang.compiler.jvm;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Emits the bytecode of a single method into a {@code Code} attribute body. Tracks {@code
 * max_stack} and {@code max_locals}, and back-patches forward branches once their targets are
 * marked.
 *
 * <p>Operand-stack depth is tracked per instruction so {@code max_stack} is computed exactly.
 * Generated code keeps the operand stack empty across every branch target, so no {@code
 * StackMapTable} is needed and the class file can stay at major version 50 (legacy verifier).
 *
 * <p>Branch targets are integer labels obtained from {@link #label()} and fixed in place with
 * {@link #mark(int)}; {@link #branch(int, int)} records a fixup that {@link #code()} resolves into
 * a signed 16-bit relative offset.
 */
final class MethodEmitter {

  private final ByteArrayOutputStream code = new ByteArrayOutputStream();
  private final Map<Integer, Integer> targets = new HashMap<>();
  private final List<Fixup> fixups = new ArrayList<>();
  private int labels;
  private int stack;
  private int maxStack;
  private int maxLocals;

  MethodEmitter(final int parameters) {
    maxLocals = parameters;
  }

  private static int argumentWords(final String descriptor) {
    var words = 0;
    var index = 1;
    while (descriptor.charAt(index) != ')') {
      final var ch = descriptor.charAt(index);
      if (ch == 'J' || ch == 'D') {
        words += 2;
        index++;
      } else if (ch == 'L') {
        words += 1;
        index = descriptor.indexOf(';', index) + 1;
      } else if (ch == '[') {
        index++;
        while (descriptor.charAt(index) == '[') {
          index++;
        }
        if (descriptor.charAt(index) == 'L') {
          index = descriptor.indexOf(';', index) + 1;
        } else {
          index++;
        }
        words += 1;
      } else {
        words += 1;
        index++;
      }
    }
    return words;
  }

  private static int returnWords(final String descriptor) {
    final var ret = descriptor.charAt(descriptor.indexOf(')') + 1);
    if (ret == 'V') {
      return 0;
    }
    if (ret == 'J' || ret == 'D') {
      return 2;
    }
    return 1;
  }

  /** Reserve {@code count} local slots starting at the current count and return the first slot. */
  int reserve(final int count) {
    final var first = maxLocals;
    maxLocals += count;
    return first;
  }

  int label() {
    return labels++;
  }

  void mark(final int label) {
    targets.put(label, code.size());
  }

  void aconstNull() {
    op(0x01);
    push(1);
  }

  void intConstant(final int value) {
    if (value >= -1 && value <= 5) {
      op(0x03 + value); // iconst_<n>, with iconst_m1 at 0x02
    } else if (value >= Byte.MIN_VALUE && value <= Byte.MAX_VALUE) {
      op(0x10); // bipush
      code.write(value & 0xff);
    } else if (value >= Short.MIN_VALUE && value <= Short.MAX_VALUE) {
      op(0x11); // sipush
      u2(value);
    } else {
      throw new IllegalArgumentException("int constant out of range for direct push: " + value);
    }
    push(1);
  }

  /** {@code ldc} a pool entry that occupies one slot (String, Integer, Float, Class). */
  void loadConstant(final int poolIndex) {
    if (poolIndex <= 0xff) {
      op(0x12); // ldc
      code.write(poolIndex);
    } else {
      op(0x13); // ldc_w
      u2(poolIndex);
    }
    push(1);
  }

  /** {@code ldc2_w} a pool entry that occupies two slots (Long, Double). */
  void loadConstant2(final int poolIndex) {
    op(0x14); // ldc2_w
    u2(poolIndex);
    push(2);
  }

  void loadReference(final int slot) {
    if (slot <= 3) {
      op(0x2a + slot); // aload_<n>
    } else {
      op(0x19); // aload
      code.write(slot);
    }
    push(1);
  }

  void storeReference(final int slot) {
    if (slot <= 3) {
      op(0x4b + slot); // astore_<n>
    } else {
      op(0x3a); // astore
      code.write(slot);
    }
    push(-1);
  }

  void longZero() {
    op(0x09); // lconst_0
    push(2);
  }

  void longOne() {
    op(0x0a); // lconst_1
    push(2);
  }

  void loadLong(final int slot) {
    if (slot <= 3) {
      op(0x1e + slot); // lload_<n>
    } else {
      op(0x16); // lload
      code.write(slot);
    }
    push(2);
  }

  void storeLong(final int slot) {
    if (slot <= 3) {
      op(0x3f + slot); // lstore_<n>
    } else {
      op(0x37); // lstore
      code.write(slot);
    }
    push(-2);
  }

  void addLong() {
    op(0x61); // ladd
    push(-2);
  }

  void compareLong() {
    op(0x94); // lcmp: two longs -> one int
    push(-3);
  }

  void ifGreaterEqual(final int label) {
    branch(0x9c, label); // ifge
  }

  void loadInt(final int slot) {
    if (slot <= 3) {
      op(0x1a + slot); // iload_<n>
    } else {
      op(0x15); // iload
      code.write(slot);
    }
    push(1);
  }

  void storeInt(final int slot) {
    if (slot <= 3) {
      op(0x3b + slot); // istore_<n>
    } else {
      op(0x36); // istore
      code.write(slot);
    }
    push(-1);
  }

  void newReferenceArray(final int classPoolIndex) {
    op(0xbd); // anewarray (consumes count, produces arrayref: net 0)
    u2(classPoolIndex);
  }

  void arrayStoreReference() {
    op(0x53); // aastore
    push(-3);
  }

  void arrayLoadReference() {
    op(0x32); // aaload: arrayref, index -> value
    push(-1);
  }

  void dup() {
    op(0x59);
    push(1);
  }

  void pop() {
    op(0x57);
    push(-1);
  }

  void getStatic(final int fieldPoolIndex, final int producedWords) {
    op(0xb2);
    u2(fieldPoolIndex);
    push(producedWords);
  }

  void invokeStatic(final int methodPoolIndex, final String descriptor) {
    op(0xb8);
    u2(methodPoolIndex);
    push(returnWords(descriptor) - argumentWords(descriptor));
  }

  void invokeVirtual(final int methodPoolIndex, final String descriptor) {
    op(0xb6);
    u2(methodPoolIndex);
    push(returnWords(descriptor) - argumentWords(descriptor) - 1); // -1 for the receiver
  }

  void branch(final int opcode, final int label) {
    op(opcode);
    fixups.add(new Fixup(code.size(), label));
    u2(0); // placeholder, patched in code()
    if (opcode >= 0x99 && opcode <= 0x9e) {
      push(-1); // ifeq/ifne/iflt/ifge/ifgt/ifle each consume one int
    }
  }

  void jump(final int label) {
    branch(0xa7, label); // goto
  }

  void ifEqual(final int label) {
    branch(0x99, label); // ifeq
  }

  void ifNotEqual(final int label) {
    branch(0x9a, label); // ifne
  }

  void returnReference() {
    op(0xb0); // areturn
    push(-1);
  }

  void returnVoid() {
    op(0xb1); // return
  }

  int maxStack() {
    return maxStack;
  }

  int maxLocals() {
    return maxLocals;
  }

  /** Resolve fixups and return the final code array. */
  byte[] code() {
    final var output = code.toByteArray();
    for (final var fixup : fixups) {
      final var target = targets.get(fixup.label);
      if (target == null) {
        throw new IllegalStateException("Unmarked branch label: " + fixup.label);
      }
      final var origin = fixup.position - 1; // branch opcode precedes the operand
      final var offset = target - origin;
      output[fixup.position] = (byte) ((offset >> 8) & 0xff);
      output[fixup.position + 1] = (byte) (offset & 0xff);
    }
    return output;
  }

  private void op(final int opcode) {
    code.write(opcode);
  }

  private void u2(final int value) {
    code.write((value >> 8) & 0xff);
    code.write(value & 0xff);
  }

  private void push(final int delta) {
    stack += delta;
    if (stack > maxStack) {
      maxStack = stack;
    }
    if (stack < 0) {
      throw new IllegalStateException("Operand stack underflow");
    }
  }

  private record Fixup(int position, int label) {}
}
