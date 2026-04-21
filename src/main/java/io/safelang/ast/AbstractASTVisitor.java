package io.safelang.ast;

/** Base visitor that returns {@code null} by default. */
public abstract class AbstractASTVisitor<T> implements ASTVisitor<T> {

  protected T defaultResult() {
    return null;
  }

  @Override
  public T visitProgram(final ProgramNode node) {
    return defaultResult();
  }

  @Override
  public T visitImport(final ImportNode node) {
    return defaultResult();
  }

  @Override
  public T visitType(final TypeNode node) {
    return defaultResult();
  }

  @Override
  public T visitTypeDeclaration(final TypeDeclarationNode node) {
    return defaultResult();
  }

  @Override
  public T visitFieldDeclaration(final FieldDeclarationNode node) {
    return defaultResult();
  }

  @Override
  public T visitFunctionDeclaration(final FunctionDeclarationNode node) {
    return defaultResult();
  }

  @Override
  public T visitParameter(final ParameterNode node) {
    return defaultResult();
  }

  @Override
  public T visitVariableDeclaration(final VariableDeclarationNode node) {
    return defaultResult();
  }

  @Override
  public T visitAssignment(final AssignmentNode node) {
    return defaultResult();
  }

  @Override
  public T visitForStatement(final ForStatementNode node) {
    return defaultResult();
  }

  @Override
  public T visitReturn(final ReturnNode node) {
    return defaultResult();
  }

  @Override
  public T visitExpressionStatement(final ExpressionStatementNode node) {
    return defaultResult();
  }

  @Override
  public T visitBinaryExpression(final BinaryExpressionNode node) {
    return defaultResult();
  }

  @Override
  public T visitUnaryExpression(final UnaryExpressionNode node) {
    return defaultResult();
  }

  @Override
  public T visitIfExpression(final IfExpressionNode node) {
    return defaultResult();
  }

  @Override
  public T visitCaseExpression(final CaseExpressionNode node) {
    return defaultResult();
  }

  @Override
  public T visitCaseBranch(final CaseBranchNode node) {
    return defaultResult();
  }

  @Override
  public T visitFunctionCall(final FunctionCallNode node) {
    return defaultResult();
  }

  @Override
  public T visitVariableReference(final VariableReferenceNode node) {
    return defaultResult();
  }

  @Override
  public T visitObjectCreation(final ObjectCreationNode node) {
    return defaultResult();
  }

  @Override
  public T visitFieldAssignment(final FieldAssignmentNode node) {
    return defaultResult();
  }

  @Override
  public T visitLiteral(final LiteralNode node) {
    return defaultResult();
  }

  @Override
  public T visitListLiteral(final ListLiteralNode node) {
    return defaultResult();
  }

  @Override
  public T visitMapLiteral(final MapLiteralNode node) {
    return defaultResult();
  }

  @Override
  public T visitMapEntry(final MapEntryNode node) {
    return defaultResult();
  }

  @Override
  public T visitAssert(final AssertNode node) {
    return defaultResult();
  }

  @Override
  public T visitIndexAccess(final IndexAccessNode node) {
    return defaultResult();
  }

  @Override
  public T visitIndexAssignment(final IndexAssignmentNode node) {
    return defaultResult();
  }

  @Override
  public T visitEnumDeclaration(final EnumDeclarationNode node) {
    return defaultResult();
  }

  @Override
  public T visitEnumVariant(final EnumVariantNode node) {
    return defaultResult();
  }

  @Override
  public T visitEnumPattern(final EnumPatternNode node) {
    return defaultResult();
  }

  @Override
  public T visitStringInterpolation(final StringInterpolationNode node) {
    return defaultResult();
  }

  @Override
  public T visitFieldAccess(final FieldAccessNode node) {
    return defaultResult();
  }

  @Override
  public T visitTypeAlias(final TypeAliasNode node) {
    return defaultResult();
  }

  @Override
  public T visitRange(final RangeNode node) {
    return defaultResult();
  }

  @Override
  public T visitDoExpression(final DoExpressionNode node) {
    return defaultResult();
  }

  @Override
  public T visitTupleLiteral(final TupleLiteralNode node) {
    return defaultResult();
  }

  @Override
  public T visitSetLiteral(final SetLiteralNode node) {
    return defaultResult();
  }

  @Override
  public T visitLambda(final LambdaNode node) {
    return defaultResult();
  }

  @Override
  public T visitDestructure(final DestructureNode node) {
    return defaultResult();
  }

  @Override
  public T visitWhileStatement(final WhileStatementNode node) {
    return defaultResult();
  }
}
