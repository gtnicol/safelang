package io.safelang.compiler.c;

import io.safelang.ast.*;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.stream.Collectors;

final class CLambdaCompiler {

  private final CLambdaContext context;

  CLambdaCompiler(final CLambdaContext context) {
    this.context = context;
  }

  String closure(final LambdaNode node) {
    if (!context.has(node)) {
      lambda(node);
    }
    final var name = context.name(node);
    final var captures = context.captures(node);

    if (captures.isEmpty()) {
      // Always boxed: bug 006 — unboxed SAFEClosure values leak their
      // captures array. For no-capture closures the box carries an empty
      // context but still participates in dispose-on-release uniformly.
      return "safe_closure_box(safe_closure_new((void*)"
          + name
          + ", NULL, "
          + node.parameters().size()
          + "))";
    }

    // Compute the capture-heap bitmap so dispose_closure can release each
    // heap capture. SAFEHeader.meta carries 16 bits after the Phase-7
    // audit, so indices 0..15 fit directly; closures with a 17th or later
    // heap capture get a compile-time warning and those captures will not
    // participate in RC. The emission loop below respects the same limit,
    // so a warned closure leaks 1 refcount per over-limit heap capture
    // instead of double-freeing or crashing.
    final int BITMAP_LIMIT = 16;
    int heapBitmap = 0;
    int heapCaptureCount = 0;
    int lastHeapIndex = -1;
    for (var index = 0; index < captures.size(); index++) {
      final var type = context.variables().get(captures.get(index));
      if (type != null && context.isHeapRc(type)) {
        heapCaptureCount++;
        lastHeapIndex = index;
        if (index < BITMAP_LIMIT) heapBitmap |= 1 << index;
      }
    }
    if (lastHeapIndex >= BITMAP_LIMIT) {
      System.err.println(
          "warning: lambda captures "
              + heapCaptureCount
              + " heap-RC values; only the first "
              + BITMAP_LIMIT
              + " participate in dispose-on-release. Captures at indices "
              + BITMAP_LIMIT
              + ".."
              + lastHeapIndex
              + " will leak one refcount each. Consider wrapping captures in a struct.");
    }

    final var builder = new StringBuilder("({\n");
    context.indent(builder);
    // Phase 5.1: captures array is now safe_alloc'd (KIND_RAW, no children
    // in the bitmap sense) so dispose_closure can safe_release it after
    // dropping each heap capture's refcount. SAFE_KIND_RAW means "opaque
    // byte buffer" — no children to walk.
    builder
        .append("void** __captures__ = (void**)safe_alloc(")
        .append(captures.size())
        .append(" * sizeof(void*), SAFE_KIND_RAW, 0);\n");
    for (var index = 0; index < captures.size(); index++) {
      final var capture = captures.get(index);
      final var type = context.variables().get(capture);
      context.indent(builder);
      if ("int".equals(type) || "uint".equals(type) || type == null) {
        builder
            .append("{ int64_t* __v__ = (int64_t*)safe_arena_alloc(sizeof(int64_t)); *__v__ = ")
            .append(capture)
            .append("; __captures__[")
            .append(index)
            .append("] = __v__; }\n");
      } else if ("float".equals(type)) {
        builder
            .append("{ double* __v__ = (double*)safe_arena_alloc(sizeof(double)); *__v__ = ")
            .append(capture)
            .append("; __captures__[")
            .append(index)
            .append("] = __v__; }\n");
      } else if ("boolean".equals(type)) {
        builder
            .append("{ bool* __v__ = (bool*)safe_arena_alloc(sizeof(bool)); *__v__ = ")
            .append(capture)
            .append("; __captures__[")
            .append(index)
            .append("] = __v__; }\n");
      } else if (context.struct(type) || context.enumeration(type)) {
        final var mapped = context.translate(type);
        final var refKind = context.enumeration(type) ? "SAFE_KIND_ENUM" : "SAFE_KIND_OBJECT";
        builder
            .append("{ ")
            .append(mapped)
            .append("* __v__ = (")
            .append(mapped)
            .append("*)safe_alloc(sizeof(")
            .append(mapped)
            .append("), ")
            .append(refKind)
            .append(", 0); *__v__ = ")
            .append(capture)
            .append("; ")
            // Phase 7b: if the captured struct has heap-RC fields, retain
            // each so the closure's copy owns its references. Outer scope
            // can then release its copy without dangling the capture.
            .append(context.retainStructFields("(*__v__)", type))
            .append("__captures__[")
            .append(index)
            .append("] = __v__; }\n");
      } else if (!context.isHeapRc(type)) {
        // Non-heap pointer capture (notably `string` — a bare char* with no SAFEHeader). Store the
        // pointer directly with NO retain: safe_retain would read a header 8 bytes before the
        // buffer
        // (heap-buffer-overflow). This mirrors the reader (lambda() treats strings as bare char*)
        // and
        // the bitmap loop, which already excludes non-heap types so dispose_closure never releases
        // it.
        context.pad(builder);
        builder
            .append("__captures__[")
            .append(index)
            .append("] = (void*)")
            .append(capture)
            .append(";\n");
      } else {
        // Heap-RC capture (list / map / set / bytes / recursive enum):
        // retain so the closure owns its own reference; dispose_closure
        // releases via the bitmap bit set above. Captures past the
        // 16-slot bitmap limit skip the retain — otherwise dispose_closure
        // would never match them and the extra refcounts would leak.
        context.pad(builder);
        if (index < BITMAP_LIMIT) {
          builder
              .append("__captures__[")
              .append(index)
              .append("] = (void*)safe_retain((void*)")
              .append(capture)
              .append(");\n");
        } else {
          builder
              .append("__captures__[")
              .append(index)
              .append("] = (void*)")
              .append(capture)
              .append(";\n");
        }
      }
    }
    context.indent(builder);
    // Always box (bug 006): an unboxed SAFEClosure value has no
    // SAFEHeader of its own, so dispose_closure never runs and the
    // captures block + retained heap captures leak. safe_closure_box
    // propagates the meta bitmap into the boxed SAFEHeader so
    // dispose_closure can walk the captures at refs=0.
    builder
        .append("safe_closure_box(safe_closure_new_meta((void*)")
        .append(name)
        .append(", (void*)__captures__, ")
        .append(node.parameters().size())
        .append(", ")
        .append(heapBitmap)
        .append("));\n");
    context.pad(builder);
    builder.append("})");
    return builder.toString();
  }

