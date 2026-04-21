package io.safelang.analyzer;

import io.safelang.ast.ASTNode;
import io.safelang.ast.EnumDeclarationNode;

interface SemanticImportHooks {

  void error(String message, ASTNode node);

  void conflict(EnumDeclarationNode declaration, String module, ASTNode node);
}
