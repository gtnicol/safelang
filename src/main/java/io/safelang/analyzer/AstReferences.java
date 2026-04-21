package io.safelang.analyzer;

import io.safelang.ast.ASTNode;
import io.safelang.ast.AssignmentNode;
import io.safelang.ast.BinaryExpressionNode;
import io.safelang.ast.CaseExpressionNode;
import io.safelang.ast.DestructureNode;
import io.safelang.ast.DoExpressionNode;
import io.safelang.ast.ExpressionStatementNode;
import io.safelang.ast.FieldAccessNode;
import io.safelang.ast.ForStatementNode;
import io.safelang.ast.FunctionCallNode;
import io.safelang.ast.IfExpressionNode;
import io.safelang.ast.IndexAccessNode;
import io.safelang.ast.IndexAssignmentNode;
import io.safelang.ast.LambdaNode;
import io.safelang.ast.ListLiteralNode;
import io.safelang.ast.MapLiteralNode;
import io.safelang.ast.ObjectCreationNode;
import io.safelang.ast.ReturnNode;
import io.safelang.ast.SetLiteralNode;
import io.safelang.ast.StringInterpolationNode;
import io.safelang.ast.TupleLiteralNode;
import io.safelang.ast.UnaryExpressionNode;
import io.safelang.ast.VariableDeclarationNode;
import io.safelang.ast.WhileStatementNode;

/**
 * Pure AST traversal helpers used by the analyzer. Stateless, side-effect-free; safe to call from
 * any pass.
 */
final class AstReferences {

  private AstReferences() {}

  /**
   * True if {@code node} or any of its transitive children syntactically calls a function named
   * {@code name} (without a module prefix). Used by {@link TerminationChecker} to decide whether a
   * candidate base-case branch can in fact reach the function under analysis.
   *
   * <p>Recurses into lambda bodies, so a lambda that hides a recursive call to the enclosing
   * function still counts as containing that call.
   */
  static boolean contains(final ASTNode node, final String name) {
    if (node instanceof FunctionCallNode call) {
      if (!call.hasPrefix() && call.name().equals(name)) return true;
      for (final var argument : call.arguments()) {
        if (contains(argument, name)) return true;
      }
      return false;
    }
    if (node instanceof BinaryExpressionNode b) {
      return contains(b.left(), name) || contains(b.right(), name);
    }
    if (node instanceof UnaryExpressionNode u) {
      return contains(u.operand(), name);
    }
    if (node instanceof IfExpressionNode i) {
      if (contains(i.condition(), name)) return true;
      if (contains(i.then(), name)) return true;
      return i.hasOtherwise() && contains(i.otherwise(), name);
    }
    if (node instanceof CaseExpressionNode c) {
      if (contains(c.subject(), name)) return true;
      for (final var branch : c.branches()) {
        if (branch.hasGuard() && contains(branch.guard(), name)) return true;
        if (contains(branch.result(), name)) return true;
      }
      return c.hasFallback() && contains(c.fallback(), name);
    }
    if (node instanceof ReturnNode r) {
      return r.hasExpression() && contains(r.expression(), name);
    }
    if (node instanceof DoExpressionNode d) {
      for (final var statement : d.statements()) {
        if (contains(statement, name)) return true;
      }
      return contains(d.expression(), name);
    }
    if (node instanceof ExpressionStatementNode e) {
      return contains(e.expression(), name);
    }
    if (node instanceof ListLiteralNode l) {
      for (final var element : l.elements()) {
        if (contains(element, name)) return true;
      }
    }
    if (node instanceof TupleLiteralNode t) {
      for (final var element : t.elements()) {
        if (contains(element, name)) return true;
      }
    }
    if (node instanceof MapLiteralNode m) {
      for (final var entry : m.entries()) {
        if (contains(entry.key(), name) || contains(entry.value(), name)) return true;
      }
    }
    if (node instanceof ObjectCreationNode o) {
      for (final var field : o.fields()) {
        if (contains(field.value(), name)) return true;
      }
    }
    if (node instanceof LambdaNode l) {
      if (contains(l.body(), name)) return true;
    }
    if (node instanceof IndexAccessNode i) {
      return contains(i.container(), name) || contains(i.index(), name);
    }
    if (node instanceof ForStatementNode f) {
      if (contains(f.iterable(), name)) return true;
      for (final var statement : f.body()) {
        if (contains(statement, name)) return true;
      }
      return false;
    }
    if (node instanceof WhileStatementNode w) {
      if (contains(w.condition(), name)) return true;
      for (final var statement : w.body()) {
        if (contains(statement, name)) return true;
      }
      return false;
    }
    if (node instanceof VariableDeclarationNode v) {
      return v.hasInitializer() && contains(v.initializer(), name);
    }
    if (node instanceof AssignmentNode a) {
      return contains(a.value(), name);
    }
    if (node instanceof IndexAssignmentNode ia) {
      if (contains(ia.value(), name)) return true;
      for (final var index : ia.indices()) {
        if (contains(index, name)) return true;
      }
      return false;
    }
    if (node instanceof StringInterpolationNode s) {
      for (final var part : s.parts()) {
        if (contains(part, name)) return true;
      }
      return false;
    }
    if (node instanceof FieldAccessNode fa) {
      return contains(fa.receiver(), name);
    }
    if (node instanceof DestructureNode d) {
      return contains(d.initializer(), name);
    }
    if (node instanceof SetLiteralNode sl) {
      for (final var element : sl.elements()) {
        if (contains(element, name)) return true;
      }
      return false;
    }
    return false;
  }
}
