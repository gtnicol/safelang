package io.safelang.compiler.c;

import io.safelang.ast.IndexAccessNode;
import io.safelang.ast.IndexAssignmentNode;
import io.safelang.ast.VariableReferenceNode;

/**
 * Emits C code for SAFE index access ({@code container[index]}) and index assignment ({@code
 * container[index] = value}).
 *
 * <p>Both operations are type-aware: the runtime function chosen depends on the element type of the
 * container (list, tuple, or map keyed by int / float / string). For maps the put/get prefix is
 * selected by the map's key type and the cast is selected by the value type.
 *
 * <p>Stateless apart from the injected {@link CIndexContext}.
 */
final class CIndexCompiler {

  private final CIndexContext context;

  CIndexCompiler(final CIndexContext context) {
    this.context = context;
  }

  String access(final IndexAccessNode node) {
    final var container = context.emit(node.container());
    final var index = context.emit(node.index());

    // Check if container is a tuple variable
    if (node.container() instanceof VariableReferenceNode ref && ref.parts().size() == 1) {
      final var type = context.variables().get(ref.parts().getFirst());
      if (type != null && type.startsWith("tuple<")) {
        // Try to resolve element type from tuple type parameters
        final var element = context.tuple(type, node.index());
        return context.unwrap(container + ".elements[" + index + "]", element);
      }
    }

    // Check if container is a map variable
    if (node.container() instanceof VariableReferenceNode ref && ref.parts().size() == 1) {
      final var type = context.variables().get(ref.parts().getFirst());
      if (type != null && type.startsWith("map<")) {
        return mapAccess(container, index, type);
      }
    }

    // Default: list access — check element type from variable or inferred type
    String type = null;
    if (node.container() instanceof VariableReferenceNode ref && ref.parts().size() == 1) {
      type = context.variables().get(ref.parts().getFirst());
    }
    if (type == null) {
      type = context.infer(node.container());
    }
    // String indexing: s[i] returns a single-character string
    if ("string".equals(type)) {
      return "safe_charat(" + container + ", " + index + ")";
    }
    if (type != null) {
      // Inferred type might be a map (e.g., from nested index like xs[0]["a"])
      if (type.startsWith("map<")) {
        return mapAccess(container, index, type);
      }
      final var element = context.inner(type);
      if ("string".equals(element)) {
        return "(char*)((void**)" + container + "->data)[" + index + "]";
      }
      if ("float".equals(element)) {
        return "*((double*)((void**)" + container + "->data)[" + index + "])";
      }
      if (element.startsWith("list") || element.startsWith("map") || element.startsWith("set")) {
        return "((SAFEList*)((void**)" + container + "->data)[" + index + "])";
      }
      if (element.startsWith("tuple<")) {
        return "(*((SAFETuple*)((void**)" + container + "->data)[" + index + "]))";
      }
      if (context.enumerations().containsKey(element)) {
        if (context.recursive().contains(element)) {
          return "((" + element + "*)((void**)" + container + "->data)[" + index + "])";
        }
        return "(*((" + element + "*)((void**)" + container + "->data)[" + index + "]))";
      }
    }
    return "*((int64_t*)((void**)" + container + "->data)[" + index + "])";
  }

