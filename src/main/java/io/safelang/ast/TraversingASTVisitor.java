package io.safelang.ast;

/**
 * Base visitor that recurses into every child of every container node, returning {@link
 * AbstractASTVisitor#defaultResult()} unless a subclass overrides.
 *
 * <p>{@link AbstractASTVisitor} returns {@code null} for every method without descending, which is
 * safe for visitors that intend to handle every node explicitly but is a silent footgun for
 * visitors that want to observe just a handful of node kinds (e.g. "collect every identifier
 * reference"). A partial override of {@code AbstractASTVisitor} silently skips whole subtrees.
 *
 * <p>Extend {@code TraversingASTVisitor} when you want the opposite default: any node the subclass
 * does not override still has its children walked. Subclasses that need a custom traversal for one
 * node kind still override that method; they just don't have to write out the full traversal for
 * every other container they could encounter.
 */
public abstract class TraversingASTVisitor<T> extends AbstractASTVisitor<T> {

  private void walk(final ASTNode node) {
    if (node != null) node.accept(this);
  }

  @Override
  public T visitProgram(final ProgramNode node) {
    for (final var imported : node.imports()) walk(imported);
    for (final var declaration : node.declarations()) walk(declaration);
    for (final var statement : node.statements()) walk(statement);
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
    for (final var field : node.fields()) walk(field);
    return defaultResult();
  }

  @Override
  public T visitFieldDeclaration(final FieldDeclarationNode node) {
    return defaultResult();
  }

  @Override
  public T visitFunctionDeclaration(final FunctionDeclarationNode node) {
    for (final var parameter : node.parameters()) walk(parameter);
    if (node.hasRequires()) walk(node.requires());
    if (node.hasEnsures()) walk(node.ensures());
    if (node.hasDecreases()) walk(node.decreases());
    for (final var statement : node.body()) walk(statement);
    return defaultResult();
  }

  @Override
  public T visitParameter(final ParameterNode node) {
    if (node.hasDefault()) walk(node.initial());
    return defaultResult();
  }

  @Override
  public T visitVariableDeclaration(final VariableDeclarationNode node) {
    if (node.hasInitializer()) walk(node.initializer());
    return defaultResult();
  }

  @Override
  public T visitAssignment(final AssignmentNode node) {
    walk(node.value());
    return defaultResult();
  }

  @Override
  public T visitForStatement(final ForStatementNode node) {
    walk(node.iterable());
    for (final var statement : node.body()) walk(statement);
    return defaultResult();
  }

  @Override
  public T visitWhileStatement(final WhileStatementNode node) {
    walk(node.condition());
    walk(node.bound());
    for (final var statement : node.body()) walk(statement);
    return defaultResult();
  }

  @Override
  public T visitReturn(final ReturnNode node) {
    if (node.hasExpression()) walk(node.expression());
    return defaultResult();
  }

  @Override
  public T visitExpressionStatement(final ExpressionStatementNode node) {
    walk(node.expression());
    return defaultResult();
  }

  @Override
  public T visitBinaryExpression(final BinaryExpressionNode node) {
    walk(node.left());
    walk(node.right());
    return defaultResult();
  }

  @Override
  public T visitUnaryExpression(final UnaryExpressionNode node) {
    walk(node.operand());
    return defaultResult();
  }

  @Override
  public T visitIfExpression(final IfExpressionNode node) {
    walk(node.condition());
    walk(node.then());
    if (node.hasOtherwise()) walk(node.otherwise());
    return defaultResult();
  }

  @Override
  public T visitCaseExpression(final CaseExpressionNode node) {
    walk(node.subject());
    for (final var branch : node.branches()) walk(branch);
    if (node.hasFallback()) walk(node.fallback());
    return defaultResult();
  }

  @Override
  public T visitCaseBranch(final CaseBranchNode node) {
    walk(node.pattern());
    if (node.hasGuard()) walk(node.guard());
    walk(node.result());
    return defaultResult();
  }

  @Override
  public T visitFunctionCall(final FunctionCallNode node) {
    for (final var argument : node.arguments()) walk(argument);
    return defaultResult();
  }

  @Override
  public T visitVariableReference(final VariableReferenceNode node) {
    return defaultResult();
  }

  @Override
  public T visitObjectCreation(final ObjectCreationNode node) {
    for (final var field : node.fields()) walk(field);
    return defaultResult();
  }

  @Override
  public T visitFieldAssignment(final FieldAssignmentNode node) {
    walk(node.value());
    return defaultResult();
  }

  @Override
  public T visitLiteral(final LiteralNode node) {
    return defaultResult();
  }

  @Override
  public T visitListLiteral(final ListLiteralNode node) {
    for (final var element : node.elements()) walk(element);
    return defaultResult();
  }

  @Override
  public T visitMapLiteral(final MapLiteralNode node) {
    for (final var entry : node.entries()) walk(entry);
    return defaultResult();
  }

  @Override
  public T visitMapEntry(final MapEntryNode node) {
    walk(node.key());
    walk(node.value());
    return defaultResult();
  }

  @Override
  public T visitAssert(final AssertNode node) {
    walk(node.condition());
    if (node.hasMessage()) walk(node.message());
    return defaultResult();
  }

  @Override
  public T visitIndexAccess(final IndexAccessNode node) {
    walk(node.container());
    walk(node.index());
    return defaultResult();
  }

  @Override
  public T visitIndexAssignment(final IndexAssignmentNode node) {
    walk(node.container());
    for (final var index : node.indices()) walk(index);
    walk(node.value());
    return defaultResult();
  }

  @Override
  public T visitEnumDeclaration(final EnumDeclarationNode node) {
    for (final var variant : node.variants()) walk(variant);
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
    for (final var part : node.parts()) walk(part);
    return defaultResult();
  }

  @Override
  public T visitFieldAccess(final FieldAccessNode node) {
    walk(node.receiver());
    return defaultResult();
  }

  @Override
  public T visitTypeAlias(final TypeAliasNode node) {
    return defaultResult();
  }

  @Override
  public T visitRange(final RangeNode node) {
    walk(node.start());
    walk(node.end());
    if (node.hasStep()) walk(node.step());
    return defaultResult();
  }

  @Override
  public T visitDoExpression(final DoExpressionNode node) {
    for (final var statement : node.statements()) walk(statement);
    walk(node.expression());
    return defaultResult();
  }

  @Override
  public T visitTupleLiteral(final TupleLiteralNode node) {
    for (final var element : node.elements()) walk(element);
    return defaultResult();
  }

  @Override
  public T visitSetLiteral(final SetLiteralNode node) {
    for (final var element : node.elements()) walk(element);
    return defaultResult();
  }

  @Override
  public T visitLambda(final LambdaNode node) {
    for (final var parameter : node.parameters()) walk(parameter);
    walk(node.body());
    return defaultResult();
  }

  @Override
  public T visitDestructure(final DestructureNode node) {
    walk(node.initializer());
    return defaultResult();
  }
}
