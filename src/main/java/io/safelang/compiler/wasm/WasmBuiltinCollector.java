package io.safelang.compiler.wasm;

import io.safelang.ast.*;
import io.safelang.runtime.BuiltinRegistry;
import java.util.Set;

final class WasmBuiltinCollector {

  void collect(final ASTNode node, final Set<String> builtins) {
    if (node == null) {
      return;
    }
    if (node instanceof FunctionCallNode call) {
      // Unqualified builtins, plus qualified module-owned builtins with no SAFE trampoline
      // (e.g. std:range), which dispatch to the same builtin stub.
      if (BuiltinRegistry.isBuiltin(call.name())
          && (call.prefix() == null || call.prefix().equals(BuiltinRegistry.module(call.name())))) {
        builtins.add(call.name());
      }
      for (final var argument : call.arguments()) {
        collect(argument, builtins);
      }
      return;
    }
    switch (node) {
      case ProgramNode program -> {
        for (final var declaration : program.declarations()) {
          collect(declaration, builtins);
        }
        for (final var statement : program.statements()) {
          collect(statement, builtins);
        }
      }
      case FunctionDeclarationNode function -> {
        for (final var parameter : function.parameters()) {
          if (parameter.hasDefault()) {
            collect(parameter.initial(), builtins);
          }
        }
        if (function.hasRequires()) {
          collect(function.requires(), builtins);
        }
        if (function.hasEnsures()) {
          collect(function.ensures(), builtins);
        }
        for (final var statement : function.body()) {
          collect(statement, builtins);
        }
      }
      case ReturnNode statement -> {
        if (statement.hasExpression()) {
          collect(statement.expression(), builtins);
        }
      }
      case ExpressionStatementNode statement -> collect(statement.expression(), builtins);
      case VariableDeclarationNode declaration -> {
        if (declaration.hasInitializer()) {
          collect(declaration.initializer(), builtins);
        }
      }
      case AssignmentNode assignment -> collect(assignment.value(), builtins);
      case BinaryExpressionNode expression -> {
        collect(expression.left(), builtins);
        collect(expression.right(), builtins);
      }
      case UnaryExpressionNode expression -> collect(expression.operand(), builtins);
      case IfExpressionNode expression -> {
        collect(expression.condition(), builtins);
        collect(expression.then(), builtins);
        if (expression.hasOtherwise()) {
          collect(expression.otherwise(), builtins);
        }
      }
      case CaseExpressionNode expression -> {
        collect(expression.subject(), builtins);
        for (final var branch : expression.branches()) {
          collect(branch.result(), builtins);
          if (branch.hasGuard()) {
            collect(branch.guard(), builtins);
          }
        }
        if (expression.hasFallback()) {
          collect(expression.fallback(), builtins);
        }
      }
      case ForStatementNode statement -> {
        collect(statement.iterable(), builtins);
        for (final var child : statement.body()) {
          collect(child, builtins);
        }
      }
      case WhileStatementNode statement -> {
        collect(statement.condition(), builtins);
        collect(statement.bound(), builtins);
        for (final var child : statement.body()) {
          collect(child, builtins);
        }
      }
      case DoExpressionNode expression -> {
        for (final var statement : expression.statements()) {
          collect(statement, builtins);
        }
        collect(expression.expression(), builtins);
      }
      case ObjectCreationNode creation -> {
        for (final var field : creation.fields()) {
          collect(field.value(), builtins);
        }
      }
      case FieldAccessNode access -> collect(access.receiver(), builtins);
      case IndexAccessNode access -> {
        collect(access.container(), builtins);
        collect(access.index(), builtins);
      }
      case IndexAssignmentNode assignment -> {
        collect(assignment.container(), builtins);
        for (final var index : assignment.indices()) {
          collect(index, builtins);
        }
        collect(assignment.value(), builtins);
      }
      case LambdaNode expression -> collect(expression.body(), builtins);
      case ListLiteralNode literal -> {
        for (final var element : literal.elements()) {
          collect(element, builtins);
        }
      }
      case MapLiteralNode literal -> {
        for (final var entry : literal.entries()) {
          collect(entry.key(), builtins);
          collect(entry.value(), builtins);
        }
      }
      case TupleLiteralNode literal -> {
        for (final var element : literal.elements()) {
          collect(element, builtins);
        }
      }
      case SetLiteralNode literal -> {
        for (final var element : literal.elements()) {
          collect(element, builtins);
        }
      }
      case StringInterpolationNode expression -> {
        for (final var part : expression.parts()) {
          collect(part, builtins);
        }
      }
      case DestructureNode declaration -> collect(declaration.initializer(), builtins);
      case RangeNode expression -> {
        collect(expression.start(), builtins);
        collect(expression.end(), builtins);
        if (expression.hasStep()) {
          collect(expression.step(), builtins);
        }
      }
      case AssertNode statement -> {
        collect(statement.condition(), builtins);
        if (statement.hasMessage()) {
          collect(statement.message(), builtins);
        }
      }
      default -> {}
    }
  }
}
