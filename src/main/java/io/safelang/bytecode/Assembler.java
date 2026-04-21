package io.safelang.bytecode;

import io.safelang.runtime.StringEscapes;
import java.util.*;
import java.util.regex.*;

/**
 * Assembles SAFE bytecode assembly text (.safea) into a BytecodeModule. Two-pass: (1) collect
 * labels, (2) emit bytecode with resolved labels.
 */
public class Assembler {

  private BytecodeModule module;
  private ConstantPool pool;
  private Map<String, Integer> labels; // label -> bytecode offset
  private List<LabelPatch> patches; // unresolved label references

  /** Find the position of a ';' comment marker, skipping semicolons inside quoted strings. */
  private static int comment(final String line) {
    var quoted = false;
    for (int i = 0; i < line.length(); i++) {
      final var c = line.charAt(i);
      if (c == '"' && (i == 0 || line.charAt(i - 1) != '\\')) {
        quoted = !quoted;
      } else if (c == ';' && !quoted) {
        return i;
      }
    }
    return -1;
  }

  /** Assemble text into a BytecodeModule. */
  public BytecodeModule assemble(final String text) {
    module = new BytecodeModule();
    pool = module.pool();
    labels = new HashMap<>();
    patches = new ArrayList<>();

    final var lines = text.split("\n");
    int i = 0;
    String section = null;
    String name = null;
    int params = 0;
    int locals = 0;
    BytecodeChunk chunk = null;
    // For contract support in functions
    BytecodeChunk bodyChunk = null;
    BytecodeChunk requiresChunk = null;
    BytecodeChunk ensuresChunk = null;
    BytecodeChunk decreasesChunk = null;
    Map<String, Integer> bodyLabels = null;
    List<LabelPatch> bodyPatches = null;
    Map<String, Integer> requiresLabels = null;
    List<LabelPatch> requiresPatches = null;
    Map<String, Integer> ensuresLabels = null;
    List<LabelPatch> ensuresPatches = null;
    Map<String, Integer> decreasesLabels = null;
    List<LabelPatch> decreasesPatches = null;
    String subsection = null; // null, "requires", "ensures", or "decreases"

    while (i < lines.length) {
      String line = lines[i].trim();
      i++;

      // Skip empty lines and comments
      if (line.isEmpty() || line.startsWith(";")) continue;

      // Strip inline comments (respecting quoted strings)
      final var comment = comment(line);
      if (comment >= 0) line = line.substring(0, comment).trim();

      if (line.startsWith(".version")) {
        continue; // skip version directive
      } else if (line.equals(".constants")) {
        section = "constants";
        continue;
      } else if (line.equals(".types")) {
        section = "types";
        continue;
      } else if (line.equals(".enums")) {
        section = "enums";
        continue;
      } else if (line.equals(".globals")) {
        section = "globals";
        continue;
      } else if (line.equals(".functions")) {
        section = "functions";
        continue;
      } else if (line.startsWith(".function ")) {
        section = "function_body";
        subsection = null;
        // Parse function header: .function name params=N locals=N
        final var rest = line.substring(10).trim();
        final var parts = rest.split("\\s+");
        name = parts[0];
        params = 0;
        locals = 0;
        for (int j = 1; j < parts.length; j++) {
          if (parts[j].startsWith("params=")) {
            params = Integer.parseInt(parts[j].substring(7));
          } else if (parts[j].startsWith("locals=")) {
            locals = Integer.parseInt(parts[j].substring(7));
          }
        }
        bodyChunk = new BytecodeChunk();
        chunk = bodyChunk;
        requiresChunk = null;
        ensuresChunk = null;
        decreasesChunk = null;
        labels = new HashMap<>();
        patches = new ArrayList<>();
        bodyLabels = labels;
        bodyPatches = patches;
        requiresLabels = null;
        requiresPatches = null;
        ensuresLabels = null;
        ensuresPatches = null;
        decreasesLabels = null;
        decreasesPatches = null;
        continue;
      } else if (line.equals(".requires") && "function_body".equals(section)) {
        // Save current body labels/patches, switch to requires chunk
        subsection = "requires";
        requiresChunk = new BytecodeChunk();
        chunk = requiresChunk;
        requiresLabels = new HashMap<>();
        requiresPatches = new ArrayList<>();
        labels = requiresLabels;
        patches = requiresPatches;
        continue;
      } else if (line.equals(".ensures") && "function_body".equals(section)) {
        subsection = "ensures";
        ensuresChunk = new BytecodeChunk();
        chunk = ensuresChunk;
        ensuresLabels = new HashMap<>();
        ensuresPatches = new ArrayList<>();
        labels = ensuresLabels;
        patches = ensuresPatches;
        continue;
      } else if (line.equals(".decreases") && "function_body".equals(section)) {
        subsection = "decreases";
        decreasesChunk = new BytecodeChunk();
        chunk = decreasesChunk;
        decreasesLabels = new HashMap<>();
        decreasesPatches = new ArrayList<>();
        labels = decreasesLabels;
        patches = decreasesPatches;
        continue;
      } else if (line.startsWith(".main")) {
        section = "main";
        chunk = new BytecodeChunk();
        labels = new HashMap<>();
        patches = new ArrayList<>();
        // Parse optional locals=N
        final var tokens = line.split("\\s+");
        for (int j = 1; j < tokens.length; j++) {
          if (tokens[j].startsWith("locals=")) {
            module.setLocals(Integer.parseInt(tokens[j].substring(7)));
          }
        }
        continue;
      } else if (line.equals(".end")) {
        if ("function_body".equals(section)) {
          // Resolve patches for body
          labels = bodyLabels;
          patches = bodyPatches;
          if (bodyChunk != null) {
            resolve(bodyChunk);
          }
          final var index = pool.addName(name);
          final var body = bodyChunk != null ? bodyChunk.bytes() : new byte[0];
          byte[] requiresBytes = null;
          if (requiresChunk != null) {
            labels = requiresLabels;
            patches = requiresPatches;
            resolve(requiresChunk);
            requiresBytes = requiresChunk.bytes();
          }
          byte[] ensuresBytes = null;
          if (ensuresChunk != null) {
            labels = ensuresLabels;
            patches = ensuresPatches;
            resolve(ensuresChunk);
            ensuresBytes = ensuresChunk.bytes();
          }
          byte[] decreasesBytes = null;
          if (decreasesChunk != null) {
            labels = decreasesLabels;
            patches = decreasesPatches;
            resolve(decreasesChunk);
            decreasesBytes = decreasesChunk.bytes();
          }
          module.add(
              new FunctionDefinition(
                  name, index, params, locals, body, requiresBytes, ensuresBytes, decreasesBytes));
        } else if ("main".equals(section) && chunk != null) {
          resolve(chunk);
          module.setMain(chunk.bytes());
        }
        section = null;
        chunk = null;
        subsection = null;
        continue;
      }

      // Process line based on section
      if ("constants".equals(section)) {
        constant(line);
      } else if ("types".equals(section)) {
        type(line);
      } else if ("enums".equals(section)) {
        enumeration(line);
      } else if ("globals".equals(section)) {
        global(line);
      } else if ("function_body".equals(section) || "main".equals(section)) {
        if (chunk != null) {
          instruction(line, chunk);
        }
      }
    }

    return module;
  }

