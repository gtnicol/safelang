package io.safelang.compiler.c;

/**
 * Wraps and unwraps SAFE values for the C tagged-union {@code SAFEValue} representation. The
 * mapping from SAFE type to union field:
 *
 * <pre>
 *   int / boolean / unknown → .int_val
 *   uint                    → .uint_val
 *   float                   → .float_val
 *   string                  → .string_val
 *   bytes / list / map / set / struct → .ptr_val
 * </pre>
 *
 * <p>User-defined structs are heap-allocated on wrap (via {@code safe_heap_struct}) and
 * dereferenced on unwrap. Lists/maps/sets are stored as opaque pointers; the unwrap cast reapplies
 * the SAFE-to-C type translation.
 */
final class CBoxing {

  private final CBoxingContext context;

  CBoxing(final CBoxingContext context) {
    this.context = context;
  }

  /**
   * Build the {@code SAFEValue} initializer that boxes {@code code} interpreted as {@code type}.
   */
  String wrap(final String code, final String type) {
    if (type == null) return "(SAFEValue){.int_val = " + code + "}";
    return switch (type) {
      case "float" -> "(SAFEValue){.float_val = " + code + "}";
      case "string" -> "(SAFEValue){.string_val = " + code + "}";
      case "boolean" -> "(SAFEValue){.bool_val = " + code + "}";
      case "uint" -> "(SAFEValue){.uint_val = " + code + "}";
      case "bytes" -> "(SAFEValue){.ptr_val = " + code + "}";
      default -> wrapDefault(code, type);
    };
  }

  /** Inverse of {@link #wrap}: extract the typed value from the {@code SAFEValue} expression. */
  String unwrap(final String code, final String type) {
    if (type == null) return code + ".int_val";
    return switch (type) {
      case "float" -> code + ".float_val";
      case "string" -> code + ".string_val";
      case "boolean" -> code + ".bool_val";
      case "uint" -> code + ".uint_val";
      case "bytes" -> "(SAFEBytes*)" + code + ".ptr_val";
      default -> unwrapDefault(code, type);
    };
  }

  private String wrapDefault(final String code, final String type) {
    if (type.startsWith("list") || type.startsWith("map") || type.startsWith("set")) {
      return "(SAFEValue){.ptr_val = " + code + "}";
    }
    final var mapped = context.translate(type);
    final var structs = context.structs();
    if (structs.containsKey(type) || structs.containsKey(mapped)) {
      return "(SAFEValue){.ptr_val = safe_heap_struct(&(" + code + "), sizeof(" + mapped + "))}";
    }
    return "(SAFEValue){.int_val = " + code + "}";
  }

  private String unwrapDefault(final String code, final String type) {
    if (type.startsWith("list") || type.startsWith("map") || type.startsWith("set")) {
      return "(" + context.translate(type) + ")" + code + ".ptr_val";
    }
    final var mapped = context.translate(type);
    final var structs = context.structs();
    if (structs.containsKey(type) || structs.containsKey(mapped)) {
      return "(*(" + mapped + "*)" + code + ".ptr_val)";
    }
    return code + ".int_val";
  }
}
