package io.safelang.parser;

import io.safelang.ast.*;
import io.safelang.parser.generated.SAFEGrammarBaseVisitor;
import io.safelang.parser.generated.SAFEGrammarLexer;
import io.safelang.parser.generated.SAFEGrammarParser;
import io.safelang.parser.generated.SAFEGrammarParser.*;
import java.util.ArrayList;
import java.util.List;
import org.antlr.v4.runtime.*;
import org.antlr.v4.runtime.Token;

public class ASTBuilder extends SAFEGrammarBaseVisitor<ASTNode> {

  @Override
  public ASTNode visitProgram(final ProgramContext context) {
    final var header = context.header();
    final var line = header.getStart().getLine();
    final var column = header.getStart().getCharPositionInLine();

    String type;
    String name;
    if (header instanceof ProgramDeclarationContext program) {
      type = "program";
      name = program.IDENTIFIER().getText();
    } else if (header instanceof ModuleDeclarationHeaderContext module) {
      type = "module";
      name = module.IDENTIFIER().getText();
    } else {
      throw new ParserException("Expected program or module header", line, column);
    }

    final var imports = new ArrayList<ImportNode>();
    for (final var i : context.imports) {
      imports.add((ImportNode) visit(i));
    }

    final var declarations = new ArrayList<ASTNode>();
    for (final var declaration : context.declarations) {
      declarations.add(visit(declaration));
    }

    final var statements = new ArrayList<ASTNode>();
    for (final var statement : context.statements) {
      statements.add(visit(statement));
    }

    return new ProgramNode(line, column, type, name, imports, declarations, statements);
  }

  @Override
  public ASTNode visitImportStatement(final ImportStatementContext context) {
    final var line = context.getStart().getLine();
    final var column = context.getStart().getCharPositionInLine();
    final var identifiers = context.IDENTIFIER();
    final var module = identifiers.getFirst().getText();

    if (identifiers.size() > 1) {
      final var symbols = new ArrayList<String>();
      for (int i = 1; i < identifiers.size(); i++) {
        symbols.add(identifiers.get(i).getText());
      }
      return new ImportNode(line, column, module, symbols);
    }
    return new ImportNode(line, column, module);
  }

  @Override
  public ASTNode visitDeclaration(final DeclarationContext context) {
    if (context.typeDeclaration() != null) return visit(context.typeDeclaration());
    if (context.functionDeclaration() != null) return visit(context.functionDeclaration());
    if (context.enumDeclaration() != null) return visit(context.enumDeclaration());
    if (context.constDeclaration() != null) return visit(context.constDeclaration());
    return visit(context.typeAlias());
  }

  @Override
  public ASTNode visitConstDeclaration(final SAFEGrammarParser.ConstDeclarationContext context) {
    final var line = context.getStart().getLine();
    final var column = context.getStart().getCharPositionInLine();
    final var type = typed(context.type());
    final var name = context.id.getText();
    final var initializer = visit(context.expression());
    return new VariableDeclarationNode(line, column, type, name, initializer, true);
  }

  @Override
  public ASTNode visitTypeDeclaration(final TypeDeclarationContext context) {
    final var line = context.getStart().getLine();
    final var column = context.getStart().getCharPositionInLine();
    final var name = context.IDENTIFIER().getText();
    final var visible = context.visibility() != null && context.visibility().PUBLIC() != null;

    final var parameters = new ArrayList<String>();
    if (context.typeParameters() != null) {
      for (final var id : context.typeParameters().IDENTIFIER()) {
        parameters.add(id.getText());
      }
    }

    final var fields = new ArrayList<FieldDeclarationNode>();
    for (final var field : context.fieldDeclaration()) {
      fields.add((FieldDeclarationNode) visit(field));
    }

    return new TypeDeclarationNode(line, column, name, parameters, fields, visible);
  }

  @Override
  public ASTNode visitFieldDeclaration(final FieldDeclarationContext context) {
    final var line = context.getStart().getLine();
    final var column = context.getStart().getCharPositionInLine();
    final var type = typed(context.type());
    final var name = context.id.getText();
    final var constant = context.CONST() != null;
    final var visible = context.visibility() != null && context.visibility().PUBLIC() != null;
    return new FieldDeclarationNode(line, column, type, name, constant, visible);
  }

  @Override
  public ASTNode visitFunctionDeclaration(final FunctionDeclarationContext context) {
    final var line = context.getStart().getLine();
    final var column = context.getStart().getCharPositionInLine();
    final var returns = typed(context.type());
    final var name = context.id.getText();
    final var visible = context.visibility() != null && context.visibility().PUBLIC() != null;

    final var parameters = new ArrayList<ParameterNode>();
    if (context.parameters() != null) {
      for (final var parameter : context.parameters().parameter()) {
        parameters.add((ParameterNode) visit(parameter));
      }
    }

    ASTNode requires = null;
    if (context.requiresClause() != null) {
      requires = visit(context.requiresClause().expression());
    }

    ASTNode ensures = null;
    if (context.ensuresClause() != null) {
      ensures = visit(context.ensuresClause().expression());
    }

    ASTNode decreases = null;
    if (context.decreasesClause() != null) {
      decreases = visit(context.decreasesClause().expression());
    }

    final var body = new ArrayList<ASTNode>();
    for (final var statement : context.body().bodyStatement()) {
      body.add(visit(statement));
    }

    return new FunctionDeclarationNode(
        line, column, returns, name, parameters, requires, ensures, decreases, body, visible);
  }

