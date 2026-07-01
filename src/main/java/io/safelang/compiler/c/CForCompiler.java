package io.safelang.compiler.c;

import io.safelang.ast.ASTNode;
import io.safelang.ast.ForStatementNode;
import io.safelang.ast.ListLiteralNode;
import io.safelang.ast.SetLiteralNode;
import io.safelang.ast.VariableDeclarationNode;
import java.util.ArrayList;
import java.util.HashSet;

/**
 * Emits C code for a SAFE {@code for ... in ...} loop.
 *
 * <p>The loop is type-aware: strings, sets, maps and lists each lower to a different fragment
 * because the underlying runtime exposes them as different C structures. The loop variable is
 * allocated as the appropriate C type for the inferred element type and bound into the surrounding
 * scope's variable map so subsequent statements in the body can reference it.
 *
 * <p>Stateless apart from the injected {@link CForContext}.
 */
final class CForCompiler {

  private final CForContext context;

  CForCompiler(final CForContext context) {
    this.context = context;
  }

  /**
   * Emit the statements of a loop body, then auto-release heap-typed locals declared by the body at
   * the end of each iteration. Only fires at the top level — stdlib-module for-loops have escape
   * patterns (values captured into containers without a matching retain) that would break under
   * scope-release. Once retain-on-insert lands this can widen.
   */
  private void emitBody(final ForStatementNode node, final StringBuilder builder) {
    // Phase 7: release heap-typed locals introduced by the body at the end
    // of each iteration. We only release variables that are top-level
    // VariableDeclarationNodes of the body — case/destructure bindings and
    // GCC-statement-expression locals live inside nested expressions and
    // are either already scoped by `({ ... })` braces or borrow from an
    // outer ref.
    final var bodyDecls = new ArrayList<String>();
    for (final ASTNode statement : node.body()) {
      if (statement instanceof VariableDeclarationNode decl) {
        bodyDecls.add(decl.name());
      }
    }
    final var snapshot = new HashSet<>(context.variables().keySet());
    for (final ASTNode statement : node.body()) {
      final var code = context.emit(statement);
      if (!code.trim().isEmpty()) {
        context.indent(builder);
        builder.append(code).append("\n");
      }
    }
    final var live = context.variables();
    for (final String name : bodyDecls) {
      if (snapshot.contains(name)) continue;
      if (name.equals(node.variable())) continue;
      final String type = live.get(name);
      if (type == null) continue;
      final var release = context.releaseForLocal(name, type);
      if (release.isEmpty()) continue;
      for (final var line : release.split("\n")) {
        if (line.isEmpty()) continue;
        context.indent(builder);
        builder.append(line).append("\n");
      }
    }
    live.keySet().removeIf(k -> !snapshot.contains(k) && !k.equals(node.variable()));
  }