  private void constant(final String line) {
    // @N type value
    final var m = Pattern.compile("@(\\d+)\\s+(int|float|string|name)\\s+(.+)").matcher(line);
    if (!m.matches()) return;

    final var index = Integer.parseInt(m.group(1));
    final var expected = pool.size();
    if (index != expected) {
      throw new BytecodeException(
          "Constant pool index mismatch: declared @" + index + " but expected @" + expected);
    }

    final var type = m.group(2);
    final var value = m.group(3);

    switch (type) {
      case "int":
        pool.addInt(Long.parseLong(value));
        break;
      case "float":
        pool.addFloat(Double.parseDouble(value));
        break;
      case "string":
        pool.addString(unescape(value));
        break;
      case "name":
        pool.addName(unescape(value));
        break;
      default:
        throw new BytecodeException("Unknown constant pool type: " + type);
    }
  }

  private void type(final String line) {
    // type Name { field1: type1, field2: type2 }
    final var m = Pattern.compile("type\\s+(\\w+)\\s*\\{(.*)\\}").matcher(line);
    if (!m.matches()) return;

    final var name = m.group(1);
    final var index = pool.addName(name);
    final var fields = new ArrayList<TypeDefinition.FieldInfo>();
    final var body = m.group(2).trim();
    if (!body.isEmpty()) {
      for (final var entry : body.split(",")) {
        final var parts = entry.trim().split(":");
        if (parts.length == 2) {
          final var field = parts[0].trim();
          final var type = parts[1].trim();
          final var slot = pool.addName(field);
          fields.add(
              new TypeDefinition.FieldInfo(field, slot, TypeDefinition.typeTagFromName(type)));
        }
      }
    }
    module.add(new TypeDefinition(name, index, fields));
  }

