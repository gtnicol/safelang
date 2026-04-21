package io.safelang.bytecode;

import java.util.*;

/** Metadata for a user-defined struct type. */
public record TypeDefinition(String name, int index, List<FieldInfo> fields) {
  /** Type tag constants */
  public static final int TYPE_INT = 0;

  public static final int TYPE_FLOAT = 1;
  public static final int TYPE_UINT = 2;
  public static final int TYPE_STRING = 3;
  public static final int TYPE_BOOL = 4;
  public static final int TYPE_LIST = 5;
  public static final int TYPE_MAP = 6;
  public static final int TYPE_OBJECT = 7;
  public static final int TYPE_ENUM = 8;
  public static final int TYPE_VOID = 9;

  public TypeDefinition {
    fields = List.copyOf(fields);
  }

  public static int typeTagFromName(final String type) {
    return switch (type) {
      case "int" -> TYPE_INT;
      case "float" -> TYPE_FLOAT;
      case "uint" -> TYPE_UINT;
      case "string" -> TYPE_STRING;
      case "boolean" -> TYPE_BOOL;
      case "list" -> TYPE_LIST;
      case "map" -> TYPE_MAP;
      case "void" -> TYPE_VOID;
      default -> TYPE_OBJECT;
    };
  }

  public static String typeNameFromTag(final int tag) {
    return switch (tag) {
      case TYPE_INT -> "int";
      case TYPE_FLOAT -> "float";
      case TYPE_UINT -> "uint";
      case TYPE_STRING -> "string";
      case TYPE_BOOL -> "boolean";
      case TYPE_LIST -> "list";
      case TYPE_MAP -> "map";
      case TYPE_OBJECT -> "object";
      case TYPE_ENUM -> "enum";
      case TYPE_VOID -> "void";
      default -> "unknown";
    };
  }

  public record FieldInfo(String name, int index, int tag) {}
}
