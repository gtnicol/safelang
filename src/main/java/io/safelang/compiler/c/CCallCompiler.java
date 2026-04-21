package io.safelang.compiler.c;

import io.safelang.ast.ASTNode;
import io.safelang.ast.BinaryExpressionNode;
import io.safelang.ast.FunctionCallNode;
import io.safelang.ast.UnaryExpressionNode;
import io.safelang.ast.VariableReferenceNode;
import io.safelang.compiler.CompilerException;
import io.safelang.runtime.BuiltinRegistry;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Emits C code for SAFE function calls.
 *
 * <p>Handles every flavour the language supports:
 *
 * <ul>
 *   <li>module-qualified calls ({@code io:println})
 *   <li>builtin functions (delegated to {@link CBuiltinResolver} via the context)
 *   <li>enum variant constructors ({@code Some(x)}, {@code Ok(v)})
 *   <li>intra-module user-function calls
 *   <li>top-level user-function calls
 *   <li>closure invocation through a {@code fn<...>} variable
 *   <li>default-argument padding, including the chained case where one default expression
 *       references an earlier parameter — emitted as a GCC statement expression with temporary
 *       variables
 * </ul>
 *
 * <p>Stateless apart from the injected {@link CCallContext}.
 */
final class CCallCompiler {

  private final CCallContext context;

  CCallCompiler(final CCallContext context) {
    this.context = context;
  }

  String compile(final FunctionCallNode node) {
    final var name = node.name();

    // Build args string. Each struct-typed aliased argument is wrapped so its
    // heap fields are retained before the call — paired with a release at
    // the callee's function-body exit.
    final var arguments = node.arguments();
    final var args = new StringBuilder();
    for (int i = 0; i < arguments.size(); i++) {
      if (i > 0) args.append(", ");
      final var argNode = arguments.get(i);
      args.append(context.wrapStructArgForCall(context.emit(argNode), argNode));
    }

    // Module-qualified calls: io:println -> mangled name or builtin
    if (node.hasPrefix()) {
      final var prefix = node.prefix();
      if (context.modules().contains(prefix)) {
        // Qualified enum variant construction: mod:Ok(42). If the prefix module exports an
        // enum whose variant matches `name`, emit the variant constructor. C flattens modules
        // at codegen time so the owning enum is a compile-time identifier.
        if (context.registry() != null) {
          for (final var entry : context.registry().enums(prefix).entrySet()) {
            final var declaration = entry.getValue();
            if (!declaration.isPublic()) {
              continue;
            }
            for (final var variant : declaration.variants()) {
              if (variant.name().equals(name)) {
                return entry.getKey() + "_" + name + "_new(" + args + ")";
              }
            }
          }
        }

        // Try builtin resolution only if the prefix matches the builtin's module.
        // Skip when the module defines a wrapper that returns a different type than the
        // raw builtin (e.g., file:read returns ReadResult via fileload, not raw char*).
        final var module = BuiltinRegistry.module(name);
        if (module != null && module.equals(prefix)) {
          final var wrapper =
              context.registry() != null ? context.registry().functions(prefix).get(name) : null;
          final var skip =
              wrapper != null
                  && wrapper.returns() != null
                  && !wrapper.returns().name().equals(BuiltinRegistry.returns(name));
          if (!skip) {
            final var builtin = context.resolveBuiltin(name, arguments);
            if (builtin != null) return builtin;
          }
        }

        // Call mangled module function — enforce visibility
        if (context.registry() != null) {
          final var declaration = context.registry().functions(prefix).get(name);
          if (declaration != null && !declaration.isPublic()) {
            throw new CompilerException(
                "Cannot access private function '" + name + "' in module '" + prefix + "'");
          }
        }
        final var mangled = context.mangle(prefix, name);
        final var raw = mangled + "(" + args + ")";
        return pad(prefix + ":" + name, raw, arguments);
      }
      return prefix + "_" + name + "(" + args + ")";
    }

    // Call-through-value: f(args) where f is a fn variable
    final var type = context.variables().get(name);
    if (type != null && type.startsWith("fn<")) {
      return invoke(name, arguments, type);
    }

    // Enum variant construction: Some(x), Ok(v), etc.
    // Prefer enums from the current module to avoid cross-module variant collisions
    String resolved = null;
    for (final var entry : context.enumerations().entrySet()) {
      for (final var variant : entry.getValue().variants()) {
        if (variant.name().equals(name)) {
          final var candidate = entry.getKey() + "_" + name + "_new(" + args + ")";
          if (resolved == null) resolved = candidate;
          // Prefer current module's enum (check if enum was defined in current module)
          if (context.currentModule() != null && context.imported().contains(entry.getKey())) {
            resolved = candidate;
          }
        }
      }
    }
    if (resolved != null) return resolved;

    // Builtin resolution (for non-prefixed calls in module bodies and user code)
    final var builtin = context.resolveBuiltin(name, arguments);
    if (builtin != null) return builtin;

    // Intra-module call resolution
    if (context.currentModule() != null) {
      // Check if function name is a builtin — builtins take priority
      // to prevent module wrappers from self-recursing
      if (!BuiltinRegistry.isBuiltin(name)) {
        final var mangled = context.mangle(context.currentModule(), name);
        if (context.emitted().contains(mangled)) {
          final var raw = mangled + "(" + args + ")";
          return pad(mangled, raw, arguments);
        }
      }
    }

    // Regular function call — mangle user-defined functions to avoid libc collisions
    if (context.emitted().contains(name)) {
      final var mangled = context.mangle(name);
      final var raw = mangled + "(" + args + ")";
      return pad(name, raw, arguments);
    }
    final var raw = name + "(" + args + ")";
    return pad(name, raw, arguments);
  }

