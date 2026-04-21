package io.safelang.ast;

/**
 * Pretty-printer for the AST that implements ASTVisitor. Produces a tree-structured representation
 * with indentation showing nesting.
 */
public class ASTPrinter implements ASTVisitor<String> {

  private int level = 0;

  @Override
  public String visitProgram(final ProgramNode node) {
    final var builder = new StringBuilder();
    builder.append(indent()).append("Program: ").append(node.name()).append("\n");
    level++;

    // Print imports
    for (final ImportNode imp : node.imports()) {
      builder.append(imp.accept(this));
    }

    // Print declarations
    for (final ASTNode declaration : node.declarations()) {
      builder.append(declaration.accept(this));
    }

    // Print statements
    for (final ASTNode statement : node.statements()) {
      builder.append(statement.accept(this));
    }

    level--;
    return builder.toString();
  }

  @Override
  public String visitImport(final ImportNode node) {
    if (node.isSelective()) {
      return indent()
          + "Import: "
          + node.module()
          + " {"
          + String.join(", ", node.symbols())
          + "}\n";
    }
    return indent() + "Import: " + node.module() + "\n";
  }

  @Override
  public String visitType(final TypeNode node) {
    return indent() + "Type: " + node.fullName() + "\n";
  }

  @Override
  public String visitTypeDeclaration(final TypeDeclarationNode node) {
    final var builder = new StringBuilder();
    builder.append(indent()).append("TypeDeclaration: ").append(node.name());

    // Show type parameters if present
    final var params = node.parameters();
    if (!params.isEmpty()) {
      builder.append("[");
      for (int i = 0; i < params.size(); i++) {
        if (i > 0) builder.append(", ");
        builder.append(params.get(i));
      }
      builder.append("]");
    }
    builder.append("\n");

    level++;
    for (final FieldDeclarationNode field : node.fields()) {
      builder.append(field.accept(this));
    }
    level--;
    return builder.toString();
  }

  @Override
  public String visitFieldDeclaration(final FieldDeclarationNode node) {
    return indent() + "Field: " + node.type().fullName() + " " + node.name() + "\n";
  }

  @Override
  public String visitFunctionDeclaration(final FunctionDeclarationNode node) {
    final var builder = new StringBuilder();
    builder
        .append(indent())
        .append("FunctionDeclaration: ")
        .append(node.returns().fullName())
        .append(" ")
        .append(node.name())
        .append("(");

    final var params = node.parameters();
    for (int i = 0; i < params.size(); i++) {
      if (i > 0) builder.append(", ");
      final var p = params.get(i);
      builder.append(p.type().fullName()).append(" ").append(p.name());
    }
    builder.append(")");

    // Show requires/ensures if present
    if (node.hasRequires()) {
      builder.append(" [requires]");
    }
    if (node.hasEnsures()) {
      builder.append(" [ensures]");
    }
    if (node.hasDecreases()) {
      builder.append(" [decreases]");
    }
    builder.append("\n");

    level++;

    // Print requires clause if present
    if (node.hasRequires()) {
      builder.append(indent()).append("Requires:\n");
      level++;
      builder.append(node.requires().accept(this));
      level--;
    }

    // Print ensures clause if present
    if (node.hasEnsures()) {
      builder.append(indent()).append("Ensures:\n");
      level++;
      builder.append(node.ensures().accept(this));
      level--;
    }

    // Print decreases clause if present
    if (node.hasDecreases()) {
      builder.append(indent()).append("Decreases:\n");
      level++;
      builder.append(node.decreases().accept(this));
      level--;
    }

    for (final ASTNode statement : node.body()) {
      builder.append(statement.accept(this));
    }
    level--;

    return builder.toString();
  }

  @Override
  public String visitParameter(final ParameterNode node) {
    return indent() + "Parameter: " + node.type().fullName() + " " + node.name() + "\n";
  }