  String compile(final ForStatementNode node) {
    final var builder = new StringBuilder();
    final var source = context.emit(node.iterable());

    // Detect string iteration
    if (isStringIterable(node.iterable())) {
      builder.append("{\n");
      context.indentInc();
      context.indent(builder);
      builder.append("const char* __str__ = ").append(source).append(";\n");
      context.indent(builder);
      builder.append("int64_t __slen__ = (int64_t)strlen(__str__);\n");
      context.indent(builder);
      builder.append("for (int64_t __i__ = 0; __i__ < __slen__; __i__++) {\n");
      context.indentInc();
      context.indent(builder);
      builder
          .append("char* ")
          .append(context.user(node.variable()))
          .append(" = (char*)safe_arena_alloc(2);\n");
      context.indent(builder);
      builder
          .append(context.user(node.variable()))
          .append("[0] = __str__[__i__]; ")
          .append(context.user(node.variable()))
          .append("[1] = '\\0';\n");
      context.variables().put(node.variable(), "string");

      emitBody(node, builder);

      context.indentDec();
      context.indent(builder);
      builder.append("}\n");
      context.indentDec();
      context.indent(builder);
      builder.append("}");
      return builder.toString();
    }

    // Detect set iteration
    if (isSetIterable(node.iterable())) {
      builder.append("{\n");
      context.indentInc();
      context.indent(builder);
      builder.append("SAFESet* __set__ = ").append(source).append(";\n");
      context.indent(builder);
      builder.append("for (int64_t __i__ = 0; __i__ < __set__->length; __i__++) {\n");
      context.indentInc();
      final var element = setElement(node.iterable());
      context.indent(builder);
      switch (element) {
        case "float":
          builder
              .append("double ")
              .append(context.user(node.variable()))
              .append(" = __set__->data[__i__].float_val;\n");
          break;
        case "string":
          builder
              .append("char* ")
              .append(context.user(node.variable()))
              .append(" = __set__->data[__i__].string_val;\n");
          break;
        default:
          builder
              .append("int64_t ")
              .append(context.user(node.variable()))
              .append(" = __set__->data[__i__].int_val;\n");
          break;
      }
      context.variables().put(node.variable(), element);
      emitBody(node, builder);
      context.indentDec();
      context.indent(builder);
      builder.append("}\n");
      context.indentDec();
      context.indent(builder);
      builder.append("}");
      return builder.toString();
    }

    // Detect map iteration (iterates over keys)
    if (isMapIterable(node.iterable())) {
      builder.append("{\n");
      context.indentInc();
      context.indent(builder);
      builder.append("SAFEList* __keys__ = safe_map_keys(").append(source).append(");\n");
      context.indent(builder);
      builder.append("for (int64_t __i__ = 0; __i__ < __keys__->length; __i__++) {\n");
      context.indentInc();
      final var inferred = context.infer(node.iterable());
      final var key = context.keyed(inferred);
      context.indent(builder);
      switch (key) {
        case "float":
          builder
              .append("double ")
              .append(context.user(node.variable()))
              .append(" = *((double*)((void**)__keys__->data)[__i__]);\n");
          break;
        case "string":
          builder
              .append("char* ")
              .append(context.user(node.variable()))
              .append(" = (char*)((void**)__keys__->data)[__i__];\n");
          break;
        default:
          builder
              .append("int64_t ")
              .append(context.user(node.variable()))
              .append(" = *((int64_t*)((void**)__keys__->data)[__i__]);\n");
          break;
      }
      context.variables().put(node.variable(), key);
      emitBody(node, builder);
      context.indentDec();
      context.indent(builder);
      builder.append("}\n");
      context.indentDec();
      context.indent(builder);
      builder.append("}");
      return builder.toString();
    }

    // List iteration
    builder.append("{\n");
    context.indentInc();

    context.indent(builder);
    builder.append("SAFEList* __list__ = ").append(source).append(";\n");

    context.indent(builder);
    builder.append("for (int64_t __i__ = 0; __i__ < __list__->length; __i__++) {\n");

    context.indentInc();

    // Determine element type from iterable context
    final var element = listElement(node.iterable());
    context.indent(builder);
    if (context.isFunctionType(element)) {
      builder
          .append("SAFEClosure* ")
          .append(context.user(node.variable()))
          .append(" = (SAFEClosure*)((void**)__list__->data)[__i__];\n");
    } else if (context.enumerations().containsKey(element)) {
      if (context.recursive().contains(element)) {
        builder
            .append(element)
            .append("* ")
            .append(context.user(node.variable()))
            .append(" = ((")
            .append(element)
            .append("*)((void**)__list__->data)[__i__]);\n");
      } else {
        builder
            .append(element)
            .append(" ")
            .append(context.user(node.variable()))
            .append(" = *((")
            .append(element)
            .append("*)((void**)__list__->data)[__i__]);\n");
      }
    } else if (element != null && element.startsWith("tuple<")) {
      builder
          .append("SAFETuple ")
          .append(context.user(node.variable()))
          .append(" = *((SAFETuple*)((void**)__list__->data)[__i__]);\n");
    } else if (context.isStruct(element)) {
      builder
          .append(context.translate(element))
          .append(" ")
          .append(context.user(node.variable()))
          .append(" = *((")
          .append(context.translate(element))
          .append("*)((void**)__list__->data)[__i__]);\n");
    } else if (context.isPointerType(element)) {
      builder
          .append(context.translate(element))
          .append(" ")
          .append(context.user(node.variable()))
          .append(" = (")
          .append(context.translate(element))
          .append(")((void**)__list__->data)[__i__];\n");
    } else {
      switch (element) {
        case "float":
          builder
              .append("double ")
              .append(context.user(node.variable()))
              .append(" = *((double*)((void**)__list__->data)[__i__]);\n");
          break;
        case "string":
          builder
              .append("char* ")
              .append(context.user(node.variable()))
              .append(" = (char*)((void**)__list__->data)[__i__];\n");
          break;
        default:
          builder
              .append("int64_t ")
              .append(context.user(node.variable()))
              .append(" = *((int64_t*)((void**)__list__->data)[__i__]);\n");
          break;
      }
    }
    context.variables().put(node.variable(), element);

    emitBody(node, builder);

    context.indentDec();
    context.indent(builder);
    builder.append("}\n");

    // A range iterable (`for i in a..b`) is a freshly allocated list that is never aliased, so
    // release it at loop exit (count 1->0, immediate free, never buffered) — otherwise every such
    // loop leaks its range list. Only for RangeNode sources; a variable iterable is borrowed.
    if (node.iterable() instanceof io.safelang.ast.RangeNode) {
      context.indent(builder);
      builder.append("safe_release(__list__);\n");
    }

    context.indentDec();
    context.indent(builder);
    builder.append("}");

    return builder.toString();
  }

  private boolean isStringIterable(final ASTNode node) {
    return "string".equals(context.infer(node));
  }

  private boolean isSetIterable(final ASTNode node) {
    final var type = context.infer(node);
    return type != null && (type.startsWith("set<") || "set".equals(type));
  }

  private boolean isMapIterable(final ASTNode node) {
    final var type = context.infer(node);
    return type != null && (type.startsWith("map<") || "map".equals(type));
  }

  private String setElement(final ASTNode node) {
    final var type = context.infer(node);
    if (type != null && type.startsWith("set<") && type.endsWith(">")) {
      return type.substring(4, type.length() - 1).trim();
    }
    if (node instanceof SetLiteralNode set && !set.elements().isEmpty()) {
      final var element = context.infer(set.elements().getFirst());
      if (element != null) {
        return element;
      }
    }
    return "int";
  }

  private String listElement(final ASTNode node) {
    final var type = context.infer(node);
    if (type != null && type.startsWith("list<") && type.endsWith(">")) {
      return type.substring(5, type.length() - 1).trim();
    }
    if (node instanceof ListLiteralNode list && !list.elements().isEmpty()) {
      final var element = context.infer(list.elements().getFirst());
      if (element != null) {
        return element;
      }
    }
    return "int";
  }
}