  /** Pad a function call's argument list with default-argument values. */
  private String pad(final String key, final String call, final List<ASTNode> provided) {
    final var declaration = context.functions().get(key);
    if (declaration == null) return call;
    final var params = declaration.parameters();
    if (provided.size() >= params.size()) return call;

    // Check if any default references an earlier parameter name
    final var names = new HashSet<String>();
    for (int i = 0; i < provided.size(); i++) {
      names.add(params.get(i).name());
    }
    var chained = false;
    for (int i = provided.size(); i < params.size(); i++) {
      if (params.get(i).hasDefault() && references(params.get(i).initial(), names)) {
        chained = true;
        break;
      }
    }

    if (chained) {
      // Use GCC statement expression: ({ type a = arg0; ...; f(a, b); })
      final var wrapper = new StringBuilder("({");
      final var temps = new ArrayList<String>();
      for (int i = 0; i < provided.size(); i++) {
        final var temp = "__default_" + i + "__";
        temps.add(temp);
        final var type = context.translate(params.get(i).type().fullName());
        wrapper
            .append(" ")
            .append(type)
            .append(" ")
            .append(temp)
            .append(" = ")
            .append(context.emit(provided.get(i)))
            .append(";");
        // Temporarily register the temp so default expressions can reference the param name
        context.variables().put(params.get(i).name(), params.get(i).type().fullName());
      }
      for (int i = provided.size(); i < params.size(); i++) {
        if (params.get(i).hasDefault()) {
          final var temp = "__default_" + i + "__";
          temps.add(temp);
          final var type = context.translate(params.get(i).type().fullName());
          // Temporarily bind earlier params for the default expression
          final var snapshot = new HashMap<>(context.variables());
          for (int j = 0; j < provided.size(); j++) {
            context.variables().put(params.get(j).name(), params.get(j).type().fullName());
          }
          // Map parameter names to their temp variables for cross-references
          final var saved = context.aliases();
          final var active = new HashMap<String, String>();
          for (int j = 0; j < i; j++) {
            active.put(params.get(j).name(), "__default_" + j + "__");
          }
          context.aliases(active);
          wrapper
              .append(" ")
              .append(type)
              .append(" ")
              .append(temp)
              .append(" = ")
              .append(context.emit(params.get(i).initial()))
              .append(";");
          context.aliases(saved);
          context.variables().clear();
          context.variables().putAll(snapshot);
        }
      }
      final var paren = call.indexOf('(');
      if (paren < 0) return call;
      wrapper.append(" ").append(call, 0, paren + 1);
      for (int i = 0; i < temps.size(); i++) {
        if (i > 0) wrapper.append(", ");
        wrapper.append(temps.get(i));
      }
      wrapper.append("); })");
      return wrapper.toString();
    }

    // Simple case: no cross-references, inline the defaults
    final var builder = new StringBuilder();
    for (int i = 0; i < provided.size(); i++) {
      if (i > 0) builder.append(", ");
      builder.append(context.emit(provided.get(i)));
    }
    for (int i = provided.size(); i < params.size(); i++) {
      if (params.get(i).hasDefault()) {
        if (!builder.isEmpty()) builder.append(", ");
        builder.append(context.emit(params.get(i).initial()));
      }
    }
    // Extract function name from "name(" pattern
    final var paren = call.indexOf('(');
    if (paren < 0) return call;
    return call.substring(0, paren + 1) + builder + ")";
  }

  /** True when {@code node} contains a reference to any name in {@code names}. */
  private boolean references(final ASTNode node, final Set<String> names) {
    if (node instanceof VariableReferenceNode ref) {
      return ref.parts().stream().anyMatch(names::contains);
    }
    if (node instanceof BinaryExpressionNode binary) {
      return references(binary.left(), names) || references(binary.right(), names);
    }
    if (node instanceof UnaryExpressionNode unary) {
      return references(unary.operand(), names);
    }
    if (node instanceof FunctionCallNode call) {
      return call.arguments().stream().anyMatch(arg -> references(arg, names));
    }
    return false;
  }

  /** Emit an indirect closure call: cast the function pointer and apply the args. */
  private String invoke(final String name, final List<ASTNode> arguments, final String type) {
    // Parse fn<P1, P2, ..., R> to get param types and return type
    final var parameters = context.params(type);
    final var ctype = parameters.isEmpty() ? "int64_t" : context.translate(parameters.getLast());

    // Build cast: RetType(*)(P1, P2, ..., void*)
    final var cast = new StringBuilder("(");
    cast.append(ctype).append("(*)(");
    for (int i = 0; i < arguments.size(); i++) {
      if (i > 0) cast.append(", ");
      if (i < parameters.size() - 1) {
        cast.append(context.translate(parameters.get(i)));
      } else {
        cast.append("int64_t");
      }
    }
    if (!arguments.isEmpty()) cast.append(", ");
    cast.append("void*))");

    // Build call: ((RetType(*)(Params, void*))name->fn)(args, name->context)
    // Closures are always boxed (SAFEClosure*) — see bug 006.
    final var builder = new StringBuilder();
    builder.append("(").append(cast).append(name).append("->fn)(");
    for (int i = 0; i < arguments.size(); i++) {
      if (i > 0) builder.append(", ");
      builder.append(context.emit(arguments.get(i)));
    }
    if (!arguments.isEmpty()) builder.append(", ");
    builder.append(name).append("->context)");
    return builder.toString();
  }
}
