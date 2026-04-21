package io.safelang.compiler.c;

import io.safelang.ast.*;
import io.safelang.compiler.CompilerException;
import io.safelang.runtime.SAFEValue;

/**
 * Emits the C code for SAFE collection literals: tuples, sets, lists, and maps. All four
 * constructions wrap GCC statement expressions ({@code ({ ... })}) around the runtime constructor
 * calls and per-element insertions, with type-aware boxing for the underlying {@code SAFEList*} /
 * {@code SAFEMap*} / {@code SAFESet*} / {@code SAFETuple} types.
 *
 * <p>Stateless apart from the injected {@link CCollectionContext}.
 */
final class CCollectionEmitter {

  private final CCollectionContext context;

  CCollectionEmitter(final CCollectionContext context) {
    this.context = context;
  }

  String tuple(final TupleLiteralNode node) {
    if (node.elements().size() > SAFEValue.MAX_TUPLE_SIZE) {
      throw new CompilerException(
          "Tuple size "
              + node.elements().size()
              + " exceeds maximum of "
              + SAFEValue.MAX_TUPLE_SIZE,
          node.line(),
          node.column());
    }
    final var builder = new StringBuilder("(SAFETuple){.count=");
    builder.append(node.elements().size()).append(", .elements={");
    for (int i = 0; i < node.elements().size(); i++) {
      if (i > 0) builder.append(", ");
      final var element = node.elements().get(i);
      final var code = context.emit(element);
      final var type = context.infer(element);
      // Phase 7: retain heap-typed fields so a boxed tuple owns its refs;
      // the dispose bitmap (set at safe_alloc site) will release on free.
      final var wrapped = "0".equals(context.safeKindOf(type)) ? code : "safe_retain(" + code + ")";
      builder.append(context.wrap(wrapped, type));
    }
    builder.append("}}");
    return builder.toString();
  }

  /**
   * Compute the heap-field bitmap for a tuple literal (bit N set iff element N's type is
   * heap-refcounted). Used to initialise SAFEHeader.meta when boxing the tuple, so safe_dispose can
   * release the right fields.
   */
  int tupleMeta(final TupleLiteralNode node) {
    int bits = 0;
    final var count = Math.min(node.elements().size(), 8);
    for (int i = 0; i < count; i++) {
      final var t = context.infer(node.elements().get(i));
      if (!"0".equals(context.safeKindOf(t))) bits |= (1 << i);
    }
    return bits;
  }

  String set(final SetLiteralNode node) {
    final var elements = node.elements();
    final var builder = new StringBuilder("({\n");
    context.indentInc();
    context.indent(builder);
    final var elementKind =
        elements.isEmpty() ? "0" : context.safeKindOf(context.infer(elements.getFirst()));
    if ("0".equals(elementKind)) {
      builder.append("SAFESet* __set__ = safe_set_new();\n");
    } else {
      builder.append("SAFESet* __set__ = safe_set_new_typed(").append(elementKind).append(");\n");
    }
    final var tagValue = elements.isEmpty() ? 0 : tagFor(elements.getFirst());
    context.indent(builder);
    builder.append("__set__->tag = ").append(tagValue).append(";\n");
    for (final var element : elements) {
      context.indent(builder);
      final var code = context.emit(element);
      final var type = context.infer(element);
      builder.append("safe_set_add_mut(__set__, ").append(context.wrap(code, type)).append(");\n");
    }
    context.indent(builder);
    builder.append("__set__;\n");
    context.indentDec();
    context.indent(builder);
    builder.append("})");
    return builder.toString();
  }

