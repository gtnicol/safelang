package io.safelang.analyzer;

import io.safelang.ModuleRegistry;
import io.safelang.ast.*;
import io.safelang.runtime.BuiltinRegistry;
import java.util.*;

/**
 * Checks whether functions transitively call impure builtins. Used by SemanticAnalyzer in strict
 * mode to reject impure function calls.
 *
 * <p>The walker is module-aware: when checking a function whose body contains an unqualified call,
 * we look up the target in the function's owning module rather than always in the main program.
 * This is what makes the cross-module strict check work — a strict main calling {@code mod:noop()}
 * where {@code noop} privately calls {@code mod:helper()} which calls {@code time()} is correctly
 * rejected.
 */
class PurityChecker {

  // Host-dependent global variables (baked from System.getProperty) — referencing one makes
  // execution machine-specific, so strict/deterministic mode rejects them like a NONDETERMINISTIC
  // builtin. Keep in sync with the ENVIRONMENT-gated variables in BuiltinRegistry.
  static final Set<String> HOST_DEPENDENT = Set.of("OS", "ARCH", "OS_VERSION", "PLATFORM");

  private final Map<FunctionDeclarationNode, Boolean> cache = new IdentityHashMap<>();
  private final Map<String, FunctionDeclarationNode> functions;
  private final ModuleRegistry registry;

  // Stack of module names currently being walked. Empty means "main program".
  // The topmost entry is the module whose function body the walker is
  // currently inside, used to resolve unqualified call targets.
  private final Deque<String> moduleStack = new ArrayDeque<>();

  PurityChecker(
      final Map<String, FunctionDeclarationNode> functions, final ModuleRegistry registry) {
    this.functions = functions;
    this.registry = registry;
  }

  /** Check a main-program function for transitive impurity. */
  boolean impure(final FunctionDeclarationNode function) {
    return checkFunction(function);
  }

  /**
   * Check a function declared in module {@code module} for transitive impurity. Unqualified calls
   * inside the function body resolve against the named module's namespace via {@link
   * ModuleRegistry}.
   */
  boolean impure(final FunctionDeclarationNode function, final String module) {
    if (module == null) {
      return checkFunction(function);
    }
    moduleStack.push(module);
    try {
      return checkFunction(function);
    } finally {
      moduleStack.pop();
    }
  }

  private boolean checkFunction(final FunctionDeclarationNode function) {
    if (cache.containsKey(function)) return cache.get(function);
    // Prevent infinite recursion during analysis
    cache.put(function, false);
    var result = isBodyImpure(function.body());
    // Check default parameter expressions
    if (!result) {
      for (final var parameter : function.parameters()) {
        if (parameter.hasDefault() && isNodeImpure(parameter.initial())) {
          result = true;
          break;
        }
      }
    }
    // Check requires/ensures contract expressions
    if (!result && function.hasRequires() && isNodeImpure(function.requires())) {
      result = true;
    }
    if (!result && function.hasEnsures() && isNodeImpure(function.ensures())) {
      result = true;
    }
    cache.put(function, result);
    return result;
  }

  private boolean isBodyImpure(final List<ASTNode> body) {
    for (final var node : body) {
      if (isNodeImpure(node)) return true;
    }
    return false;
  }

  /**
   * Search a module for a function by name, including private declarations. {@link
   * ModuleRegistry#function(String, String)} only returns public ones, which would hide private
   * helpers from the cross-module purity check.
   */
  private FunctionDeclarationNode findFunctionInModule(final String module, final String name) {
    final var program = registry.program(module);
    if (program == null) return null;
    for (final var declaration : program.declarations()) {
      if (declaration instanceof FunctionDeclarationNode function && function.name().equals(name)) {
        return function;
      }
    }
    return null;
  }

  boolean isExpressionImpure(final ASTNode node) {
    return isNodeImpure(node);
  }