  @Override
  public ASTNode visitParameter(final ParameterContext context) {
    final var line = context.getStart().getLine();
    final var column = context.getStart().getCharPositionInLine();
    final var type = typed(context.type());
    final var name = context.id.getText();
    final var constant = context.CONST() != null;
    ASTNode initial = null;
    if (context.expression() != null) {
      initial = visit(context.expression());
    }
    return new ParameterNode(line, column, type, name, constant, initial);
  }

  @Override
  public ASTNode visitEnumDeclaration(final EnumDeclarationContext context) {
    final var line = context.getStart().getLine();
    final var column = context.getStart().getCharPositionInLine();
    final var name = context.IDENTIFIER().getText();
    final var visible = context.visibility() != null && context.visibility().PUBLIC() != null;

    final var parameters = new ArrayList<String>();
    if (context.typeParameters() != null) {
      for (final var id : context.typeParameters().IDENTIFIER()) {
        parameters.add(id.getText());
      }
    }

    final var variants = new ArrayList<EnumVariantNode>();
    for (final var variant : context.enumVariant()) {
      variants.add((EnumVariantNode) visit(variant));
    }

    return new EnumDeclarationNode(line, column, name, parameters, variants, visible);
  }

  @Override
  public ASTNode visitEnumVariant(final EnumVariantContext context) {
    final var line = context.getStart().getLine();
    final var column = context.getStart().getCharPositionInLine();
    final var name = context.IDENTIFIER().getText();

    final var fields = new ArrayList<TypeNode>();
    for (final var type : context.type()) {
      fields.add(typed(type));
    }

    return new EnumVariantNode(line, column, name, fields);
  }

  @Override
  public ASTNode visitTypeAlias(final TypeAliasContext context) {
    final var line = context.getStart().getLine();
    final var column = context.getStart().getCharPositionInLine();
    final var name = context.IDENTIFIER().getText();
    final var visible = context.visibility() != null && context.visibility().PUBLIC() != null;
    final var target = typed(context.type());
    return new TypeAliasNode(line, column, name, target, visible);
  }

  @Override
  public ASTNode visitStatement(final StatementContext context) {
    if (context.variableDeclaration() != null) return visit(context.variableDeclaration());
    if (context.destructureDeclaration() != null) return visit(context.destructureDeclaration());
    if (context.assignmentStatement() != null) return visit(context.assignmentStatement());
    if (context.indexAssignmentStatement() != null)
      return visit(context.indexAssignmentStatement());
    if (context.forStatement() != null) return visit(context.forStatement());
    if (context.whileStatement() != null) return visit(context.whileStatement());
    if (context.assertStatement() != null) return visit(context.assertStatement());
    return visit(context.expressionStatement());
  }

  @Override
  public ASTNode visitBodyStatement(final BodyStatementContext context) {
    if (context.returnStatement() != null) return visit(context.returnStatement());
    return visit(context.statement());
  }

  @Override
  public ASTNode visitVariableDeclaration(final VariableDeclarationContext context) {
    final var line = context.getStart().getLine();
    final var column = context.getStart().getCharPositionInLine();
    final var type = typed(context.type());
    final var name = context.id.getText();
    final var constant = context.CONST() != null;

    ASTNode initializer = null;
    if (context.expression() != null) {
      initializer = visit(context.expression());
    }

    return new VariableDeclarationNode(line, column, type, name, initializer, constant);
  }

  @Override
  public ASTNode visitDestructureDeclaration(
      final SAFEGrammarParser.DestructureDeclarationContext context) {
    final var line = context.getStart().getLine();
    final var column = context.getStart().getCharPositionInLine();
    final var constant = context.CONST() != null;

    TypeNode type = null;
    if (context.type() != null) {
      type = typed(context.type());
    }

    final var names = new ArrayList<String>();
    for (final var id : context.IDENTIFIER()) {
      names.add(id.getText());
    }

    final var initializer = visit(context.expression());
    return new DestructureNode(line, column, type, names, initializer, constant);
  }

  @Override
  public ASTNode visitAssignmentStatement(final AssignmentStatementContext context) {
    final var line = context.getStart().getLine();
    final var column = context.getStart().getCharPositionInLine();
    final var parts = parts(context.qualifiedName());
    final var expression = visit(context.expression());
    return new AssignmentNode(line, column, parts, expression);
  }