  String list(final ListLiteralNode node) {
    final var builder = new StringBuilder();
    builder.append("({\n");

    context.indentInc();
    context.indent(builder);
    // Phase 6: when we can infer the element kind (from the first element's
    // type), emit a typed list so retain-on-insert and dispose-with-children
    // fire. Empty literals fall back to untyped — a following variable
    // declaration at known type may upgrade.
    final var elementKind =
        node.elements().isEmpty()
            ? "0"
            : context.safeKindOf(context.infer(node.elements().getFirst()));
    if ("0".equals(elementKind)) {
      builder.append("SAFEList* __list__ = safe_list_new();\n");
    } else {
      builder
          .append("SAFEList* __list__ = safe_list_new_typed(")
          .append(elementKind)
          .append(");\n");
    }

    for (final ASTNode elem : node.elements()) {
      context.indent(builder);
      final var code = context.emit(elem);

      if (elem instanceof LiteralNode lit) {
        if (lit instanceof LiteralNode.IntLiteral || lit instanceof LiteralNode.UintLiteral) {
          builder.append("{\n");
          context.indentInc();
          context.indent(builder);
          builder.append("int64_t* __val__ = (int64_t*)safe_arena_alloc(sizeof(int64_t));\n");
          context.indent(builder);
          builder.append("*__val__ = ").append(code).append(";\n");
          context.indent(builder);
          builder.append("safe_list_append(__list__, __val__);\n");
          context.indentDec();
          context.indent(builder);
          builder.append("}\n");
        } else if (lit instanceof LiteralNode.FloatLiteral) {
          builder.append("{\n");
          context.indentInc();
          context.indent(builder);
          builder.append("double* __val__ = (double*)safe_arena_alloc(sizeof(double));\n");
          context.indent(builder);
          builder.append("*__val__ = ").append(code).append(";\n");
          context.indent(builder);
          builder.append("safe_list_append(__list__, __val__);\n");
          context.indentDec();
          context.indent(builder);
          builder.append("}\n");
        } else if (lit instanceof LiteralNode.BoolLiteral) {
          builder.append("{\n");
          context.indentInc();
          context.indent(builder);
          builder.append("int64_t* __val__ = (int64_t*)safe_arena_alloc(sizeof(int64_t));\n");
          context.indent(builder);
          builder.append("*__val__ = (int64_t)").append(code).append(";\n");
          context.indent(builder);
          builder.append("safe_list_append(__list__, __val__);\n");
          context.indentDec();
          context.indent(builder);
          builder.append("}\n");
        } else {
          builder.append("safe_list_append(__list__, ").append(code).append(");\n");
        }
      } else {
        // For non-literal elements, infer type for correct boxing
        final var type = context.infer(elem);
        if ("string".equals(type)) {
          builder.append("safe_list_append(__list__, ").append(code).append(");\n");
        } else if ("float".equals(type)) {
          builder.append("{\n");
          context.indentInc();
          context.indent(builder);
          builder.append("double* __val__ = (double*)safe_arena_alloc(sizeof(double));\n");
          context.indent(builder);
          builder.append("*__val__ = ").append(code).append(";\n");
          context.indent(builder);
          builder.append("safe_list_append(__list__, __val__);\n");
          context.indentDec();
          context.indent(builder);
          builder.append("}\n");
        } else if (context.isPointerType(type)) {
          // Pointer types: append directly without boxing
          builder.append("safe_list_append(__list__, ").append(code).append(");\n");
        } else if (type != null && context.enumerations().containsKey(type)) {
          builder.append("{\n");
          context.indentInc();
          context.indent(builder);
          builder
              .append(type)
              .append("* __val__ = (")
              .append(type)
              .append("*)safe_alloc(sizeof(")
              .append(type)
              .append("), SAFE_KIND_ENUM, 0);\n");
          context.indent(builder);
          builder.append("*__val__ = ").append(code).append(";\n");
          context.indent(builder);
          builder.append("safe_list_append(__list__, __val__);\n");
          context.indentDec();
          context.indent(builder);
          builder.append("}\n");
        } else if (context.isFunctionType(type) || elem instanceof LambdaNode) {
          // Closures are always boxed (SAFEClosure*) since bug 006 — the
          // pointer goes straight into the list.
          builder.append("safe_list_append(__list__, (void*)").append(code).append(");\n");
        } else {
          builder.append("{\n");
          context.indentInc();
          context.indent(builder);
          builder.append("int64_t* __val__ = (int64_t*)safe_arena_alloc(sizeof(int64_t));\n");
          context.indent(builder);
          builder.append("*__val__ = ").append(code).append(";\n");
          context.indent(builder);
          builder.append("safe_list_append(__list__, __val__);\n");
          context.indentDec();
          context.indent(builder);
          builder.append("}\n");
        }
      }
    }

    context.indent(builder);
    builder.append("__list__;\n");

    context.indentDec();
    context.indent(builder);
    builder.append("})");

    return builder.toString();
  }