  @Override
  public String visitVariableDeclaration(final VariableDeclarationNode node) {
    final var builder = new StringBuilder();
    builder.append(indent()).append("VariableDeclaration: ");

    // Show "const" prefix if isConst
    if (node.isConstant()) {
      builder.append("const ");
    }

    builder.append(node.type().fullName()).append(" ").append(node.name());

    if (node.hasInitializer()) {
      builder.append(" = \n");
      level++;
      builder.append(node.initializer().accept(this));
      level--;
    } else {
      builder.append("\n");
    }

    return builder.toString();
  }

  @Override
  public String visitAssignment(final AssignmentNode node) {
    final var builder = new StringBuilder();
    builder.append(indent()).append("Assignment: ");

    final var parts = node.parts();
    for (int i = 0; i < parts.size(); i++) {
      if (i > 0) builder.append(".");
      builder.append(parts.get(i));
    }
    builder.append(" = \n");

    level++;
    builder.append(node.value().accept(this));
    level--;

    return builder.toString();
  }

  @Override
  public String visitForStatement(final ForStatementNode node) {
    final var builder = new StringBuilder();
    builder.append(indent()).append("ForStatement: ").append(node.variable()).append(" in\n");

    level++;
    builder.append(node.iterable().accept(this));
    for (final ASTNode statement : node.body()) {
      builder.append(statement.accept(this));
    }
    level--;

    return builder.toString();
  }

  @Override
  public String visitWhileStatement(final WhileStatementNode node) {
    final var builder = new StringBuilder();
    builder.append(indent()).append("WhileStatement\n");

    level++;
    builder.append(indent()).append("Condition:\n");
    level++;
    builder.append(node.condition().accept(this));
    level--;

    builder.append(indent()).append("Bound:\n");
    level++;
    builder.append(node.bound().accept(this));
    level--;

    for (final ASTNode statement : node.body()) {
      builder.append(statement.accept(this));
    }
    level--;

    return builder.toString();
  }

  @Override
  public String visitReturn(final ReturnNode node) {
    final var builder = new StringBuilder();
    builder.append(indent()).append("Return");

    if (node.hasExpression()) {
      builder.append("\n");
      level++;
      builder.append(node.expression().accept(this));
      level--;
    } else {
      builder.append("\n");
    }

    return builder.toString();
  }

  @Override
  public String visitExpressionStatement(final ExpressionStatementNode node) {
    return indent() + "ExpressionStatement\n" + node.expression().accept(this);
  }

  @Override
  public String visitBinaryExpression(final BinaryExpressionNode node) {
    final var builder = new StringBuilder();
    builder.append(indent()).append("BinaryExpression: ").append(node.operator()).append("\n");

    level++;
    builder.append(node.left().accept(this));
    builder.append(node.right().accept(this));
    level--;

    return builder.toString();
  }

  @Override
  public String visitUnaryExpression(final UnaryExpressionNode node) {
    final var builder = new StringBuilder();
    builder.append(indent()).append("UnaryExpression: ").append(node.operator()).append("\n");

    level++;
    builder.append(node.operand().accept(this));
    level--;

    return builder.toString();
  }

  @Override
  public String visitIfExpression(final IfExpressionNode node) {
    final var builder = new StringBuilder();
    builder.append(indent()).append("IfExpression\n");

    level++;
    builder.append(indent()).append("Condition:\n");
    level++;
    builder.append(node.condition().accept(this));
    level--;

    builder.append(indent()).append("Then:\n");
    level++;
    builder.append(node.then().accept(this));
    level--;

    if (node.hasOtherwise()) {
      builder.append(indent()).append("Else:\n");
      level++;
      builder.append(node.otherwise().accept(this));
      level--;
    }
    level--;

    return builder.toString();
  }