  @Override
  public ASTNode visitIndexAssignmentStatement(final IndexAssignmentStatementContext context) {
    final var line = context.getStart().getLine();
    final var column = context.getStart().getCharPositionInLine();
    final var parts = parts(context.qualifiedName());
    final var container = new VariableReferenceNode(line, column, parts);

    final var indices = new ArrayList<ASTNode>();
    for (final var access : context.indexAccess()) {
      indices.add(visit(access.expression()));
    }

    final var expression = visit(context.expression());
    return new IndexAssignmentNode(line, column, container, indices, expression);
  }

  @Override
  public ASTNode visitForStatement(final ForStatementContext context) {
    final var line = context.getStart().getLine();
    final var column = context.getStart().getCharPositionInLine();
    final var variable = context.IDENTIFIER().getText();
    final var iterable = visit(context.expression());

    final var body = new ArrayList<ASTNode>();
    for (final var statement : context.block().statement()) {
      body.add(visit(statement));
    }

    return new ForStatementNode(line, column, variable, iterable, body);
  }

  @Override
  public ASTNode visitWhileStatement(final SAFEGrammarParser.WhileStatementContext context) {
    final var line = context.getStart().getLine();
    final var column = context.getStart().getCharPositionInLine();
    final var condition = visit(context.condition);
    final var bound = visit(context.bound);

    final var body = new ArrayList<ASTNode>();
    for (final var statement : context.block().statement()) {
      body.add(visit(statement));
    }

    return new WhileStatementNode(line, column, condition, bound, body);
  }

  @Override
  public ASTNode visitAssertStatement(final AssertStatementContext context) {
    final var line = context.getStart().getLine();
    final var column = context.getStart().getCharPositionInLine();
    final var condition = visit(context.expression(0));

    ASTNode message = null;
    if (context.expression().size() > 1) {
      message = visit(context.expression(1));
    }

    return new AssertNode(line, column, condition, message);
  }

  @Override
  public ASTNode visitReturnStatement(final ReturnStatementContext context) {
    final var line = context.getStart().getLine();
    final var column = context.getStart().getCharPositionInLine();

    ASTNode expression = null;
    if (context.expression() != null) {
      expression = visit(context.expression());
    }

    return new ReturnNode(line, column, expression);
  }

  @Override
  public ASTNode visitExpressionStatement(final ExpressionStatementContext context) {
    final var line = context.getStart().getLine();
    final var column = context.getStart().getCharPositionInLine();
    return new ExpressionStatementNode(line, column, visit(context.expression()));
  }

  @Override
  public ASTNode visitExpression(final ExpressionContext context) {
    if (context.ifExpression() != null) return visit(context.ifExpression());
    if (context.caseExpression() != null) return visit(context.caseExpression());
    if (context.doExpression() != null) return visit(context.doExpression());
    if (context.lambdaExpression() != null) return visit(context.lambdaExpression());
    return visit(context.orExpression());
  }

  @Override
  public ASTNode visitDoExpression(final DoExpressionContext context) {
    final var line = context.getStart().getLine();
    final var column = context.getStart().getCharPositionInLine();
    final var statements = new ArrayList<ASTNode>();
    for (final var statement : context.statement()) {
      statements.add(visit(statement));
    }
    final var expression = visit(context.expression());
    return new DoExpressionNode(line, column, statements, expression);
  }

  @Override
  public ASTNode visitLambdaExpression(final LambdaExpressionContext context) {
    final var line = context.getStart().getLine();
    final var column = context.getStart().getCharPositionInLine();
    final var parameters = new ArrayList<ParameterNode>();
    if (context.lambdaParams() != null) {
      for (final var param : context.lambdaParams().lambdaParam()) {
        final var row = param.getStart().getLine();
        final var position = param.getStart().getCharPositionInLine();
        if (param.type() != null) {
          // Typed parameter: fn(int x, string y) -> ...
          final var type = typed(param.type());
          final var name = param.IDENTIFIER().getText();
          parameters.add(new ParameterNode(row, position, type, name));
        } else {
          // Untyped parameter: fn(x, y) -> ... — type inferred later
          final var name = param.IDENTIFIER().getText();
          parameters.add(new ParameterNode(row, position, null, name));
        }
      }
    }
    final var body = visit(context.expression());
    return new LambdaNode(line, column, parameters, body);
  }

  @Override
  public ASTNode visitIfExpression(final IfExpressionContext context) {
    final var line = context.getStart().getLine();
    final var column = context.getStart().getCharPositionInLine();
    final var condition = visit(context.condition);
    final var then = visit(context.then);

    ASTNode otherwise = null;
    if (context.else_ != null) {
      otherwise = visit(context.else_);
    }

    return new IfExpressionNode(line, column, condition, then, otherwise);
  }

  @Override
  public ASTNode visitCaseExpression(final CaseExpressionContext context) {
    final var line = context.getStart().getLine();
    final var column = context.getStart().getCharPositionInLine();
    final var subject = visit(context.expression());

    final var branches = new ArrayList<CaseBranchNode>();
    for (final var branch : context.caseBranch()) {
      branches.add((CaseBranchNode) visit(branch));
    }

    ASTNode fallback = null;
    if (context.defaultBranch() != null) {
      fallback = visit(context.defaultBranch().expression());
    }

    return new CaseExpressionNode(line, column, subject, branches, fallback);
  }

