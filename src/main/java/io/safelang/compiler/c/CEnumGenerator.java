package io.safelang.compiler.c;

import io.safelang.ast.EnumDeclarationNode;

/**
 * Emits the C code for a SAFE {@code enum} declaration.
 *
 * <p>Generates a tag enum, an optional union of variant payloads, and a {@code _new} constructor
 * function for each variant. Recursive enums (any variant whose payload references the enum itself)
 * are forward-declared as pointer-typed structs and allocated via the arena allocator;
 * non-recursive enums are emitted as plain inline structs that pass by value.
 *
 * <p>Stateless apart from the injected {@link CEnumContext}.
 */
final class CEnumGenerator {

  private final CEnumContext context;

  CEnumGenerator(final CEnumContext context) {
    this.context = context;
  }

  String generate(final EnumDeclarationNode node) {
    final var builder = new StringBuilder();
    final var name = node.name();

    // Detect recursive enum (any variant field directly references this enum)
    var selfref = false;
    for (final var variant : node.variants()) {
      for (final var field : variant.fields()) {
        if (name.equals(field.name()) || name.equals(field.fullName())) {
          selfref = true;
          break;
        }
      }
      if (selfref) break;
    }
    if (selfref) {
      context.markRecursive(name);
    }

    // Tag enum
    builder.append("typedef enum { ");
    for (int i = 0; i < node.variants().size(); i++) {
      if (i > 0) builder.append(", ");
      builder.append(name).append("_").append(node.variants().get(i).name());
    }
    builder.append(" } ").append(name).append("_Tag;\n");

    // Check if any variant has associated data
    var compound = false;
    for (final var variant : node.variants()) {
      if (variant.hasFields()) {
        compound = true;
        break;
      }
    }

    // For recursive enums, use a named struct with forward declaration
    if (selfref) {
      builder.append("typedef struct ").append(name).append("_s ").append(name).append(";\n");
      builder.append("struct ").append(name).append("_s {\n");
    } else {
      builder.append("typedef struct {\n");
    }
    builder.append("    ").append(name).append("_Tag tag;\n");
    if (compound) {
      builder.append("    union {\n");
      for (final var variant : node.variants()) {
        if (variant.hasFields()) {
          builder.append("        struct { ");
          for (int i = 0; i < variant.fields().size(); i++) {
            final var field = variant.fields().get(i);
            final var kind = field.name() != null ? field.name() : field.fullName();
            if (selfref && (name.equals(kind) || name.equals(field.fullName()))) {
              // Self-referential: use pointer
              builder.append(name).append("* _").append(i).append("; ");
            } else {
              builder
                  .append(context.translate(field.fullName()))
                  .append(" _")
                  .append(i)
                  .append("; ");
            }
          }
          builder.append("} ").append(variant.name()).append(";\n");
        }
      }
      builder.append("    } data;\n");
    }
    if (selfref) {
      builder.append("};\n");
    } else {
      builder.append("} ").append(name).append(";\n");
    }

    // Phase 5.2: recursive enums register a dispose function that walks
    // the active variant and safe_release's each heap-RC field. The type
    // id returned by safe_register_enum is cached in a static and stamped
    // into header.size_class at each variant construction so safe_dispose
    // can dispatch back here.
    if (selfref) {
      builder
          .append("static void ")
          .append(name)
          .append("_dispose(void* body) {\n")
          .append("    ")
          .append(name)
          .append("* v = (")
          .append(name)
          .append("*)body;\n")
          .append("    switch (v->tag) {\n");
      for (final var variant : node.variants()) {
        builder
            .append("        case ")
            .append(name)
            .append("_")
            .append(variant.name())
            .append(":\n");
        if (variant.hasFields()) {
          for (int i = 0; i < variant.fields().size(); i++) {
            final var field = variant.fields().get(i);
            final var fieldType = field.fullName();
            final var isSelf = name.equals(field.name()) || name.equals(fieldType);
            if (isSelf || context.isHeapRc(fieldType)) {
              builder
                  .append("            safe_release(v->data.")
                  .append(variant.name())
                  .append("._")
                  .append(i)
                  .append(");\n");
            }
          }
        }
        builder.append("            break;\n");
      }
      builder
          .append("    }\n")
          .append("}\n")
          .append("static int ")
          .append(name)
          .append("_type_id = 0;\n")
          .append("__attribute__((constructor))\n")
          .append("static void ")
          .append(name)
          .append("_register(void) {\n")
          .append("    ")
          .append(name)
          .append("_type_id = safe_register_enum(")
          .append(name)
          .append("_dispose);\n")
          .append("}\n");
    }

    // Constructor functions for each variant
    for (final var variant : node.variants()) {
      if (selfref) {
        // Return pointer for recursive enums
        builder
            .append("static inline ")
            .append(name)
            .append("* ")
            .append(name)
            .append("_")
            .append(variant.name())
            .append("_new(");
      } else {
        builder
            .append("static inline ")
            .append(name)
            .append(" ")
            .append(name)
            .append("_")
            .append(variant.name())
            .append("_new(");
      }
      if (variant.hasFields()) {
        for (int i = 0; i < variant.fields().size(); i++) {
          if (i > 0) builder.append(", ");
          final var field = variant.fields().get(i);
          final var kind = field.name() != null ? field.name() : field.fullName();
          if (selfref && (name.equals(kind) || name.equals(field.fullName()))) {
            builder.append(name).append("* _").append(i);
          } else {
            builder.append(context.translate(field.fullName())).append(" _").append(i);
          }
        }
      }
      builder.append(") {\n");
      if (selfref) {
        builder
            .append("    ")
            .append(name)
            .append("* v = (")
            .append(name)
            .append("*)safe_alloc(sizeof(")
            .append(name)
            .append("), SAFE_KIND_ENUM, 0);\n")
            .append("    safe_header(v)->size_class = (unsigned char)")
            .append(name)
            .append("_type_id;\n");
        builder
            .append("    v->tag = ")
            .append(name)
            .append("_")
            .append(variant.name())
            .append(";\n");
        if (variant.hasFields()) {
          for (int i = 0; i < variant.fields().size(); i++) {
            builder
                .append("    v->data.")
                .append(variant.name())
                .append("._")
                .append(i)
                .append(" = _")
                .append(i)
                .append(";\n");
          }
        }
        builder.append("    return v;\n");
      } else {
        builder.append("    ").append(name).append(" v;\n");
        builder
            .append("    v.tag = ")
            .append(name)
            .append("_")
            .append(variant.name())
            .append(";\n");
        if (variant.hasFields()) {
          for (int i = 0; i < variant.fields().size(); i++) {
            builder
                .append("    v.data.")
                .append(variant.name())
                .append("._")
                .append(i)
                .append(" = _")
                .append(i)
                .append(";\n");
          }
        }
        builder.append("    return v;\n");
      }
      builder.append("}\n");
    }

    return builder.toString();
  }
}