  private boolean isNodeImpure(final ASTNode node) {
    if (node == null) return false;
    return switch (node) {
      case FunctionCallNode call -> {
        final var name = call.name();
        // Only flag as impure builtin when the prefix matches the builtin's
        // module. Strict mode admits PURE and OBSERVABLE builtins; only
        // NONDETERMINISTIC ones (time, rand, file I/O, input, getenv, ...)
        // count as impure for the purpose of this check.
        if (BuiltinRegistry.isBuiltin(name) && !BuiltinRegistry.isStrictAllowed(name)) {
          if (!call.hasPrefix() || call.prefix().equals(BuiltinRegistry.module(name))) {
            yield true;
          }
        }
        // Transitive check: if calling a user-defined function, check it recursively.
        if (call.hasPrefix()) {
          if (registry != null) {
            final var target = registry.function(call.prefix(), name);
            if (target != null && impure(target, call.prefix())) {
              yield true;
            }
          }
        } else {
          // Resolve unqualified calls in the appropriate module's namespace.
          // If we are walking a function declared in module M, an unqualified
          // call to `helper()` resolves against M's functions (including
          // private ones), not main's. Without this, transitive impurity
          // checks across module boundaries would miss every private helper
          // that the entry point doesn't import directly.
          final var current = moduleStack.peek();
          FunctionDeclarationNode target = null;
          if (current != null && registry != null) {
            target = findFunctionInModule(current, name);
          }
          if (target == null) {
            target = functions.get(name);
          }
          if (target != null) {
            // For module-scoped targets, recurse with the module context so
            // their own unqualified calls keep resolving correctly.
            final var nextModule =
                (current != null && registry != null && findFunctionInModule(current, name) != null)
                    ? current
                    : null;
            if (impure(target, nextModule)) {
              yield true;
            }
          }
        }
        for (final var argument : call.arguments()) {
          if (isNodeImpure(argument)) yield true;
        }
        yield false;
      }
      case ReturnNode r -> r.hasExpression() && isNodeImpure(r.expression());
      case ExpressionStatementNode e -> isNodeImpure(e.expression());
      case VariableDeclarationNode v -> v.hasInitializer() && isNodeImpure(v.initializer());
      case DestructureNode d -> isNodeImpure(d.initializer());
      case BinaryExpressionNode b -> isNodeImpure(b.left()) || isNodeImpure(b.right());
      case UnaryExpressionNode u -> isNodeImpure(u.operand());
      case IfExpressionNode i ->
          isNodeImpure(i.condition())
              || isNodeImpure(i.then())
              || (i.hasOtherwise() && isNodeImpure(i.otherwise()));
      case CaseExpressionNode c -> {
        if (isNodeImpure(c.subject())) yield true;
        for (final var branch : c.branches()) {
          if (isNodeImpure(branch.result())) yield true;
          if (branch.hasGuard() && isNodeImpure(branch.guard())) yield true;
        }
        yield c.hasFallback() && isNodeImpure(c.fallback());
      }
      case ForStatementNode f -> isNodeImpure(f.iterable()) || isBodyImpure(f.body());
      case WhileStatementNode w ->
          isNodeImpure(w.condition()) || isNodeImpure(w.bound()) || isBodyImpure(w.body());
      case DoExpressionNode d -> isBodyImpure(d.statements()) || isNodeImpure(d.expression());
      case AssignmentNode a -> isNodeImpure(a.value());
      case IndexAssignmentNode ia -> {
        if (isNodeImpure(ia.container())) yield true;
        for (final var index : ia.indices()) {
          if (isNodeImpure(index)) yield true;
        }
        yield isNodeImpure(ia.value());
      }
      case LambdaNode lambda -> isNodeImpure(lambda.body());
      case ListLiteralNode list -> {
        for (final var element : list.elements()) {
          if (isNodeImpure(element)) yield true;
        }
        yield false;
      }
      case MapLiteralNode map -> {
        for (final var entry : map.entries()) {
          if (isNodeImpure(entry.key()) || isNodeImpure(entry.value())) yield true;
        }
        yield false;
      }
      case IndexAccessNode access ->
          isNodeImpure(access.container()) || isNodeImpure(access.index());
      case StringInterpolationNode interpolation -> {
        for (final var part : interpolation.parts()) {
          if (isNodeImpure(part)) yield true;
        }
        yield false;
      }
      case AssertNode assertion ->
          isNodeImpure(assertion.condition())
              || (assertion.hasMessage() && isNodeImpure(assertion.message()));
      case SetLiteralNode set -> {
        for (final var element : set.elements()) {
          if (isNodeImpure(element)) yield true;
        }
        yield false;
      }
      case TupleLiteralNode tuple -> {
        for (final var element : tuple.elements()) {
          if (isNodeImpure(element)) yield true;
        }
        yield false;
      }
      case RangeNode range -> {
        if (isNodeImpure(range.start()) || isNodeImpure(range.end())) yield true;
        yield range.hasStep() && isNodeImpure(range.step());
      }
      case VariableReferenceNode ref ->
          ref.parts().size() == 1 && HOST_DEPENDENT.contains(ref.parts().getFirst());
      case FieldAccessNode access -> isNodeImpure(access.receiver());
      case ObjectCreationNode creation -> {
        for (final var field : creation.fields()) {
          if (isNodeImpure(field.value())) yield true;
        }
        yield false;
      }
        // Metadata and declaration nodes — intentionally no purity checking needed
      case EnumDeclarationNode ignored -> false;
      case TypeDeclarationNode ignored -> false;
      case TypeAliasNode ignored -> false;
      case EnumVariantNode ignored -> false;
      case FieldDeclarationNode ignored -> false;
      case ParameterNode ignored -> false;
      case TypeNode ignored -> false;
      case ImportNode ignored -> false;
      case ProgramNode ignored -> false;
      default -> false;
    };
  }
}