  @Override
  public ASTNode visitCaseBranch(final CaseBranchContext context) {
    final var line = context.getStart().getLine();
    final var column = context.getStart().getCharPositionInLine();
    final var pattern = visit(context.pattern());
    final var result = visit(context.expression());

    ASTNode guard = null;
    if (context.guard != null) {
      guard = visit(context.guard);
    }

    if (pattern == null) {
      return new CaseBranchNode(line, column, null, result, true, guard);
    }
    return new CaseBranchNode(line, column, pattern, result, false, guard);
  }

  @Override
  public ASTNode visitWildcardPattern(final WildcardPatternContext context) {
    return null; // signal to visitCaseBranch that it's a wildcard
  }

  @Override
  public ASTNode visitLiteralPattern(final LiteralPatternContext context) {
    return visit(context.literal());
  }

  @Override
  public ASTNode visitEnumUnitPattern(final EnumUnitPatternContext context) {
    final var line = context.getStart().getLine();
    final var column = context.getStart().getCharPositionInLine();
    return new EnumPatternNode(line, column, context.IDENTIFIER().getText(), new ArrayList<>());
  }

  @Override
  public ASTNode visitEnumDestructurePattern(final EnumDestructurePatternContext context) {
    final var line = context.getStart().getLine();
    final var column = context.getStart().getCharPositionInLine();
    final var identifiers = context.IDENTIFIER();
    final var variant = identifiers.getFirst().getText();

    final var bindings = new ArrayList<String>();
    for (int i = 1; i < identifiers.size(); i++) {
      bindings.add(identifiers.get(i).getText());
    }

    return new EnumPatternNode(line, column, variant, bindings);
  }

  @Override
  public ASTNode visitOrExpression(final OrExpressionContext context) {
    var result = visit(context.left);
    for (final var operand : context.right) {
      final var line = operand.getStart().getLine();
      final var column = operand.getStart().getCharPositionInLine();
      result = new BinaryExpressionNode(line, column, result, "||", visit(operand));
    }
    return result;
  }

  @Override
  public ASTNode visitAndExpression(final AndExpressionContext context) {
    var result = visit(context.left);
    for (final var operand : context.right) {
      final var line = operand.getStart().getLine();
      final var column = operand.getStart().getCharPositionInLine();
      result = new BinaryExpressionNode(line, column, result, "&&", visit(operand));
    }
    return result;
  }

  @Override
  public ASTNode visitBitwiseOrExpression(
      final SAFEGrammarParser.BitwiseOrExpressionContext context) {
    var result = visit(context.left);
    for (final var operand : context.right) {
      final var line = operand.getStart().getLine();
      final var column = operand.getStart().getCharPositionInLine();
      result = new BinaryExpressionNode(line, column, result, "|", visit(operand));
    }
    return result;
  }

  @Override
  public ASTNode visitBitwiseXorExpression(
      final SAFEGrammarParser.BitwiseXorExpressionContext context) {
    var result = visit(context.left);
    for (final var operand : context.right) {
      final var line = operand.getStart().getLine();
      final var column = operand.getStart().getCharPositionInLine();
      result = new BinaryExpressionNode(line, column, result, "^", visit(operand));
    }
    return result;
  }

  @Override
  public ASTNode visitBitwiseAndExpression(
      final SAFEGrammarParser.BitwiseAndExpressionContext context) {
    var result = visit(context.left);
    for (final var operand : context.right) {
      final var line = operand.getStart().getLine();
      final var column = operand.getStart().getCharPositionInLine();
      result = new BinaryExpressionNode(line, column, result, "&", visit(operand));
    }
    return result;
  }

  @Override
  public ASTNode visitValueExpression(final ValueExpressionContext context) {
    if (context.comparisonExpression() != null) return visit(context.comparisonExpression());
    if (context.equalityExpression() != null) return visit(context.equalityExpression());
    if (context.inExpression() != null) return visit(context.inExpression());
    if (context.rangeExpression() != null) return visit(context.rangeExpression());
    if (context.shiftExpression() != null) return visit(context.shiftExpression());
    return visit(context.arithmeticExpression());
  }

  @Override
  public ASTNode visitRangeExpression(final RangeExpressionContext context) {
    final var line = context.getStart().getLine();
    final var column = context.getStart().getCharPositionInLine();
    if (context.step != null) {
      return new RangeNode(
          line, column, visit(context.left), visit(context.right), visit(context.step));
    }
    return new RangeNode(line, column, visit(context.left), visit(context.right));
  }

  @Override
  public ASTNode visitShiftLeft(final SAFEGrammarParser.ShiftLeftContext context) {
    final var line = context.getStart().getLine();
    final var column = context.getStart().getCharPositionInLine();
    return new BinaryExpressionNode(line, column, visit(context.left), "<<", visit(context.right));
  }

