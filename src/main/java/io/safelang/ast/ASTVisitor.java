package io.safelang.ast;

/**
 * Visitor interface for the AST of the SAFE programming language.
 *
 * @param <T> The return type of visitor methods.
 */
public interface ASTVisitor<T> {
  T visitProgram(ProgramNode node);

  T visitImport(ImportNode node);

  T visitType(TypeNode node);

  T visitTypeDeclaration(TypeDeclarationNode node);

  T visitFieldDeclaration(FieldDeclarationNode node);

  T visitFunctionDeclaration(FunctionDeclarationNode node);

  T visitParameter(ParameterNode node);

  T visitVariableDeclaration(VariableDeclarationNode node);

  T visitAssignment(AssignmentNode node);

  T visitForStatement(ForStatementNode node);

  T visitReturn(ReturnNode node);

  T visitExpressionStatement(ExpressionStatementNode node);

  T visitBinaryExpression(BinaryExpressionNode node);

  T visitUnaryExpression(UnaryExpressionNode node);

  T visitIfExpression(IfExpressionNode node);

  T visitCaseExpression(CaseExpressionNode node);

  T visitCaseBranch(CaseBranchNode node);

  T visitFunctionCall(FunctionCallNode node);

  T visitVariableReference(VariableReferenceNode node);

  T visitObjectCreation(ObjectCreationNode node);

  T visitFieldAssignment(FieldAssignmentNode node);

  T visitLiteral(LiteralNode node);

  T visitListLiteral(ListLiteralNode node);

  // New visitor methods for extended features
  T visitMapLiteral(MapLiteralNode node);

  T visitMapEntry(MapEntryNode node);

  T visitAssert(AssertNode node);

  T visitIndexAccess(IndexAccessNode node);

  T visitIndexAssignment(IndexAssignmentNode node);

  T visitEnumDeclaration(EnumDeclarationNode node);

  T visitEnumVariant(EnumVariantNode node);

  T visitEnumPattern(EnumPatternNode node);

  T visitStringInterpolation(StringInterpolationNode node);

  T visitFieldAccess(FieldAccessNode node);

  T visitTypeAlias(TypeAliasNode node);

  T visitRange(RangeNode node);

  T visitDoExpression(DoExpressionNode node);

  T visitTupleLiteral(TupleLiteralNode node);

  T visitSetLiteral(SetLiteralNode node);

  T visitLambda(LambdaNode node);

  T visitDestructure(DestructureNode node);

  T visitWhileStatement(WhileStatementNode node);
}
