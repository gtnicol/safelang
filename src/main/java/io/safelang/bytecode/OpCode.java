package io.safelang.bytecode;

import java.util.HashMap;
import java.util.Map;

/**
 * Opcodes for the SAFE bytecode virtual machine. Each opcode has a name, numeric value, and operand
 * size specification.
 */
public enum OpCode {
  // Constants
  CONST_INT(0x01, "const_int", 2), // pool_idx:2
  CONST_FLOAT(0x02, "const_float", 2), // pool_idx:2
  CONST_STR(0x03, "const_str", 2), // pool_idx:2
  PUSH_TRUE(0x04, "push_true", 0),
  PUSH_FALSE(0x05, "push_false", 0),
  PUSH_VOID(0x06, "push_void", 0),
  CONST_UINT(0x0A, "const_uint", 2), // pool_idx:2

  // Stack
  POP(0x07, "pop", 0),
  DUP(0x08, "dup", 0),
  NOP(0x09, "nop", 0),

  // Variables
  LOAD_LOCAL(0x10, "load_local", 2), // slot:2
  STORE_LOCAL(0x11, "store_local", 2), // slot:2
  LOAD_GLOBAL(0x12, "load_global", 2), // name_idx:2
  STORE_GLOBAL(0x13, "store_global", 2), // name_idx:2

  // Arithmetic
  ADD(0x20, "add", 0),
  SUB(0x21, "sub", 0),
  MUL(0x22, "mul", 0),
  DIV(0x23, "div", 0),
  MOD(0x24, "mod", 0),
  NEG(0x25, "neg", 0),

  // Comparison
  CMP_EQ(0x30, "cmp_eq", 0),
  CMP_NE(0x31, "cmp_ne", 0),
  CMP_LT(0x32, "cmp_lt", 0),
  CMP_LE(0x33, "cmp_le", 0),
  CMP_GT(0x34, "cmp_gt", 0),
  CMP_GE(0x35, "cmp_ge", 0),

  // Logic
  NOT(0x38, "not", 0),

  // Control flow
  JUMP(0x40, "jump", 2), // offset:2 (signed)
  JUMP_FALSE(0x41, "jump_false", 2), // offset:2 (signed)
  JUMP_TRUE(0x42, "jump_true", 2), // offset:2 (signed)
  RETURN(0x43, "return", 0),
  HALT(0x44, "halt", 0),

  // Functions
  CALL(0x50, "call", 3), // name_idx:2, argc:1

  // Objects
  NEW_OBJECT(0x60, "new_object", 3), // type_idx:2, field_count:1
  GET_FIELD(0x61, "get_field", 2), // name_idx:2
  SET_FIELD(0x62, "set_field", 2), // name_idx:2

  // Collections
  NEW_LIST(0x70, "new_list", 2), // count:2
  NEW_MAP(0x71, "new_map", 2), // count:2
  GET_INDEX(0x72, "get_index", 0),
  SET_INDEX(0x73, "set_index", 0),

  // Enums
  NEW_ENUM(0x80, "new_enum", 5), // type_idx:2, variant_idx:2, data_count:1
  MATCH_ENUM(0x81, "match_enum", 4), // variant_idx:2, jump_offset:2
  ENUM_DATA(0x82, "enum_data", 1), // field_idx:1

  // Assert
  ASSERT(0x90, "assert", 2), // msg_idx:2
  ASSERT_EXPR(0x91, "assert_expr", 0), // condition + message on stack

  // Iteration
  ITER_INIT(0xA0, "iter_init", 2), // slot:2
  ITER_NEXT(0xA1, "iter_next", 4), // var_slot:2, end_offset:2

  // In operator
  IN_CHECK(0xB0, "in_check", 0), // Check membership (element, container on stack)

  // Range
  NEW_RANGE(0xB1, "new_range", 0), // Pops end, start; pushes list<int>

  // Tuple
  NEW_TUPLE(0xB2, "new_tuple", 2), // count:2, pops N elements, pushes tuple

  // Set
  NEW_SET(0xB3, "new_set", 2), // count:2, pops N elements, pushes set

  // Higher-order functions
  CLOSURE(0xB4, "closure", 3), // func_idx:2, capture_count:1, pops captures from stack
  CALL_VALUE(0xB5, "call_value", 1), // argc:1, pops function value + args, calls it

  // Bitwise
  BIT_AND(0xB7, "bit_and", 0),
  BIT_OR(0xB8, "bit_or", 0),
  BIT_XOR(0xB9, "bit_xor", 0),
  BIT_NOT(0xBA, "bit_not", 0),
  BIT_SHL(0xBB, "bit_shl", 0),
  BIT_SHR(0xBC, "bit_shr", 0),

  // Range with step
  NEW_RANGE_STEP(0xBD, "new_range_step", 0), // Pops step, end, start; pushes list<int>

  // Tail call optimization
  TAIL_CALL(0xBE, "tail_call", 3); // func_idx:2, argc:1

  private static final OpCode[] BY_CODE = new OpCode[256];
  private static final Map<String, OpCode> BY_MNEMONIC = new HashMap<>();

  static {
    for (final var op : values()) {
      BY_CODE[op.code & 0xFF] = op;
      BY_MNEMONIC.put(op.mnemonic, op);
    }
  }

  private final int code;
  private final String mnemonic;
  private final int operands;

  OpCode(final int code, final String mnemonic, final int operands) {
    this.code = code;
    this.mnemonic = mnemonic;
    this.operands = operands;
  }

  /** Lookup OpCode by its numeric code value */
  public static OpCode fromCode(final int value) {
    final var op = (value >= 0 && value < 256) ? BY_CODE[value] : null;
    if (op == null) throw new BytecodeException("Unknown opcode: 0x" + Integer.toHexString(value));
    return op;
  }

  /** Lookup OpCode by its mnemonic name */
  public static OpCode fromMnemonic(final String value) {
    final var op = BY_MNEMONIC.get(value);
    if (op == null) throw new BytecodeException("Unknown mnemonic: " + value);
    return op;
  }

  public int code() {
    return code;
  }

  public String mnemonic() {
    return mnemonic;
  }

  public int operands() {
    return operands;
  }

  /** Total size of this instruction in bytes (1 for opcode + operand size) */
  public int getInstructionSize() {
    return 1 + operands;
  }
}
