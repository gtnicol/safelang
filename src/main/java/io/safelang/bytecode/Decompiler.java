package io.safelang.bytecode;

import io.safelang.runtime.StringEscapes;
import java.util.*;

/** Decompiles a BytecodeModule into human-readable SAFE assembly text. */
public class Decompiler {

  private BytecodeModule module;

  private static int unsigned(final byte[] data, final int pos) {
    return ((data[pos] & 0xFF) << 8) | (data[pos + 1] & 0xFF);
  }

  private static int signed(final byte[] data, final int pos) {
    int value = unsigned(data, pos);
    if (value > 32767) value -= 65536;
    return value;
  }

  private static String escape(final String s) {
    return StringEscapes.assembly(s);
  }

  /** Decompile a BytecodeModule to assembly text. */
  public String decompile(final BytecodeModule module) {
    this.module = module;
    final var builder = new StringBuilder();
    final var pool = module.pool();

    builder.append("; SAFE Bytecode Assembly\n");
    builder.append("; Decompiled output\n\n");
    builder.append(".version ").append(BytecodeModule.VERSION).append("\n\n");

    // Constants section
    builder.append(".constants\n");
    for (int i = 0; i < pool.size(); i++) {
      final var entry = pool.get(i);
      builder.append("  @").append(i).append(" ");
      if (entry instanceof ConstantPool.IntEntry(long value)) {
        builder.append("int ").append(value);
      } else if (entry instanceof ConstantPool.FloatEntry(double value)) {
        builder.append("float ").append(value);
      } else if (entry instanceof ConstantPool.StringEntry(String value)) {
        builder.append("string \"").append(escape(value)).append("\"");
      } else if (entry instanceof ConstantPool.NameEntry(String value)) {
        builder.append("name \"").append(escape(value)).append("\"");
      }
      builder.append("\n");
    }
    builder.append("\n");

    // Types section
    if (!module.types().isEmpty()) {
      builder.append(".types\n");
      for (final var type : module.types()) {
        builder.append("  type ").append(type.name()).append(" {");
        final var fields = type.fields();
        for (int i = 0; i < fields.size(); i++) {
          if (i > 0) builder.append(",");
          builder.append(" ").append(fields.get(i).name()).append(": ");
          builder.append(TypeDefinition.typeNameFromTag(fields.get(i).tag()));
        }
        builder.append(" }\n");
      }
      builder.append("\n");
    }

    // Enums section
    if (!module.enums().isEmpty()) {
      builder.append(".enums\n");
      for (final var entry : module.enums()) {
        builder.append("  enum ").append(entry.name()).append(" {");
        final var variants = entry.variants();
        for (int i = 0; i < variants.size(); i++) {
          if (i > 0) builder.append(",");
          builder.append(" ").append(variants.get(i).name());
          if (variants.get(i).hasFields()) {
            builder.append("(");
            final var tags = variants.get(i).tags();
            for (int j = 0; j < tags.size(); j++) {
              if (j > 0) builder.append(", ");
              builder.append(TypeDefinition.typeNameFromTag(tags.get(j)));
            }
            builder.append(")");
          }
        }
        builder.append(" }\n");
      }
      builder.append("\n");
    }

    // Globals section
    if (!module.globals().isEmpty()) {
      builder.append(".globals\n");
      for (final var global : module.globals()) {
        builder.append("  ").append(global.isConst() ? "const" : "var");
        builder.append(" ").append(global.name()).append("\n");
      }
      builder.append("\n");
    }

    // Functions
    if (!module.functions().isEmpty()) {
      builder.append(".functions\n\n");
      for (final var function : module.functions()) {
        builder.append(".function ").append(function.name());
        builder.append(" params=").append(function.parameters());
        builder.append(" locals=").append(function.locals());
        builder.append("\n");

        if (function.bytecode() != null) {
          disassemble(builder, function.bytecode(), pool, "  ");
        }

        if (function.hasRequires()) {
          builder.append("  .requires\n");
          disassemble(builder, function.requires(), pool, "    ");
        }
        if (function.hasEnsures()) {
          builder.append("  .ensures\n");
          disassemble(builder, function.ensures(), pool, "    ");
        }
        if (function.hasDecreases()) {
          builder.append("  .decreases\n");
          disassemble(builder, function.decreases(), pool, "    ");
        }

        builder.append(".end\n\n");
      }
    }

    // Main bytecode
    builder.append(".main locals=").append(module.locals()).append("\n");
    disassemble(builder, module.main(), pool, "  ");
    builder.append(".end\n");

    return builder.toString();
  }

