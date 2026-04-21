package io.safelang.analyzer;

import io.safelang.ast.ASTNode;

interface SemanticAssignmentHooks {

  TypeEnvironment scope();

  boolean module();

  void analyze(ASTNode node);

  void error(String message, ASTNode node);
}
