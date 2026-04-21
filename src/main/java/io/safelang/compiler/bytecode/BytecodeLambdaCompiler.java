package io.safelang.compiler.bytecode;

import io.safelang.ast.*;
import io.safelang.bytecode.*;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;

final class BytecodeLambdaCompiler {

  private final BytecodeLambdaContext context;
  private final Set<String> names;
  private final Set<String> functions;

  BytecodeLambdaCompiler(
      final BytecodeLambdaContext context, final Set<String> names, final Set<String> functions) {
    this.context = context;
    this.names = names;
    this.functions = functions;
  }

  void compile(final LambdaNode node) {
    final var enclosed = new HashMap<>(context.slots());

    final var referenced = new LinkedHashSet<String>();
    collect(node.body(), referenced);

    final var params = new HashSet<String>();
    for (final var param : node.parameters()) {
      params.add(param.name());
    }

    final var captures = new ArrayList<String>();
    final var origins = new ArrayList<Integer>();
    for (final var reference : referenced) {
      if (!params.contains(reference) && enclosed.containsKey(reference)) {
        captures.add(reference);
        origins.add(enclosed.get(reference));
      }
    }

    final var globals = new ArrayList<String>();
    for (final var reference : referenced) {
      if (!params.contains(reference)
          && !enclosed.containsKey(reference)
          && !captures.contains(reference)
          && names.contains(reference)
          && !functions.contains(reference)) {
        globals.add(reference);
      }
    }

    final var name = context.next();
    final var body = new ArrayList<ASTNode>();
    body.add(new ReturnNode(node.line(), node.column(), node.body()));

    final var index = context.add(name);
    final var position = context.reserve();
    context.register(name, position);
    final var arity = node.parameters().size();
    final var locals = arity + captures.size() + globals.size() + context.count(body);

    context.push();
    try {
      for (final var param : node.parameters()) {
        context.allocate(param.name());
      }
      for (final var capture : captures) {
        context.allocate(capture);
      }
      for (final var capture : globals) {
        context.allocate(capture);
      }
      for (final var statement : body) {
        context.compile(statement);
      }
      context.chunk().emitOpcode(OpCode.PUSH_VOID);
      context.chunk().emitOpcode(OpCode.RETURN);
      context.define(
          position,
          new FunctionDefinition(
              name, index, arity, locals, context.chunk().bytes(), null, null, null));
    } finally {
      context.pop();
    }

    for (final var origin : origins) {
      context.chunk().emitOpShort(OpCode.LOAD_LOCAL, origin);
    }
    for (final var capture : globals) {
      context.chunk().emitOpShort(OpCode.LOAD_GLOBAL, context.add(capture));
    }

    context.chunk().emitOpcode(OpCode.CLOSURE);
    context.chunk().emitShort(position);
    context.chunk().emitByte(captures.size() + globals.size());
  }

  private void collect(final ASTNode node, final Set<String> names) {
    if (node == null) {
      return;
    }
    switch (node) {
      case VariableReferenceNode reference -> names.add(reference.parts().get(0));
      case BinaryExpressionNode binary -> {
        collect(binary.left(), names);
        collect(binary.right(), names);
      }
      case UnaryExpressionNode unary -> collect(unary.operand(), names);
      case FunctionCallNode call -> {
        for (final var arg : call.arguments()) {
          collect(arg, names);
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
          if (branch.hasGuard()) {
            collect(branch.guard(), names);
          }
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
      case ExpressionStatementNode wrapper -> collect(wrapper.expression(), names);
      case VariableDeclarationNode declaration -> collect(declaration.initializer(), names);
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
        if (range.hasStep()) {
          collect(range.step(), names);
        }
      }
      case AssertNode assertion -> {
        collect(assertion.condition(), names);
        if (assertion.hasMessage()) {
          collect(assertion.message(), names);
        }
      }
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
      case DestructureNode destructure -> collect(destructure.initializer(), names);
      case AssignmentNode assignment -> {
        names.add(assignment.parts().get(0));
        collect(assignment.value(), names);
      }
      case IndexAssignmentNode assignment -> {
        collect(assignment.container(), names);
        for (final var index : assignment.indices()) {
          collect(index, names);
        }
        collect(assignment.value(), names);
      }
      case FieldAssignmentNode assignment -> collect(assignment.value(), names);
      default -> {}
    }
  }
}