  private void disassemble(
      final StringBuilder builder,
      final byte[] bytecode,
      final ConstantPool pool,
      final String indent) {
    // First pass: find jump targets for labels
    Set<Integer> targets = new HashSet<>();
    int pos = 0;
    while (pos < bytecode.length) {
      final var opcode = bytecode[pos] & 0xFF;
      OpCode instruction;
      try {
        instruction = OpCode.fromCode(opcode);
      } catch (BytecodeException e) {
        pos++;
        continue;
      }

      switch (instruction) {
        case JUMP:
        case JUMP_FALSE:
        case JUMP_TRUE:
          {
            final var offset = signed(bytecode, pos + 1);
            final var target = pos + 1 + 2 + offset; // after opcode + operand + offset
            targets.add(target);
            break;
          }
        case MATCH_ENUM:
          {
            final var offset = signed(bytecode, pos + 3);
            final var target = pos + 1 + 4 + offset;
            targets.add(target);
            break;
          }
        case ITER_NEXT:
          {
            final var offset = signed(bytecode, pos + 3);
            final var target = pos + 1 + 4 + offset;
            targets.add(target);
            break;
          }
        default:
          break;
      }

      pos += instruction.getInstructionSize();
    }

    // Second pass: disassemble with labels
    pos = 0;
    while (pos < bytecode.length) {
      // Emit label if this is a jump target
      if (targets.contains(pos)) {
        builder.append(indent, 0, Math.max(0, indent.length() - 2));
        builder.append("L").append(pos).append(":\n");
      }

      final var opcode = bytecode[pos] & 0xFF;
      OpCode instruction;
      try {
        instruction = OpCode.fromCode(opcode);
      } catch (BytecodeException e) {
        builder.append(indent).append(String.format("??? 0x%02x\n", opcode));
        pos++;
        continue;
      }

      builder.append(indent).append(instruction.mnemonic());

      final var start = pos;
      pos++; // skip opcode

      switch (instruction) {
        case CONST_INT:
          {
            final var index = unsigned(bytecode, pos);
            pos += 2;
            builder.append(" @").append(index);
            builder.append("  ; ").append(pool.getInt(index));
            break;
          }
        case CONST_UINT:
          {
            final var index = unsigned(bytecode, pos);
            pos += 2;
            builder.append(" @").append(index);
            builder.append("  ; ").append(pool.getInt(index)).append("u");
            break;
          }
        case CONST_FLOAT:
          {
            final var index = unsigned(bytecode, pos);
            pos += 2;
            builder.append(" @").append(index);
            builder.append("  ; ").append(pool.getFloat(index));
            break;
          }
        case CONST_STR:
          {
            final var index = unsigned(bytecode, pos);
            pos += 2;
            builder.append(" @").append(index);
            builder.append("  ; \"").append(escape(pool.getString(index))).append("\"");
            break;
          }
        case LOAD_LOCAL:
        case STORE_LOCAL:
          {
            final var slot = unsigned(bytecode, pos);
            pos += 2;
            builder.append(" ").append(slot);
            break;
          }
        case LOAD_GLOBAL:
        case STORE_GLOBAL:
          {
            final var index = unsigned(bytecode, pos);
            pos += 2;
            builder.append(" @").append(index);
            builder.append("  ; ").append(pool.getString(index));
            break;
          }
        case JUMP:
        case JUMP_FALSE:
        case JUMP_TRUE:
          {
            final var offset = signed(bytecode, pos);
            pos += 2;
            final var target = start + 1 + 2 + offset;
            builder.append(" L").append(target);
            break;
          }
        case CALL:
          {
            final var index = unsigned(bytecode, pos);
            pos += 2;
            final var arity = bytecode[pos++] & 0xFF;
            builder.append(" ").append(index).append(" ").append(arity);
            final var name = module.pool().getString(index);
            if (name != null) {
              builder.append("  ; ").append(name);
            }
            break;
          }
        case NEW_OBJECT:
          {
            final var type = unsigned(bytecode, pos);
            pos += 2;
            final var count = bytecode[pos++] & 0xFF;
            builder.append(" ").append(type).append(" ").append(count);
            if (type < module.types().size()) {
              builder.append("  ; ").append(module.type(type).name());
            }
            break;
          }
        case GET_FIELD:
        case SET_FIELD:
          {
            final var index = unsigned(bytecode, pos);
            pos += 2;
            builder.append(" @").append(index);
            builder.append("  ; ").append(pool.getString(index));
            break;
          }
        case NEW_LIST:
        case NEW_MAP:
          {
            final var count = unsigned(bytecode, pos);
            pos += 2;
            builder.append(" ").append(count);
            break;
          }
        case NEW_ENUM:
          {
            final var index = unsigned(bytecode, pos);
            pos += 2;
            final var variant = unsigned(bytecode, pos);
            pos += 2;
            final var count = bytecode[pos++] & 0xFF;
            builder.append(" ").append(index).append(" ").append(variant).append(" ").append(count);
            break;
          }
        case MATCH_ENUM:
          {
            final var variant = unsigned(bytecode, pos);
            pos += 2;
            final var offset = signed(bytecode, pos);
            pos += 2;
            final var target = start + 1 + 4 + offset;
            builder.append(" ").append(variant).append(" L").append(target);
            break;
          }
        case ENUM_DATA:
          {
            final var index = bytecode[pos++] & 0xFF;
            builder.append(" ").append(index);
            break;
          }
        case ASSERT:
          {
            final var index = unsigned(bytecode, pos);
            pos += 2;
            builder.append(" @").append(index);
            builder.append("  ; \"").append(escape(pool.getString(index))).append("\"");
            break;
          }
        case ASSERT_EXPR:
          break;
        case ITER_INIT:
          {
            final var slot = unsigned(bytecode, pos);
            pos += 2;
            builder.append(" ").append(slot);
            break;
          }
        case ITER_NEXT:
          {
            final var slot = unsigned(bytecode, pos);
            pos += 2;
            final var offset = signed(bytecode, pos);
            pos += 2;
            final var target = start + 1 + 4 + offset;
            builder.append(" ").append(slot).append(" L").append(target);
            break;
          }
        case NEW_TUPLE:
        case NEW_SET:
          {
            final var count = unsigned(bytecode, pos);
            pos += 2;
            builder.append(" ").append(count);
            break;
          }
        case CLOSURE:
          {
            final var index = unsigned(bytecode, pos);
            pos += 2;
            final var captures = bytecode[pos++] & 0xFF;
            builder.append(" ").append(index).append(" ").append(captures);
            if (index < module.functions().size()) {
              builder.append("  ; ").append(module.function(index).name());
            }
            break;
          }
        case CALL_VALUE:
          {
            final var arity = bytecode[pos++] & 0xFF;
            builder.append(" ").append(arity);
            break;
          }
        case TAIL_CALL:
          {
            final var index = unsigned(bytecode, pos);
            pos += 2;
            final var arity = bytecode[pos++] & 0xFF;
            builder.append(" ").append(index).append(" ").append(arity);
            final var name = module.pool().getString(index);
            if (name != null) {
              builder.append("  ; ").append(name);
            }
            break;
          }
        default:
          // No operands — pos already advanced past opcode
          break;
      }

      builder.append("\n");
    }
  }
}