  @Override
  public String visitCaseExpression(final CaseExpressionNode node) {
    final var builder = new StringBuilder();
    builder.append(indent()).append("CaseExpression\n");

    level++;
    builder.append(indent()).append("Subject:\n");
    level++;
    builder.append(node.subject().accept(this));
    level--;

    for (final CaseBranchNode branch : node.branches()) {
      builder.append(branch.accept(this));
    }

    if (node.hasFallback()) {
      builder.append(indent()).append("Default:\n");
      level++;
      builder.append(node.fallback().accept(this));
      level--;
    }
    level--;

    return builder.toString();
  }

  @Override
  public String visitCaseBranch(final CaseBranchNode node) {
    final var builder = new StringBuilder();
    if (node.isWildcard()) {
      builder.append(indent()).append("Case: _");
    } else {
      builder.append(indent()).append("Case: ");
      final var pattern = node.pattern();
      if (pattern instanceof LiteralNode literal) {
        builder.append(literalText(literal));
      } else if (pattern instanceof EnumPatternNode ep) {
        builder.append(ep.variant());
        if (ep.hasBindings()) {
          builder.append("(").append(String.join(", ", ep.bindings())).append(")");
        }
      }
    }

    if (node.hasGuard()) {
      builder.append(" if ");
    }
    builder.append("\n");

    level++;
    if (node.hasGuard()) {
      builder.append(indent()).append("Guard:\n");
      level++;
      builder.append(node.guard().accept(this));
      level--;
    }
    builder.append(node.result().accept(this));
    level--;

    return builder.toString();
  }

  @Override
  public String visitFunctionCall(final FunctionCallNode node) {
    final var builder = new StringBuilder();
    builder.append(indent()).append("FunctionCall: ");
    if (node.hasPrefix()) {
      builder.append(node.prefix()).append(":");
    }
    builder.append(node.name()).append("\n");

    if (!node.arguments().isEmpty()) {
      level++;
      for (final ASTNode arg : node.arguments()) {
        builder.append(arg.accept(this));
      }
      level--;
    }

    return builder.toString();
  }

  @Override
  public String visitVariableReference(final VariableReferenceNode node) {
    final var builder = new StringBuilder();
    final var parts = node.parts();
    builder.append(indent()).append("VariableReference: ");
    for (int i = 0; i < parts.size(); i++) {
      if (i > 0) builder.append(".");
      builder.append(parts.get(i));
    }
    builder.append("\n");
    return builder.toString();
  }

  @Override
  public String visitObjectCreation(final ObjectCreationNode node) {
    final var builder = new StringBuilder();
    builder.append(indent()).append("ObjectCreation: ").append(node.type()).append("\n");

    level++;
    for (final FieldAssignmentNode assign : node.fields()) {
      builder.append(assign.accept(this));
    }
    level--;

    return builder.toString();
  }

  @Override
  public String visitFieldAssignment(final FieldAssignmentNode node) {
    final var builder = new StringBuilder();
    builder.append(indent()).append("Field: ").append(node.field()).append(" = \n");

    level++;
    builder.append(node.value().accept(this));
    level--;

    return builder.toString();
  }

  @Override
  public String visitLiteral(final LiteralNode node) {
    return switch (node) {
      case LiteralNode.IntLiteral i -> indent() + "Literal(int): " + i.value() + "\n";
      case LiteralNode.UintLiteral u -> indent() + "Literal(uint): " + u.value() + "\n";
      case LiteralNode.FloatLiteral f -> indent() + "Literal(float): " + f.value() + "\n";
      case LiteralNode.BoolLiteral b -> indent() + "Literal(boolean): " + b.value() + "\n";
      case LiteralNode.StringLiteral s -> indent() + "Literal(string): \"" + s.value() + "\"\n";
    };
  }

  private static String literalText(final LiteralNode literal) {
    return switch (literal) {
      case LiteralNode.IntLiteral i -> Long.toString(i.value());
      case LiteralNode.UintLiteral u -> Long.toString(u.value());
      case LiteralNode.FloatLiteral f -> Double.toString(f.value());
      case LiteralNode.BoolLiteral b -> Boolean.toString(b.value());
      case LiteralNode.StringLiteral s -> s.value();
    };
  }