  @Override
  public ASTNode visitShiftRight(final SAFEGrammarParser.ShiftRightContext context) {
    final var line = context.getStart().getLine();
    final var column = context.getStart().getCharPositionInLine();
    return new BinaryExpressionNode(line, column, visit(context.left), ">>", visit(context.right));
  }

  @Override
  public ASTNode visitComparisonExpression(final ComparisonExpressionContext context) {
    final var line = context.op.getLine();
    final var column = context.op.getCharPositionInLine();
    return new BinaryExpressionNode(
        line, column, visit(context.left), context.op.getText(), visit(context.right));
  }

  @Override
  public ASTNode visitEqualityExpression(final EqualityExpressionContext context) {
    final var line = context.op.getLine();
    final var column = context.op.getCharPositionInLine();
    return new BinaryExpressionNode(
        line, column, visit(context.left), context.op.getText(), visit(context.right));
  }

  @Override
  public ASTNode visitInExpression(final InExpressionContext context) {
    final var line = context.getStart().getLine();
    final var column = context.getStart().getCharPositionInLine();
    return new BinaryExpressionNode(line, column, visit(context.left), "in", visit(context.right));
  }

  @Override
  public ASTNode visitArithmeticExpression(final ArithmeticExpressionContext context) {
    if (context.op != null) {
      final var line = context.op.getLine();
      final var column = context.op.getCharPositionInLine();
      return new BinaryExpressionNode(
          line, column, visit(context.left), context.op.getText(), visit(context.right));
    }
    return visit(context.unaryExpression());
  }

  @Override
  public ASTNode visitUnaryValueExpression(final UnaryValueExpressionContext context) {
    return visit(context.postfixExpression());
  }

  @Override
  public ASTNode visitUnaryNotExpression(final UnaryNotExpressionContext context) {
    final var line = context.getStart().getLine();
    final var column = context.getStart().getCharPositionInLine();
    return new UnaryExpressionNode(line, column, "!", visit(context.postfixExpression()));
  }

  @Override
  public ASTNode visitUnaryNegationExpression(final UnaryNegationExpressionContext context) {
    final var line = context.getStart().getLine();
    final var column = context.getStart().getCharPositionInLine();
    return new UnaryExpressionNode(line, column, "-", visit(context.postfixExpression()));
  }

  @Override
  public ASTNode visitUnaryBitwiseNotExpression(
      final SAFEGrammarParser.UnaryBitwiseNotExpressionContext context) {
    final var line = context.getStart().getLine();
    final var column = context.getStart().getCharPositionInLine();
    return new UnaryExpressionNode(line, column, "~", visit(context.postfixExpression()));
  }

  @Override
  public ASTNode visitPostfixExpression(final PostfixExpressionContext context) {
    var result = visit(context.primaryExpression());

    for (final var operation : context.postfixOp()) {
      if (operation instanceof IndexOpContext index) {
        final var line = index.getStart().getLine();
        final var column = index.getStart().getCharPositionInLine();
        result = new IndexAccessNode(line, column, result, visit(index.expression()));
      } else if (operation instanceof FieldAccessOpContext field) {
        final var line = field.getStart().getLine();
        final var column = field.getStart().getCharPositionInLine();
        result = new FieldAccessNode(line, column, result, field.IDENTIFIER().getText());
      }
    }

    return result;
  }

  @Override
  public ASTNode visitPrimaryVariableReference(final PrimaryVariableReferenceContext context) {
    return visit(context.variableReference());
  }

  @Override
  public ASTNode visitPrimaryFunctionCall(final PrimaryFunctionCallContext context) {
    return visit(context.functionCall());
  }

  @Override
  public ASTNode visitPrimaryObjectCreation(final PrimaryObjectCreationContext context) {
    return visit(context.objectCreation());
  }

  @Override
  public ASTNode visitPrimaryTupleLiteral(final PrimaryTupleLiteralContext context) {
    final var line = context.getStart().getLine();
    final var column = context.getStart().getCharPositionInLine();
    final var elements = new ArrayList<ASTNode>();
    for (final var expression : context.expression()) {
      elements.add(visit(expression));
    }
    return new TupleLiteralNode(line, column, elements);
  }

  @Override
  public ASTNode visitPrimaryGroupedExpression(final PrimaryGroupedExpressionContext context) {
    return visit(context.expression());
  }

  @Override
  public ASTNode visitPrimaryLiteralExpression(final PrimaryLiteralExpressionContext context) {
    return visit(context.literal());
  }

  @Override
  public ASTNode visitFunctionCall(final FunctionCallContext context) {
    final var line = context.getStart().getLine();
    final var column = context.getStart().getCharPositionInLine();
    final var name = context.functionName().name().IDENTIFIER().getText();

    String prefix = null;
    if (context.functionName().IDENTIFIER() != null) {
      prefix = context.functionName().IDENTIFIER().getText();
    }

    final var arguments = new ArrayList<ASTNode>();
    if (context.argumentList() != null) {
      for (final var expression : context.argumentList().expression()) {
        arguments.add(visit(expression));
      }
    }

    return new FunctionCallNode(line, column, prefix, name, arguments);
  }