  private void enumeration(final String line) {
    // enum Name { Variant1, Variant2(type), ... }
    final var m = Pattern.compile("enum\\s+(\\w+)\\s*\\{(.*)\\}").matcher(line);
    if (!m.matches()) return;

    final var name = m.group(1);
    final var index = pool.addName(name);
    final var variants = new ArrayList<EnumInfo.VariantInfo>();
    final var body = m.group(2).trim();
    // Split variants carefully — commas inside parentheses belong to field lists
    final var parts = split(body);
    for (var entry : parts) {
      entry = entry.trim();
      final var matcher = Pattern.compile("(\\w+)(?:\\((.+)\\))?").matcher(entry);
      if (matcher.matches()) {
        final var variant = matcher.group(1);
        final var slot = pool.addName(variant);
        final var tags = new ArrayList<Integer>();
        if (matcher.group(2) != null) {
          for (final var tag : matcher.group(2).split(",")) {
            tags.add(TypeDefinition.typeTagFromName(tag.trim()));
          }
        }
        variants.add(new EnumInfo.VariantInfo(variant, slot, tags));
      }
    }
    module.add(new EnumInfo(name, index, variants));
  }

  private List<String> split(final String input) {
    final var result = new ArrayList<String>();
    int depth = 0;
    int start = 0;
    for (int i = 0; i < input.length(); i++) {
      final var c = input.charAt(i);
      if (c == '(') depth++;
      else if (c == ')') depth--;
      else if (c == ',' && depth == 0) {
        result.add(input.substring(start, i));
        start = i + 1;
      }
    }
    result.add(input.substring(start));
    return result;
  }

  private void global(final String line) {
    // var name or const name
    final var parts = line.trim().split("\\s+");
    if (parts.length >= 2) {
      final var constant = parts[0].equals("const");
      final var name = parts[1];
      final var index = pool.addName(name);
      module.add(new BytecodeModule.GlobalVarInfo(name, index, constant));
    }
  }