  String assignment(final IndexAssignmentNode node) {
    final var container = context.emit(node.container());
    final var value = context.emit(node.value());

    // Check if container is a map variable
    if (node.container() instanceof VariableReferenceNode ref && ref.parts().size() == 1) {
      final var type = context.variables().get(ref.parts().getFirst());
      if (type != null && type.startsWith("map<")) {
        final var stored = context.valued(type);
        final var key = context.keyed(type);
        final var prefix = context.putter(key);
        final var index = context.emit(node.indices().getFirst());
        if (context.isPointerType(stored)) {
          // Phase 6: retain-on-insert lives in the runtime (safe_map_put_ptr
          // reads the map's meta and retains when the value kind is heap).
          // Codegen just passes the pointer.
          return prefix + "ptr(" + container + ", " + index + ", (void*)" + value + ");";
        }
        if (context.enumerations().containsKey(stored)) {
          return "{ "
              + stored
              + "* __tmp = safe_alloc(sizeof("
              + stored
              + "), SAFE_KIND_ENUM, 0); *__tmp = "
              + value
              + "; "
              + prefix
              + "ptr("
              + container
              + ", "
              + index
              + ", __tmp); }";
        }
        return switch (stored) {
          case "int" -> prefix + "int(" + container + ", " + index + ", " + value + ");";
          case "float" -> prefix + "float(" + container + ", " + index + ", " + value + ");";
          case "string" -> prefix + "str(" + container + ", " + index + ", " + value + ");";
          case "boolean" -> prefix + "bool(" + container + ", " + index + ", " + value + ");";
          default -> prefix + "int(" + container + ", " + index + ", " + value + ");";
        };
      }
    }

    // Default: list assignment — determine element type for correct cast
    String inferred = null;
    if (node.container() instanceof VariableReferenceNode ref && ref.parts().size() == 1) {
      inferred = context.variables().get(ref.parts().getFirst());
    }
    if (inferred == null) {
      inferred = context.infer(node.container());
    }

    if (node.indices().size() == 1) {
      final var index = context.emit(node.indices().getFirst());
      final var element = context.inner(inferred);
      return assignElement(container, index, value, element);
    }
    // Multi-index: chain through nested lists
    final var builder = new StringBuilder("{ ");
    var current = container;
    var resolved = inferred;
    for (int i = 0; i < node.indices().size() - 1; i++) {
      final var index = context.emit(node.indices().get(i));
      final var temp = "__inner" + i + "__";
      builder
          .append("SAFEList* ")
          .append(temp)
          .append(" = (SAFEList*)((void**)")
          .append(current)
          .append("->data)[")
          .append(index)
          .append("]; ");
      current = temp;
      resolved = context.inner(resolved);
    }
    final var last = context.emit(node.indices().getLast());
    final var element = context.inner(resolved);
    builder.append(assignElement(current, last, value, element)).append(" }");
    return builder.toString();
  }

  /** Emit a {@code map[key]} read; covers all stored value types. */
  private String mapAccess(final String container, final String index, final String type) {
    final var stored = context.valued(type);
    final var key = context.keyed(type);
    final var prefix = context.getter(key);
    if (context.isPointerType(stored)) {
      return "("
          + context.translate(stored)
          + ")"
          + prefix
          + "ptr("
          + container
          + ", "
          + index
          + ")";
    }
    if (context.enumerations().containsKey(stored)) {
      if (context.recursive().contains(stored)) {
        return "(" + stored + "*)" + prefix + "ptr(" + container + ", " + index + ")";
      }
      return "(*(" + stored + "*)" + prefix + "ptr(" + container + ", " + index + "))";
    }
    return switch (stored) {
      case "float" -> prefix + "float(" + container + ", " + index + ")";
      case "string" -> prefix + "str(" + container + ", " + index + ")";
      case "boolean" -> prefix + "bool(" + container + ", " + index + ")";
      default -> prefix + "int(" + container + ", " + index + ")";
    };
  }

  /** Emit a single element assignment by element type. */
  private String assignElement(
      final String container, final String index, final String value, final String element) {
    if ("float".equals(element)) {
      return "*((double*)((void**)" + container + "->data)[" + index + "]) = " + value + ";";
    }
    if ("string".equals(element)) {
      return "((void**)" + container + "->data)[" + index + "] = (void*)" + value + ";";
    }
    if ("boolean".equals(element)) {
      return "*((int*)((void**)" + container + "->data)[" + index + "]) = " + value + ";";
    }
    if (context.isPointerType(element)) {
      return "((void**)" + container + "->data)[" + index + "] = (void*)" + value + ";";
    }
    return "*((int64_t*)((void**)" + container + "->data)[" + index + "]) = " + value + ";";
  }
}