  @Override
  public String visitListLiteral(final ListLiteralNode node) {
    final var builder = new StringBuilder();
    builder.append(indent()).append("ListLiteral\n");

    level++;
    for (final ASTNode elem : node.elements()) {
      builder.append(elem.accept(this));
    }
    level--;

    return builder.toString();
  }

  @Override
  public String visitMapLiteral(final MapLiteralNode node) {
    final var builder = new StringBuilder();
    builder.append(indent()).append("MapLiteral\n");

    level++;
    for (final MapEntryNode entry : node.entries()) {
      builder.append(entry.accept(this));
    }
    level--;

    return builder.toString();
  }

  @Override
  public String visitMapEntry(final MapEntryNode node) {
    final var builder = new StringBuilder();
    builder.append(indent()).append("MapEntry\n");

    level++;
    builder.append(indent()).append("Key:\n");
    level++;
    builder.append(node.key().accept(this));
    level--;

    builder.append(indent()).append("Value:\n");
    level++;
    builder.append(node.value().accept(this));
    level--;

    level--;
    return builder.toString();
  }

  @Override
  public String visitAssert(final AssertNode node) {
    final var builder = new StringBuilder();
    builder.append(indent()).append("Assert\n");

    level++;
    builder.append(indent()).append("Condition:\n");
    level++;
    builder.append(node.condition().accept(this));
    level--;

    if (node.hasMessage()) {
      builder.append(indent()).append("Message:\n");
      level++;
      builder.append(node.message().accept(this));
      level--;
    }

    level--;
    return builder.toString();
  }

  @Override
  public String visitIndexAccess(final IndexAccessNode node) {
    final var builder = new StringBuilder();
    builder.append(indent()).append("IndexAccess\n");

    level++;
    builder.append(indent()).append("Container:\n");
    level++;
    builder.append(node.container().accept(this));
    level--;

    builder.append(indent()).append("Index:\n");
    level++;
    builder.append(node.index().accept(this));
    level--;

    level--;
    return builder.toString();
  }

  @Override
  public String visitIndexAssignment(final IndexAssignmentNode node) {
    final var builder = new StringBuilder();
    builder.append(indent()).append("IndexAssignment\n");

    level++;
    builder.append(indent()).append("Container:\n");
    level++;
    builder.append(node.container().accept(this));
    level--;

    builder.append(indent()).append("Indices:\n");
    level++;
    for (final ASTNode index : node.indices()) {
      builder.append(index.accept(this));
    }
    level--;

    builder.append(indent()).append("Value:\n");
    level++;
    builder.append(node.value().accept(this));
    level--;

    level--;
    return builder.toString();
  }

  @Override
  public String visitEnumDeclaration(final EnumDeclarationNode node) {
    final var builder = new StringBuilder();
    builder.append(indent()).append("EnumDeclaration: ").append(node.name());

    final var parameters = node.parameters();
    if (!parameters.isEmpty()) {
      builder.append("[");
      for (int i = 0; i < parameters.size(); i++) {
        if (i > 0) builder.append(", ");
        builder.append(parameters.get(i));
      }
      builder.append("]");
    }
    builder.append("\n");

    level++;
    for (final EnumVariantNode variant : node.variants()) {
      builder.append(variant.accept(this));
    }
    level--;

    return builder.toString();
  }

  @Override
  public String visitEnumVariant(final EnumVariantNode node) {
    final var builder = new StringBuilder();
    builder.append(indent()).append("EnumVariant: ").append(node.name());

    if (node.hasFields()) {
      builder.append("(");
      final var fields = node.fields();
      for (int i = 0; i < fields.size(); i++) {
        if (i > 0) builder.append(", ");
        builder.append(fields.get(i).fullName());
      }
      builder.append(")");
    }
    builder.append("\n");

    return builder.toString();
  }