  String map(final MapLiteralNode node) {
    final var builder = new StringBuilder();
    builder.append("({\n");

    context.indentInc();
    context.indent(builder);

    // Detect key + value kinds from the first entry so a typed map emits —
    // enabling retain-on-insert and dispose-with-children. Empty literals
    // fall through to the untyped constructor.
    var detectedKey = "string";
    String keyKind = "0";
    String valueKind = "0";
    if (!node.entries().isEmpty()) {
      final var sample = node.entries().getFirst().key();
      if (sample instanceof LiteralNode literal) {
        detectedKey =
            switch (literal) {
              case LiteralNode.IntLiteral ignored -> "int";
              case LiteralNode.UintLiteral ignored -> "int";
              case LiteralNode.BoolLiteral ignored -> "int";
              case LiteralNode.FloatLiteral ignored -> "float";
              case LiteralNode.StringLiteral ignored -> "string";
            };
      } else {
        detectedKey = context.infer(sample);
      }
      keyKind = context.safeKindOf(detectedKey);
      valueKind = context.safeKindOf(context.infer(node.entries().getFirst().value()));
    }
    if ("0".equals(keyKind) && "0".equals(valueKind)) {
      builder.append("SAFEMap* __map__ = safe_map_new();\n");
    } else {
      builder
          .append("SAFEMap* __map__ = safe_map_new_typed(")
          .append(keyKind)
          .append(", ")
          .append(valueKind)
          .append(");\n");
    }
    final var prefix = context.putter(detectedKey);

    for (final MapEntryNode entry : node.entries()) {
      context.indent(builder);
      final var key = context.emit(entry.key());
      final var value = entry.value();
      final var code = context.emit(value);

      // Determine value type for correct put function
      if (value instanceof LiteralNode lit) {
        final var putter =
            switch (lit) {
              case LiteralNode.IntLiteral ignored -> "int";
              case LiteralNode.UintLiteral ignored -> "int";
              case LiteralNode.FloatLiteral ignored -> "float";
              case LiteralNode.StringLiteral ignored -> "str";
              case LiteralNode.BoolLiteral ignored -> "bool";
            };
        builder
            .append(prefix)
            .append(putter)
            .append("(__map__, ")
            .append(key)
            .append(", ")
            .append(code)
            .append(");\n");
      } else {
        // Infer type for non-literal values
        final var type = context.infer(value);
        if (context.isPointerType(type)) {
          builder
              .append(prefix)
              .append("ptr(__map__, ")
              .append(key)
              .append(", (void*)")
              .append(code)
              .append(");\n");
        } else if (type != null && context.enumerations().containsKey(type)) {
          builder
              .append("{ ")
              .append(type)
              .append("* __tmp = safe_alloc(sizeof(")
              .append(type)
              .append("), SAFE_KIND_ENUM, 0); *__tmp = ")
              .append(code)
              .append("; ")
              .append(prefix)
              .append("ptr(__map__, ")
              .append(key)
              .append(", __tmp); }\n");
        } else {
          switch (type) {
            case "float":
              builder
                  .append(prefix)
                  .append("float(__map__, ")
                  .append(key)
                  .append(", ")
                  .append(code)
                  .append(");\n");
              break;
            case "string":
              builder
                  .append(prefix)
                  .append("str(__map__, ")
                  .append(key)
                  .append(", ")
                  .append(code)
                  .append(");\n");
              break;
            case "boolean":
              builder
                  .append(prefix)
                  .append("bool(__map__, ")
                  .append(key)
                  .append(", ")
                  .append(code)
                  .append(");\n");
              break;
            default:
              builder
                  .append(prefix)
                  .append("int(__map__, ")
                  .append(key)
                  .append(", ")
                  .append(code)
                  .append(");\n");
          }
        }
      }
    }

    context.indent(builder);
    builder.append("__map__;\n");

    context.indentDec();
    context.indent(builder);
    builder.append("})");

    return builder.toString();
  }

  /** Set element-tag value used by the runtime to dispatch insertion. */
  private int tagFor(final ASTNode node) {
    final var type = context.infer(node);
    return switch (type) {
      case "float" -> 1;
      case "string" -> 2;
      case "boolean" -> 3;
      default -> 0;
    };
  }
}