  @Override
  public ASTNode visitObjectCreation(final ObjectCreationContext context) {
    final var line = context.getStart().getLine();
    final var column = context.getStart().getCharPositionInLine();
    final var name = context.IDENTIFIER().getText();

    final var fields = new ArrayList<FieldAssignmentNode>();
    for (final var assignment : context.fieldAssignment()) {
      fields.add((FieldAssignmentNode) visit(assignment));
    }

    return new ObjectCreationNode(line, column, name, fields);
  }

  @Override
  public ASTNode visitFieldAssignment(final FieldAssignmentContext context) {
    final var line = context.getStart().getLine();
    final var column = context.getStart().getCharPositionInLine();
    return new FieldAssignmentNode(
        line, column, context.IDENTIFIER().getText(), visit(context.expression()));
  }

  @Override
  public ASTNode visitVariableReference(final VariableReferenceContext context) {
    final var line = context.getStart().getLine();
    final var column = context.getStart().getCharPositionInLine();
    final var qualified = context.qualifiedName();

    String prefix = null;
    if (qualified.IDENTIFIER() != null) {
      prefix = qualified.IDENTIFIER().getText();
    }

    final var parts = new ArrayList<String>();
    for (final var name : qualified.dottedName().name()) {
      parts.add(name.IDENTIFIER().getText());
    }

    return new VariableReferenceNode(line, column, prefix, parts);
  }

  @Override
  public ASTNode visitLiteralBoolean(final LiteralBooleanContext context) {
    return visit(context.booleanLiteral());
  }

  @Override
  public ASTNode visitBooleanLiteral(final BooleanLiteralContext context) {
    final var line = context.getStart().getLine();
    final var column = context.getStart().getCharPositionInLine();
    final var value = context.getText().equals("true");
    return LiteralNode.ofBoolean(line, column, value);
  }

  @Override
  public ASTNode visitLiteralString(final LiteralStringContext context) {
    return visit(context.stringLiteral());
  }

  @Override
  public ASTNode visitStringLiteral(final StringLiteralContext context) {
    final var line = context.getStart().getLine();
    final var column = context.getStart().getCharPositionInLine();
    final var raw = context.getText();
    final var unquoted = strip(raw);
    final var unescaped = unescape(unquoted);
    return LiteralNode.ofString(line, column, unescaped);
  }

  @Override
  public ASTNode visitLiteralNumeric(final LiteralNumericContext context) {
    return visit(context.numericLiteral());
  }

  @Override
  public ASTNode visitNumericLiteral(final NumericLiteralContext context) {
    final var line = context.getStart().getLine();
    final var column = context.getStart().getCharPositionInLine();
    final var text = context.getText();

    if (context.NUM_FLOAT() != null) {
      return LiteralNode.ofFloat(line, column, Double.parseDouble(text));
    } else if (context.NUM_UINT() != null) {
      final var stripped = text.replaceAll("[uU]$", "");
      return LiteralNode.ofUint(line, column, integer(stripped));
    } else {
      return LiteralNode.ofInt(line, column, integer(text));
    }
  }

  private long integer(final String text) {
    if (text.startsWith("0x") || text.startsWith("0X")) {
      return Long.parseLong(text.substring(2), 16);
    }
    return Long.parseLong(text);
  }

  @Override
  public ASTNode visitLiteralList(final LiteralListContext context) {
    return visit(context.listLiteral());
  }

  @Override
  public ASTNode visitListLiteral(final ListLiteralContext context) {
    final var line = context.getStart().getLine();
    final var column = context.getStart().getCharPositionInLine();

    final var elements = new ArrayList<ASTNode>();
    for (final var expression : context.expression()) {
      elements.add(visit(expression));
    }

    return new ListLiteralNode(line, column, elements);
  }

  @Override
  public ASTNode visitLiteralMap(final LiteralMapContext context) {
    return visit(context.mapLiteral());
  }

  @Override
  public ASTNode visitMapLiteral(final MapLiteralContext context) {
    final var line = context.getStart().getLine();
    final var column = context.getStart().getCharPositionInLine();

    final var entries = new ArrayList<MapEntryNode>();
    for (final var entry : context.mapEntry()) {
      entries.add((MapEntryNode) visit(entry));
    }

    return new MapLiteralNode(line, column, entries);
  }

  @Override
  public ASTNode visitMapEntry(final MapEntryContext context) {
    final var line = context.getStart().getLine();
    final var column = context.getStart().getCharPositionInLine();
    return new MapEntryNode(
        line, column, visit(context.expression(0)), visit(context.expression(1)));
  }

  @Override
  public ASTNode visitLiteralSet(final LiteralSetContext context) {
    return visit(context.setLiteral());
  }

