package io.safelang.bytecode;

import io.safelang.runtime.SAFEValue;

/** Represents a single call frame on the VM call stack. */
public class CallFrame {
  private final String name;
  private final int address; // PC to resume after return
  private final int chunk; // which function's bytecode to resume in (-1 = main)
  private final SAFEValue[] locals;

  public CallFrame(final String name, final int address, final int chunk, final int count) {
    this.name = name;
    this.address = address;
    this.chunk = chunk;
    this.locals = new SAFEValue[count];
    // Initialize locals to void
    for (int i = 0; i < count; i++) {
      locals[i] = SAFEValue.ofVoid();
    }
  }

  public String name() {
    return name;
  }

  public int address() {
    return address;
  }

  public int chunk() {
    return chunk;
  }

  public SAFEValue local(final int slot) {
    if (slot < 0 || slot >= locals.length) {
      throw new BytecodeException(
          "Local variable slot out of range: "
              + slot
              + " (function "
              + name
              + " has "
              + locals.length
              + " locals)");
    }
    return locals[slot];
  }

  public void setLocal(final int slot, final SAFEValue value) {
    if (slot < 0 || slot >= locals.length) {
      throw new BytecodeException(
          "Local variable slot out of range: "
              + slot
              + " (function "
              + name
              + " has "
              + locals.length
              + " locals)");
    }
    locals[slot] = value;
  }

  public int locals() {
    return locals.length;
  }
}
