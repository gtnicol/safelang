package io.safelang.compiler.wasm;

import io.safelang.ast.*;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Walks an entire {@link ProgramNode} and collects every module prefix used in a {@code
 * prefix:name(...)} call.
 *
 * <p>The SAFE interpreter resolves transitive module access automatically: if a test imports {@code
 * test} and the {@code test} stdlib internally imports {@code io}, then the user's program can call
 * {@code io:println(...)} without an explicit {@code import io;}. To match that behaviour the WASM
 * compiler walks the program ahead of time and lazily imports any module it sees referenced via
 * {@code prefix:name(...)} but not declared explicitly.
 *
 * <p>This is intentionally a stand-alone walker rather than a visitor on {@link
 * AbstractASTVisitor}: the WASM compiler IS that visitor and we don't want a recursive call to
 * escape the per-expression-walk side-channel state it maintains.
 */
final class WasmPrefixCollector {

  private WasmPrefixCollector() {}

  /** Collect every module prefix used by a {@code prefix:name(...)} call. */
  static Set<String> collect(final ProgramNode program) {
    final var prefixes = new LinkedHashSet<String>();
    for (final var declaration : program.declarations()) {
      visit(declaration, prefixes);
    }
    for (final var statement : program.statements()) {
      visit(statement, prefixes);
    }
    return prefixes;
  }

  private static void visit(final ASTNode node, final Set<String> prefixes) {
    if (node == null) return;

    if (node instanceof FunctionCallNode call) {
      if (call.hasPrefix()) prefixes.add(call.prefix());
      for (final var argument : call.arguments()) visit(argument, prefixes);
      return;
    }
    if (node instanceof FunctionDeclarationNode function) {
      for (final var statement : function.body()) visit(statement, prefixes);
      return;
    }
    if (node instanceof BinaryExpressionNode binary) {
      visit(binary.left(), prefixes);
      visit(binary.right(), prefixes);
      return;
    }
    if (node instanceof UnaryExpressionNode unary) {
      visit(unary.operand(), prefixes);
      return;
    }
    if (node instanceof IfExpressionNode conditional) {
      visit(conditional.condition(), prefixes);
      visit(conditional.then(), prefixes);
      visit(conditional.otherwise(), prefixes);
      return;
    }
    if (node instanceof CaseExpressionNode cases) {
      visit(cases.subject(), prefixes);
      for (final var branch : cases.branches()) {
        visit(branch.result(), prefixes);
        if (branch.hasGuard()) visit(branch.guard(), prefixes);
      }
      visit(cases.fallback(), prefixes);
      return;
    }
    if (node instanceof ExpressionStatementNode expression) {
      visit(expression.expression(), prefixes);
      return;
    }
    if (node instanceof VariableDeclarationNode declaration) {
      visit(declaration.initializer(), prefixes);
      return;
    }
    if (node instanceof AssignmentNode assignment) {
      visit(assignment.value(), prefixes);
      return;
    }
    if (node instanceof ReturnNode statement) {
      visit(statement.expression(), prefixes);
      return;
    }
    if (node instanceof ForStatementNode statement) {
      visit(statement.iterable(), prefixes);
      for (final var child : statement.body()) visit(child, prefixes);
      return;
    }
    if (node instanceof WhileStatementNode statement) {
      visit(statement.condition(), prefixes);
      visit(statement.bound(), prefixes);
      for (final var child : statement.body()) visit(child, prefixes);
      return;
    }
    if (node instanceof DoExpressionNode block) {
      for (final var statement : block.statements()) visit(statement, prefixes);
      visit(block.expression(), prefixes);
      return;
    }
    if (node instanceof StringInterpolationNode interpolation) {
      for (final var part : interpolation.parts()) visit(part, prefixes);
      return;
    }
    if (node instanceof ListLiteralNode list) {
      for (final var element : list.elements()) visit(element, prefixes);
      return;
    }
    if (node instanceof TupleLiteralNode tuple) {
      for (final var element : tuple.elements()) visit(element, prefixes);
      return;
    }
    if (node instanceof SetLiteralNode set) {
      for (final var element : set.elements()) visit(element, prefixes);
      return;
    }
    if (node instanceof MapLiteralNode literal) {
      for (final var entry : literal.entries()) {
        visit(entry.key(), prefixes);
        visit(entry.value(), prefixes);
      }
      return;
    }
    if (node instanceof ObjectCreationNode creation) {
      for (final var field : creation.fields()) visit(field.value(), prefixes);
      return;
    }
    if (node instanceof FieldAccessNode access) {
      visit(access.receiver(), prefixes);
      return;
    }
    if (node instanceof IndexAccessNode access) {
      visit(access.container(), prefixes);
      visit(access.index(), prefixes);
      return;
    }
    if (node instanceof IndexAssignmentNode assignment) {
      visit(assignment.container(), prefixes);
      for (final var index : assignment.indices()) visit(index, prefixes);
      visit(assignment.value(), prefixes);
      return;
    }
    if (node instanceof LambdaNode lambda) {
      visit(lambda.body(), prefixes);
      return;
    }
    if (node instanceof RangeNode range) {
      visit(range.start(), prefixes);
      visit(range.end(), prefixes);
      if (range.hasStep()) visit(range.step(), prefixes);
      return;
    }
    if (node instanceof DestructureNode destructure) {
      visit(destructure.initializer(), prefixes);
      return;
    }
    if (node instanceof AssertNode statement) {
      visit(statement.condition(), prefixes);
      if (statement.hasMessage()) visit(statement.message(), prefixes);
    }
  }
}
