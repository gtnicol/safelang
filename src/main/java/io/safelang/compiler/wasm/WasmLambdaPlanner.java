package io.safelang.compiler.wasm;

import io.safelang.ast.*;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Plans a lambda for compilation: assigns it a function index, table slot, and computes its
 * captured (free) variable list.
 *
 * <p>Capture analysis is a hand-written AST walker that maintains a lexical scope stack. Each
 * scope-introducing construct (lambda, case branch, do block, for/while loop) pushes a new {@link
 * Set} of bound names; references are checked against the entire stack and any name not bound there
 * but resolvable in the enclosing context becomes a free variable.
 */
final class WasmLambdaPlanner {

  private final WasmCompilationState state;
  private final Map<String, Integer> functions;
  private final List<Integer> tables;
  private final Map<Integer, Integer> slots;
  private final Map<Integer, List<String>> captures;
  private final Map<Integer, List<SymbolKey>> types;
  private final WasmLambdaHooks hooks;

  WasmLambdaPlanner(
      final WasmCompilationState state,
      final Map<String, Integer> functions,
      final List<Integer> tables,
      final Map<Integer, Integer> slots,
      final Map<Integer, List<String>> captures,
      final Map<Integer, List<SymbolKey>> types,
      final WasmLambdaHooks hooks) {
    this.state = state;
    this.functions = functions;
    this.tables = tables;
    this.slots = slots;
    this.captures = captures;
    this.types = types;
    this.hooks = hooks;
  }

  WasmLambdaPlan plan(final WasmModule module, final LambdaNode node) {
    final var walker = new CaptureWalker();
    walker.push();
    for (final var param : node.parameters()) {
      walker.bind(param.name());
    }
    walker.walk(node.body());

    final var name = "__lambda_" + state.lambdaCounter++;
    final var signature = new int[1 + node.parameters().size()];
    signature[0] = WasmOpcode.TYPE_I32;
    for (var index = 1; index < signature.length; index++) {
      signature[index] = WasmOpcode.TYPE_I64;
    }
    final var type = module.addType(signature, new int[] {WasmOpcode.TYPE_I64});
    final var index = module.addFunction(type);
    functions.put(name, index);

    slots.put(index, state.tableOffset + tables.size());
    tables.add(index);
    captures.put(index, walker.free);

    final var values = new ArrayList<SymbolKey>();
    for (final var capture : walker.free) {
      values.add(hooks.value(capture));
    }
    types.put(index, values);
    hooks.schedule(name, node, index);
    return new WasmLambdaPlan(walker.free, index, slots.get(index));
  }

  /**
   * Lexical-scope-aware AST walker that records every reference to a name not bound by an enclosing
   * scope and which resolves as a local or global in the surrounding context. Order-preserving:
   * free variables appear in the order they are first encountered.
   */
  private final class CaptureWalker {
    final Deque<Set<String>> scopes = new ArrayDeque<>();
    final List<String> free = new ArrayList<>();

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
      if (hooks.local(name) >= 0 || hooks.global(name)) {
        free.add(name);
      }
    }

    void walk(final ASTNode node) {
      if (node == null) {
        return;
      }
      switch (node) {
        case VariableReferenceNode reference -> {
          // For dotted access (`obj.field`) only the head is a variable reference;
          // subsequent parts are field names that don't exist as locals.
          if (!reference.hasPrefix()) {
            reference(reference.parts().getFirst());
          }
        }
        case FunctionCallNode call -> {
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
            // Guard runs in the pattern's binding scope.
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
          // Walk the initializer first so it sees only outer-scope bindings,
          // then bind the new local for any subsequent statements in the same
          // scope.
          walk(variable.initializer());
          bind(variable.name());
        }
        case AssignmentNode assign -> {
          // The LHS first part is the variable being mutated; treat it as a
          // reference for capture purposes (closures rarely mutate outer
          // scope, but tracking the read is harmless and avoids losing
          // captures for object/index mutations like `obj.field = ...`).
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
          // Nested lambda: walk its body inside a fresh scope holding only its
          // own parameters. Anything its body references that isn't bound
          // there nor in the enclosing scope stack becomes a free variable
          // for the OUTER lambda too — because the outer lambda is what
          // physically constructs the inner closure and must therefore have
          // those values available.
          push();
          for (final var param : nested.parameters()) {
            bind(param.name());
          }
          walk(nested.body());
          pop();
        }
        default -> {
          // Literals, declarations, and other leaves: nothing to walk for capture analysis.
        }
      }
    }
  }
}