  @Override
  public ASTNode visitSetLiteral(final SetLiteralContext context) {
    final var line = context.getStart().getLine();
    final var column = context.getStart().getCharPositionInLine();

    final var elements = new ArrayList<ASTNode>();
    for (final var expression : context.expression()) {
      elements.add(visit(expression));
    }

    return new SetLiteralNode(line, column, elements);
  }

  @Override
  public ASTNode visitLiteralInterpolated(final LiteralInterpolatedContext context) {
    return visit(context.interpolatedString());
  }

  @Override
  public ASTNode visitInterpolatedString(final InterpolatedStringContext context) {
    final var line = context.getStart().getLine();
    final var column = context.getStart().getCharPositionInLine();
    final var raw = context.TEMPLATE_STRING().getText();
    // Strip backticks
    final var content = raw.substring(1, raw.length() - 1);
    final var parts = interpolation(content, line, column);
    return new StringInterpolationNode(line, column, parts);
  }

  private TypeNode typed(final TypeContext context) {
    final var singles = context.singleType();
    if (singles.size() == 1) {
      return single(singles.getFirst());
    }
    // Union type: int|float|uint etc.
    final var line = context.getStart().getLine();
    final var column = context.getStart().getCharPositionInLine();
    final var members = new ArrayList<TypeNode>();
    for (final var single : singles) {
      members.add(single(single));
    }
    return TypeNode.withMembers(line, column, members);
  }

  private TypeNode single(final SingleTypeContext context) {
    final var line = context.getStart().getLine();
    final var column = context.getStart().getCharPositionInLine();

    // Function type: fn(int, string) -> float -> TypeNode("fn", params=[int, string, float])
    if (context.FN() != null) {
      final var parameters = new ArrayList<TypeNode>();
      // All types except the last are parameter types; last is return type
      final var types = context.type();
      for (final var type : types) {
        parameters.add(typed(type));
      }
      return TypeNode.withParameters(line, column, "fn", parameters);
    }

    // Tuple type: (int, string) -> TypeNode("tuple", params=[int, string])
    if (context.baseType() == null) {
      final var parameters = new ArrayList<TypeNode>();
      for (final var type : context.type()) {
        parameters.add(typed(type));
      }
      return TypeNode.withParameters(line, column, "tuple", parameters);
    }

    final var base = context.baseType();

    TypeNode node;
    if (base.IDENTIFIER() != null && base.getText().startsWith("?")) {
      node = TypeNode.withVariable(line, column, base.IDENTIFIER().getText());
    } else if (base.qualifiedName() != null) {
      final var qualified = base.qualifiedName();
      final var parts = new ArrayList<String>();
      if (qualified.IDENTIFIER() != null) {
        parts.add(qualified.IDENTIFIER().getText());
      }
      for (final var name : qualified.dottedName().name()) {
        parts.add(name.IDENTIFIER().getText());
      }
      if (parts.size() > 1) {
        node = new TypeNode(line, column, parts);
      } else {
        node = new TypeNode(line, column, parts.getFirst());
      }
    } else {
      node = new TypeNode(line, column, base.getText());
    }

    // Generic type parameters
    if (context.type() != null && !context.type().isEmpty()) {
      final var parameters = new ArrayList<TypeNode>();
      for (final var type : context.type()) {
        parameters.add(typed(type));
      }
      node = TypeNode.withParameters(node.line(), node.column(), node.name(), parameters);
    }

    return node;
  }

  private List<String> parts(final QualifiedNameContext context) {
    final var parts = new ArrayList<String>();
    if (context.IDENTIFIER() != null) {
      parts.add(context.IDENTIFIER().getText());
    }
    for (final var name : context.dottedName().name()) {
      parts.add(name.IDENTIFIER().getText());
    }
    return parts;
  }

  private String strip(final String raw) {
    if (raw.startsWith("\"\"\"") && raw.endsWith("\"\"\"")) {
      return raw.substring(3, raw.length() - 3);
    }
    if (raw.startsWith("'''") && raw.endsWith("'''")) {
      return raw.substring(3, raw.length() - 3);
    }
    if ((raw.startsWith("\"") && raw.endsWith("\""))
        || (raw.startsWith("'") && raw.endsWith("'"))) {
      return raw.substring(1, raw.length() - 1);
    }
    return raw;
  }

  private String unescape(final String text) {
    final var builder = new StringBuilder();
    for (int i = 0; i < text.length(); i++) {
      if (text.charAt(i) == '\\' && i + 1 < text.length()) {
        i = unescape(text, i + 1, builder);
      } else {
        builder.append(text.charAt(i));
      }
    }
    return builder.toString();
  }

