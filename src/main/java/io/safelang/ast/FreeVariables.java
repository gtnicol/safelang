package io.safelang.ast;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Lexical-scope-aware free-variable analysis for a lambda body. Each scope-introducing construct
 * (lambda, case branch, do block, for/while loop, var declaration) binds names; any reference to a
 * name not bound by an enclosing scope is a free variable. Order-preserving — free variables appear
 * in first-encounter order.
 *
 * <p>Single source of truth shared by the interpreter (to capture only the free variables a closure
 * references, instead of deep-copying the whole lexical scope) and the WASM backend (closure
 * layout). Keeping one walker prevents the two from drifting, which would otherwise silently
 * under-capture a closure.
 */
public final class FreeVariables {

  private FreeVariables() {}

  /**
   * Free variables of a lambda body (params are bound), in encounter order. Unqualified call
   * targets are treated as references — a name like {@code f()} may be a variable holding a
   * closure, which the closure must capture; a plain function name is harmless (the caller filters
   * it out).
   */
  public static List<String> of(final LambdaNode lambda) {
    return of(lambda, name -> true, true);
  }

  /**
   * As {@link #of(LambdaNode)} but only names for which {@code accept} returns true are recorded,
   * and {@code callTargets} controls whether unqualified call names count as references. The WASM
   * backend passes {@code false} (a call target is a function index there, not a captured value).
   */
  public static List<String> of(
      final LambdaNode lambda, final Predicate<String> accept, final boolean callTargets) {
    final var walker = new Walker(accept, callTargets);
    walker.push();
    for (final var param : lambda.parameters()) {
      walker.bind(param.name());
    }
    walker.walk(lambda.body());
    return walker.free;
  }

  /** Free variables of a sequence of statements with {@code params} pre-bound (e.g. a function). */
  public static List<String> of(final List<ParameterNode> params, final List<ASTNode> statements) {
    final var walker = new Walker(name -> true, true);
    walker.push();
    for (final var param : params) {
      walker.bind(param.name());
    }
    for (final var statement : statements) {
      walker.walk(statement);
    }
    return walker.free;
  }

  private static final class Walker {
    final Deque<Set<String>> scopes = new ArrayDeque<>();
    final List<String> free = new ArrayList<>();
    private final Predicate<String> accept;
    private final boolean callTargets;

    Walker(final Predicate<String> accept, final boolean callTargets) {
      this.accept = accept;
      this.callTargets = callTargets;
    }

    void push() {
      scopes.push(new HashSet<>());
    }

    void pop() {
      scopes.pop();
    }

    void bind(final String name) {
      scopes.peek().add(name);
    }

    boolean isBound(final String name) {
      for (final var scope : scopes) {
        if (scope.contains(name)) {
          return true;
        }
      }
      return false;
    }

    void reference(final String name) {
      if (isBound(name) || free.contains(name)) {
        return;
      }
      if (accept.test(name)) {
        free.add(name);
      }
    }

    void walk(final ASTNode node) {
      if (node == null) {
        return;
      }
      switch (node) {
        case VariableReferenceNode reference -> {
          // For dotted access (`obj.field`) only the head is a variable reference.
          if (!reference.hasPrefix()) {
            reference(reference.parts().getFirst());
          }
        }
        case FunctionCallNode call -> {
          // An unqualified call target may be a variable holding a closure (called by name), which
          // a
          // closure must capture. A plain function name is filtered out by the caller's `accept`.
          if (callTargets && !call.hasPrefix()) {
            reference(call.name());
          }
          for (final var argument : call.arguments()) {
            walk(argument);
          }
        }
        case BinaryExpressionNode binary -> {
          walk(binary.left());
          walk(binary.right());
        }
        case UnaryExpressionNode unary -> walk(unary.operand());
        case IfExpressionNode conditional -> {
          walk(conditional.condition());
          walk(conditional.then());
          walk(conditional.otherwise());
        }
        case StringInterpolationNode interpolation -> {
          for (final var part : interpolation.parts()) {
            walk(part);
          }
        }
        case ListLiteralNode list -> {
          for (final var element : list.elements()) {
            walk(element);
          }
        }
        case TupleLiteralNode tuple -> {
          for (final var element : tuple.elements()) {
            walk(element);
          }
        }
        case SetLiteralNode set -> {
          for (final var element : set.elements()) {
            walk(element);
          }
        }
        case MapLiteralNode map -> {
          for (final var entry : map.entries()) {
            walk(entry);
          }
        }
        case MapEntryNode entry -> {
          walk(entry.key());
          walk(entry.value());
        }
        case RangeNode range -> {
          walk(range.start());
          walk(range.end());
          walk(range.step());
        }
        case IndexAccessNode access -> {
          walk(access.container());
          walk(access.index());
        }
        case IndexAssignmentNode assign -> {
          walk(assign.container());
          for (final var index : assign.indices()) {
            walk(index);
          }
          walk(assign.value());
        }
        case FieldAccessNode field -> walk(field.receiver());
        case ObjectCreationNode object -> {
          for (final var assignment : object.fields()) {
            walk(assignment.value());
          }
        }
        case CaseExpressionNode cases -> {
          walk(cases.subject());
          for (final var branch : cases.branches()) {
            push();
            if (branch.pattern() instanceof EnumPatternNode pattern) {
              for (final var name : pattern.bindings()) {
                bind(name);
              }
            }
            walk(branch.guard());
            walk(branch.result());
            pop();
          }
          walk(cases.fallback());
        }
        case DoExpressionNode block -> {
          push();
          for (final var statement : block.statements()) {
            walk(statement);
          }
          walk(block.expression());
          pop();
        }
        case ExpressionStatementNode expression -> walk(expression.expression());
        case VariableDeclarationNode variable -> {
          walk(variable.initializer());
          bind(variable.name());
        }
        case AssignmentNode assign -> {
          reference(assign.parts().getFirst());
          walk(assign.value());
        }
        case ForStatementNode loop -> {
          walk(loop.iterable());
          push();
          bind(loop.variable());
          for (final var statement : loop.body()) {
            walk(statement);
          }
          pop();
        }
        case WhileStatementNode loop -> {
          walk(loop.condition());
          walk(loop.bound());
          push();
          for (final var statement : loop.body()) {
            walk(statement);
          }
          pop();
        }
        case ReturnNode ret -> walk(ret.expression());
        case DestructureNode destructure -> {
          walk(destructure.initializer());
          for (final var name : destructure.names()) {
            bind(name);
          }
        }
        case AssertNode assertion -> {
          walk(assertion.condition());
          walk(assertion.message());
        }
        case LambdaNode nested -> {
          // Nested lambda: its free variables (beyond its own params) are also free for the OUTER
          // lambda, since the outer is what constructs the inner closure.
          push();
          for (final var param : nested.parameters()) {
            bind(param.name());
          }
          walk(nested.body());
          pop();
        }
        default -> {
          // Literals and other leaves: nothing to capture.
        }
      }
    }
  }
}