  @Override
  public String visitEnumPattern(final EnumPatternNode node) {
    final var builder = new StringBuilder();
    builder.append(indent()).append("EnumPattern: ").append(node.variant());

    if (node.hasBindings()) {
      builder.append("(");
      final var bindings = node.bindings();
      for (int i = 0; i < bindings.size(); i++) {
        if (i > 0) builder.append(", ");
        builder.append(bindings.get(i));
      }
      builder.append(")");
    }
    builder.append("\n");

    return builder.toString();
  }

  @Override
  public String visitStringInterpolation(final StringInterpolationNode node) {
    final var builder = new StringBuilder();
    builder.append(indent()).append("StringInterpolation\n");

    level++;
    for (final ASTNode part : node.parts()) {
      builder.append(part.accept(this));
    }
    level--;

    return builder.toString();
  }

  @Override
  public String visitTupleLiteral(final TupleLiteralNode node) {
    final var builder = new StringBuilder();
    builder.append(indent()).append("TupleLiteral\n");
    level++;
    for (final ASTNode element : node.elements()) {
      builder.append(element.accept(this));
    }
    level--;
    return builder.toString();
  }

  @Override
  public String visitSetLiteral(final SetLiteralNode node) {
    final var builder = new StringBuilder();
    builder.append(indent()).append("SetLiteral\n");

    level++;
    for (final ASTNode element : node.elements()) {
      builder.append(element.accept(this));
    }
    level--;

    return builder.toString();
  }

  @Override
  public String visitLambda(final LambdaNode node) {
    final var builder = new StringBuilder();
    builder.append(indent()).append("Lambda(");
    for (int i = 0; i < node.parameters().size(); i++) {
      if (i > 0) builder.append(", ");
      final var param = node.parameters().get(i);
      if (param.type() != null) {
        builder.append(param.type().fullName()).append(" ");
      }
      builder.append(param.name());
    }
    builder.append(")\n");
    level++;
    builder.append(node.body().accept(this));
    level--;
    return builder.toString();
  }

  @Override
  public String visitDoExpression(final DoExpressionNode node) {
    final var builder = new StringBuilder();
    builder.append(indent()).append("Do\n");
    level++;
    for (final ASTNode statement : node.statements()) {
      builder.append(statement.accept(this));
    }
    builder.append(indent()).append("Result:\n");
    level++;
    builder.append(node.expression().accept(this));
    level--;
    level--;
    return builder.toString();
  }

  @Override
  public String visitRange(final RangeNode node) {
    final var builder = new StringBuilder();
    builder.append(indent()).append("Range\n");
    level++;
    builder.append(indent()).append("Start:\n");
    level++;
    builder.append(node.start().accept(this));
    level--;
    builder.append(indent()).append("End:\n");
    level++;
    builder.append(node.end().accept(this));
    level--;
    if (node.hasStep()) {
      builder.append(indent()).append("Step:\n");
      level++;
      builder.append(node.step().accept(this));
      level--;
    }
    level--;
    return builder.toString();
  }

  @Override
  public String visitTypeAlias(final TypeAliasNode node) {
    return indent() + "TypeAlias(" + node.name() + " = " + node.target().fullName() + ")\n";
  }

  @Override
  public String visitFieldAccess(final FieldAccessNode node) {
    final var builder = new StringBuilder();
    builder.append(indent()).append("FieldAccess: .").append(node.field()).append("\n");
    level++;
    builder.append(node.receiver().accept(this));
    level--;
    return builder.toString();
  }

  @Override
  public String visitDestructure(final DestructureNode node) {
    final var builder = new StringBuilder();
    builder.append(indent()).append("Destructure: ");

    if (node.isConstant()) {
      builder.append("const ");
    }

    if (node.type() != null) {
      builder.append(node.type().fullName()).append(" ");
    }

    builder.append("(").append(String.join(", ", node.names())).append(") =\n");

    level++;
    builder.append(node.initializer().accept(this));
    level--;

    return builder.toString();
  }

  private String indent() {
    return "  ".repeat(Math.max(0, level));
  }
}