  private void instruction(final String line, final BytecodeChunk chunk) {
    // Check for label
    if (line.endsWith(":")) {
      final var label = line.substring(0, line.length() - 1);
      labels.put(label, chunk.position());
      return;
    }

    final var parts = line.split("\\s+");
    final var mnemonic = parts[0];

    OpCode opcode;
    try {
      opcode = OpCode.fromMnemonic(mnemonic);
    } catch (BytecodeException e) {
      throw new BytecodeException("Unknown instruction: " + mnemonic);
    }

    chunk.emitOpcode(opcode);

    switch (opcode) {
      case CONST_INT:
      case CONST_UINT:
      case CONST_FLOAT:
      case CONST_STR:
      case LOAD_GLOBAL:
      case STORE_GLOBAL:
      case GET_FIELD:
      case SET_FIELD:
      case ASSERT:
        {
          // Pool index: @N
          final var index = pool(parts[1]);
          chunk.emitShort(index);
          break;
        }
      case LOAD_LOCAL:
      case STORE_LOCAL:
      case ITER_INIT:
        {
          final var slot = Integer.parseInt(parts[1]);
          chunk.emitShort(slot);
          break;
        }
      case NEW_LIST:
      case NEW_MAP:
        {
          final var count = Integer.parseInt(parts[1]);
          chunk.emitShort(count);
          break;
        }
      case JUMP:
      case JUMP_FALSE:
      case JUMP_TRUE:
        {
          final var target = parts[1];
          if (target.startsWith("L")) {
            // Label reference — patch later
            final var position = chunk.position();
            patches.add(new LabelPatch(position, target));
            chunk.emitShort(0); // placeholder
          } else {
            chunk.emitShort(Integer.parseInt(target));
          }
          break;
        }
      case CALL:
        {
          final var index = Integer.parseInt(parts[1]);
          final var arity = Integer.parseInt(parts[2]);
          chunk.emitShort(index);
          chunk.emitByte(arity);
          break;
        }
      case NEW_OBJECT:
        {
          final var type = Integer.parseInt(parts[1]);
          final var count = Integer.parseInt(parts[2]);
          chunk.emitShort(type);
          chunk.emitByte(count);
          break;
        }
      case NEW_ENUM:
        {
          final var index = Integer.parseInt(parts[1]);
          final var variant = Integer.parseInt(parts[2]);
          final var count = Integer.parseInt(parts[3]);
          chunk.emitShort(index);
          chunk.emitShort(variant);
          chunk.emitByte(count);
          break;
        }
      case MATCH_ENUM:
        {
          final var variant = Integer.parseInt(parts[1]);
          chunk.emitShort(variant);
          final var target = parts[2];
          if (target.startsWith("L")) {
            final var position = chunk.position();
            patches.add(new LabelPatch(position, target));
            chunk.emitShort(0);
          } else {
            chunk.emitShort(Integer.parseInt(target));
          }
          break;
        }
      case ENUM_DATA:
        {
          final var index = Integer.parseInt(parts[1]);
          chunk.emitByte(index);
          break;
        }
      case ITER_NEXT:
        {
          final var slot = Integer.parseInt(parts[1]);
          chunk.emitShort(slot);
          final var target = parts[2];
          if (target.startsWith("L")) {
            final var position = chunk.position();
            patches.add(new LabelPatch(position, target));
            chunk.emitShort(0);
          } else {
            chunk.emitShort(Integer.parseInt(target));
          }
          break;
        }
      case NEW_TUPLE:
      case NEW_SET:
        {
          final var count = Integer.parseInt(parts[1]);
          chunk.emitShort(count);
          break;
        }
      case CLOSURE:
        {
          final var index = Integer.parseInt(parts[1]);
          final var captures = Integer.parseInt(parts[2]);
          chunk.emitShort(index);
          chunk.emitByte(captures);
          break;
        }
      case CALL_VALUE:
        {
          final var arity = Integer.parseInt(parts[1]);
          chunk.emitByte(arity);
          break;
        }
      case TAIL_CALL:
        {
          final var index = Integer.parseInt(parts[1]);
          final var arity = Integer.parseInt(parts[2]);
          chunk.emitShort(index);
          chunk.emitByte(arity);
          break;
        }
      default:
        // No operands (includes BIT_AND, BIT_OR, BIT_XOR, BIT_NOT, BIT_SHL, BIT_SHR, NEW_RANGE,
        // NEW_RANGE_STEP, etc.)
        break;
    }
  }

  private void resolve(final BytecodeChunk chunk) {
    for (final var patch : patches) {
      final var target = labels.get(patch.label);
      if (target == null) {
        throw new BytecodeException("Unresolved label: " + patch.label);
      }
      // Calculate relative offset: target - (patch.position + 2)
      // The offset is relative to the byte after the 2-byte operand
      final var relative = target - (patch.position + 2);
      if (relative < Short.MIN_VALUE || relative > Short.MAX_VALUE) {
        throw new BytecodeException(
            "Jump offset out of 16-bit range for label '" + patch.label + "': " + relative);
      }
      chunk.patch(patch.position, relative & 0xFFFF);
    }
  }

  private int pool(final String s) {
    if (s.startsWith("@")) {
      return Integer.parseInt(s.substring(1));
    }
    return Integer.parseInt(s);
  }

  private String unescape(final String s) {
    return StringEscapes.unassembly(s);
  }

  private record LabelPatch(int position, String label) {}
}