  /**
   * Process a single escape sequence starting at position i (after the backslash). Appends the
   * result to builder and returns the new index to continue from.
   */
  private int unescape(final String text, int i, final StringBuilder builder) {
    final var ch = text.charAt(i);
    switch (ch) {
      case 'n' -> builder.append('\n');
      case 't' -> builder.append('\t');
      case 'r' -> builder.append('\r');
      case '\\' -> builder.append('\\');
      case '"' -> builder.append('"');
      case '\'' -> builder.append('\'');
      case '`' -> builder.append('`');
      case '$' -> builder.append('$');
      case 'a' -> builder.append('\u0007');
      case 'b' -> builder.append('\b');
      case 'f' -> builder.append('\f');
      case 'v' -> builder.append('\u000B');
      case '?' -> builder.append('?');
      case '0', '1', '2', '3', '4', '5', '6', '7' -> {
        // Octal escape: \0 through \377 (up to 3 octal digits)
        int value = ch - '0';
        int consumed = 0;
        final int limit = (ch <= '3') ? 2 : 1; // \0-\3 can have 3 digits total, \4-\7 only 2
        while (consumed < limit && i + 1 + consumed < text.length()) {
          final var next = text.charAt(i + 1 + consumed);
          if (next >= '0' && next <= '7') {
            value = value * 8 + (next - '0');
            consumed++;
          } else {
            break;
          }
        }
        builder.append((char) value);
        return i + consumed;
      }
      case 'x' -> {
        // \xHH — hex byte
        if (i + 2 < text.length()) {
          final var hex = text.substring(i + 1, i + 3);
          try {
            builder.append((char) Integer.parseInt(hex, 16));
            return i + 2;
          } catch (NumberFormatException ignored) {
            builder.append('x');
          }
        } else {
          builder.append('x');
        }
      }
      case 'u' -> {
        // unicode 4-digit escape
        if (i + 4 < text.length()) {
          final var hex = text.substring(i + 1, i + 5);
          try {
            builder.append((char) Integer.parseInt(hex, 16));
            return i + 4;
          } catch (NumberFormatException ignored) {
            builder.append('u');
          }
        } else {
          builder.append('u');
        }
      }
      case 'U' -> {
        // unicode 8-digit escape
        if (i + 8 < text.length()) {
          final var hex = text.substring(i + 1, i + 9);
          try {
            final var codepoint = Integer.parseInt(hex, 16);
            builder.appendCodePoint(codepoint);
            return i + 8;
          } catch (NumberFormatException ignored) {
            builder.append('U');
          }
        } else {
          builder.append('U');
        }
      }
      default -> builder.append(ch);
    }
    return i;
  }

  private List<ASTNode> interpolation(final String template, final int line, final int column) {
    final var parts = new ArrayList<ASTNode>();
    int i = 0;

    while (i < template.length()) {
      if (template.charAt(i) == '\\' && i + 1 < template.length()) {
        // Handle escape in template
        final var builder = new StringBuilder();
        while (i < template.length()) {
          if (template.charAt(i) == '\\' && i + 1 < template.length()) {
            i = unescape(template, i + 1, builder) + 1;
          } else if (template.startsWith("${", i)) {
            break;
          } else {
            builder.append(template.charAt(i));
            i++;
          }
        }
        if (!builder.isEmpty()) {
          parts.add(LiteralNode.ofString(line, column, builder.toString()));
        }
      } else if (template.startsWith("${", i)) {
        // Find matching }, skipping braces inside string literals
        final var start = i + 2;
        var depth = 1;
        var end = start;
        while (end < template.length() && depth > 0) {
          final var quote = template.charAt(end);
          if (quote == '"' || quote == '\'' || quote == '`') {
            // Skip over string literal
            end++;
            while (end < template.length()) {
              if (template.charAt(end) == '\\' && end + 1 < template.length()) {
                end += 2; // skip escaped char
              } else if (template.charAt(end) == quote) {
                end++;
                break;
              } else {
                end++;
              }
            }
            continue;
          }
          if (quote == '{') depth++;
          else if (quote == '}') depth--;
          if (depth > 0) end++;
        }

        final var expression = template.substring(start, end);
        final var parsed = expression(expression, line, column);
        parts.add(parsed);
        i = end + 1;
      } else {
        // Collect literal text
        final var builder = new StringBuilder();
        while (i < template.length() && !template.startsWith("${", i)) {
          if (template.charAt(i) == '\\' && i + 1 < template.length()) {
            i = unescape(template, i + 1, builder) + 1;
          } else {
            builder.append(template.charAt(i));
            i++;
          }
        }
        if (!builder.isEmpty()) {
          parts.add(LiteralNode.ofString(line, column, builder.toString()));
        }
      }
    }

    return parts;
  }

  private ASTNode expression(final String expression, final int line, final int column) {
    final var lexer = new SAFEGrammarLexer(CharStreams.fromString(expression));
    lexer.removeErrorListeners();
    lexer.addErrorListener(ThrowingErrorListener.INSTANCE);

    final var tokens = new CommonTokenStream(lexer);
    final var parser = new SAFEGrammarParser(tokens);
    parser.removeErrorListeners();
    parser.addErrorListener(ThrowingErrorListener.INSTANCE);

    final var tree = parser.expression();
    if (parser.getCurrentToken().getType() != Token.EOF) {
      throw new ParserException(
          "Unexpected trailing tokens in interpolation: " + expression, line, column);
    }
    return visit(tree);
  }
}
