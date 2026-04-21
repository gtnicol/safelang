package io.safelang.ast;

/**
 * Base interface for all AST nodes in the SAFE programming language. All concrete node types
 * implement this interface and provide the accept method for the visitor pattern.
 */
public sealed interface ASTNode
    permits AssertNode,
        AssignmentNode,
        BinaryExpressionNode,
        CaseBranchNode,
        CaseExpressionNode,
        DestructureNode,
        DoExpressionNode,
        EnumDeclarationNode,
        EnumPatternNode,
        EnumVariantNode,
        ExpressionStatementNode,
        FieldAccessNode,
        FieldAssignmentNode,
        FieldDeclarationNode,
        ForStatementNode,
        FunctionCallNode,
        FunctionDeclarationNode,
        IfExpressionNode,
        ImportNode,
        IndexAccessNode,
        IndexAssignmentNode,
        LambdaNode,
        ListLiteralNode,
        LiteralNode,
        MapEntryNode,
        MapLiteralNode,
        ObjectCreationNode,
        ParameterNode,
        ProgramNode,
        RangeNode,
        ReturnNode,
        SetLiteralNode,
        StringInterpolationNode,
        TupleLiteralNode,
        TypeAliasNode,
        TypeDeclarationNode,
        TypeNode,
        UnaryExpressionNode,
        VariableDeclarationNode,
        VariableReferenceNode,
        WhileStatementNode {

  int line();

  int column();

  <T> T accept(ASTVisitor<T> visitor);
}