  private void lambda(final LambdaNode node) {
    final var name = context.next();
    context.name(node, name);

    final var referenced = new LinkedHashSet<String>();
    collect(node.body(), referenced);
    for (final var param : node.parameters()) {
      referenced.remove(param.name());
    }
    final var captures =
        referenced.stream().filter(context.variables()::containsKey).collect(Collectors.toList());
    context.captures(node, captures);

    final var returns = context.infer(node.body());
    final var type = context.translate(returns != null ? returns : "int");

    final var builder = new StringBuilder();
    builder.append("static ").append(type).append(" ").append(name).append("(");
    for (var index = 0; index < node.parameters().size(); index++) {
      if (index > 0) {
        builder.append(", ");
      }
      final var param = node.parameters().get(index);
      final var mapped =
          param.type() != null ? context.translate(param.type().fullName()) : "int64_t";
      builder.append(mapped).append(" ").append(context.user(param.name()));
    }
    if (!node.parameters().isEmpty()) {
      builder.append(", ");
    }
    builder.append("void* __ctx) {\n");

    if (!captures.isEmpty()) {
      builder.append("    void** __captures__ = (void**)__ctx;\n");
      for (var index = 0; index < captures.size(); index++) {
        final var capture = captures.get(index);
        final var mangled = context.user(capture);
        final var typeName = context.variables().get(capture);
        builder.append("    ");
        if ("int".equals(typeName) || "uint".equals(typeName) || typeName == null) {
          builder
              .append("int64_t ")
              .append(mangled)
              .append(" = *((int64_t*)__captures__[")
              .append(index)
              .append("]);\n");
        } else if ("float".equals(typeName)) {
          builder
              .append("double ")
              .append(mangled)
              .append(" = *((double*)__captures__[")
              .append(index)
              .append("]);\n");
        } else if ("boolean".equals(typeName)) {
          builder
              .append("bool ")
              .append(mangled)
              .append(" = *((bool*)__captures__[")
              .append(index)
              .append("]);\n");
        } else if ("string".equals(typeName)) {
          builder
              .append("char* ")
              .append(mangled)
              .append(" = (char*)__captures__[")
              .append(index)
              .append("];\n");
        } else if (context.struct(typeName) || context.enumeration(typeName)) {
          final var mapped = context.translate(typeName);
          builder
              .append(mapped)
              .append(" ")
              .append(mangled)
              .append(" = *(")
              .append(mapped)
              .append("*)__captures__[")
              .append(index)
              .append("];\n");
        } else {
          builder
              .append(context.translate(typeName))
              .append(" ")
              .append(mangled)
              .append(" = (")
              .append(context.translate(typeName))
              .append(")__captures__[")
              .append(index)
              .append("];\n");
        }
      }
    }

    builder.append("    return ").append(context.body(node)).append(";\n");
    builder.append("}");
    context.define(builder.toString());
  }

