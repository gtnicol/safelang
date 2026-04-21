package io.safelang.analyzer;

import io.safelang.ast.ASTNode;
import io.safelang.ast.BinaryExpressionNode;
import io.safelang.ast.TypeNode;
import java.util.List;
import java.util.function.BiConsumer;

final class SemanticBinaryChecker {

  private final TypeResolver resolver;
  private final BiConsumer<String, ASTNode> error;

  SemanticBinaryChecker(final TypeResolver resolver, final BiConsumer<String, ASTNode> error) {
    this.resolver = resolver;
    this.error = error;
  }

  void check(
      final BinaryExpressionNode node,
      final String operator,
      final TypeNode left,
      final TypeNode right,
      final boolean leftInferred,
      final boolean rightInferred) {
    if (left == null || right == null) {
      return;
    }
    if (resolver.isArithmetic(operator) && !leftInferred && !rightInferred) {
      arithmetic(node, operator, left, right);
    } else if (resolver.isLogical(operator) && !leftInferred && !rightInferred) {
      if (!"boolean".equals(left.name())) {
        error.accept(
            "Operator '" + operator + "' requires boolean operands, got " + left.fullName(), node);
      }
      if (!"boolean".equals(right.name())) {
        error.accept(
            "Operator '" + operator + "' requires boolean operands, got " + right.fullName(), node);
      }
    } else if (resolver.isBitwise(operator) && !leftInferred && !rightInferred) {
      if (!resolver.isIntegral(left)) {
        error.accept(
            "Operator '" + operator + "' requires int or uint operands, got " + left.fullName(),
            node);
      }
      if (!resolver.isIntegral(right)) {
        error.accept(
            "Operator '" + operator + "' requires int or uint operands, got " + right.fullName(),
            node);
      }
    } else if ("in".equals(operator)) {
      inOperator(node, left, right);
    }
  }

  private void arithmetic(
      final BinaryExpressionNode node,
      final String operator,
      final TypeNode left,
      final TypeNode right) {
    if ("+".equals(operator)) {
      final boolean leftOk = "string".equals(left.name()) || resolver.isNumeric(left);
      final boolean rightOk = "string".equals(right.name()) || resolver.isNumeric(right);
      if (!leftOk) {
        error.accept(
            "Operator '+' requires numeric or string operands, got " + left.fullName(), node);
      } else if (!rightOk) {
        error.accept(
            "Operator '+' requires numeric or string operands, got " + right.fullName(), node);
      } else if (("int".equals(left.name()) && "uint".equals(right.name()))
          || ("uint".equals(left.name()) && "int".equals(right.name()))) {
        error.accept("Cannot mix int and uint in arithmetic — use explicit conversion", node);
      }
      return;
    }
    if (!resolver.isNumeric(left) || !resolver.isNumeric(right)) {
      error.accept("Operator '" + operator + "' requires numeric operands", node);
    } else if (("int".equals(left.name()) && "uint".equals(right.name()))
        || ("uint".equals(left.name()) && "int".equals(right.name()))) {
      error.accept("Cannot mix int and uint in arithmetic — use explicit conversion", node);
    }
  }

  private void inOperator(
      final BinaryExpressionNode node, final TypeNode left, final TypeNode right) {
    switch (right.name()) {
      case "list" -> matchCollection(node, left, right.parameters(), "list element");
      case "map" -> matchMap(node, left, right.parameters());
      case "set" -> matchCollection(node, left, right.parameters(), "set element");
      case "string" -> {
        if (!"string".equals(left.name())) {
          error.accept("'in' on string requires string left operand, got " + left.fullName(), node);
        }
      }
      default ->
          error.accept(
              "'in' operator requires list, map, set, or string on right side, got "
                  + right.fullName(),
              node);
    }
  }

  private void matchCollection(
      final BinaryExpressionNode node,
      final TypeNode left,
      final List<TypeNode> parameters,
      final String description) {
    if (parameters == null || parameters.isEmpty()) {
      return;
    }
    final var expected = parameters.get(0);
    if (!resolver.matches(expected, left)) {
      error.accept(
          "'"
              + description
              + " type mismatch: "
              + description
              + " is "
              + expected.fullName()
              + " but got "
              + left.fullName(),
          node);
    }
  }

  private void matchMap(
      final BinaryExpressionNode node, final TypeNode left, final List<TypeNode> parameters) {
    if (parameters == null || parameters.isEmpty()) {
      return;
    }
    final var expected = parameters.get(0);
    if (!resolver.matches(expected, left)) {
      error.accept(
          "'in' key type mismatch: map key is "
              + expected.fullName()
              + " but got "
              + left.fullName(),
          node);
    }
  }
}