  private void collect(final ASTNode node, final Set<String> names) {
    if (node == null) {
      return;
    }
    switch (node) {
      case VariableReferenceNode reference -> names.add(reference.parts().getFirst());
      case BinaryExpressionNode binary -> {
        collect(binary.left(), names);
        collect(binary.right(), names);
      }
      case UnaryExpressionNode unary -> collect(unary.operand(), names);
      case FunctionCallNode call -> {
        for (final var argument : call.arguments()) {
          collect(argument, names);
        }
      }
      case IfExpressionNode conditional -> {
        collect(conditional.condition(), names);
        collect(conditional.then(), names);
        if (conditional.hasOtherwise()) {
          collect(conditional.otherwise(), names);
        }
      }
      case CaseExpressionNode cases -> {
        collect(cases.subject(), names);
        for (final var branch : cases.branches()) {
          collect(branch.result(), names);
        }
        if (cases.hasFallback()) {
          collect(cases.fallback(), names);
        }
      }
      case IndexAccessNode access -> {
        collect(access.container(), names);
        collect(access.index(), names);
      }
      case LambdaNode nested -> collect(nested.body(), names);
      case DoExpressionNode block -> {
        for (final var statement : block.statements()) {
          collect(statement, names);
        }
        collect(block.expression(), names);
      }
      case ListLiteralNode list -> {
        for (final var element : list.elements()) {
          collect(element, names);
        }
      }
      case TupleLiteralNode tuple -> {
        for (final var element : tuple.elements()) {
          collect(element, names);
        }
      }
      case StringInterpolationNode interpolation -> {
        for (final var part : interpolation.parts()) {
          collect(part, names);
        }
      }
      case ReturnNode ret -> {
        if (ret.hasExpression()) {
          collect(ret.expression(), names);
        }
      }
      case FieldAccessNode access -> collect(access.receiver(), names);
      case ExpressionStatementNode expression -> collect(expression.expression(), names);
      case VariableDeclarationNode declaration -> {
        if (declaration.hasInitializer()) {
          collect(declaration.initializer(), names);
        }
      }
      case AssignmentNode assignment -> {
        names.add(assignment.parts().getFirst());
        collect(assignment.value(), names);
      }
      case IndexAssignmentNode assignment -> {
        collect(assignment.container(), names);
        for (final var index : assignment.indices()) {
          collect(index, names);
        }
        collect(assignment.value(), names);
      }
      case FieldAssignmentNode field -> collect(field.value(), names);
      case ForStatementNode loop -> {
        collect(loop.iterable(), names);
        for (final var statement : loop.body()) {
          collect(statement, names);
        }
      }
      case WhileStatementNode loop -> {
        collect(loop.condition(), names);
        collect(loop.bound(), names);
        for (final var statement : loop.body()) {
          collect(statement, names);
        }
      }
      case MapLiteralNode map -> {
        for (final var entry : map.entries()) {
          collect(entry.key(), names);
          collect(entry.value(), names);
        }
      }
      case SetLiteralNode set -> {
        for (final var element : set.elements()) {
          collect(element, names);
        }
      }
      case ObjectCreationNode object -> {
        for (final var field : object.fields()) {
          collect(field.value(), names);
        }
      }
      case RangeNode range -> {
        collect(range.start(), names);
        collect(range.end(), names);
        if (range.step() != null) {
          collect(range.step(), names);
        }
      }
      case DestructureNode destructure -> collect(destructure.initializer(), names);
      default -> {}
    }
  }
}
