package io.safelang.compiler.c;

import io.safelang.ast.ASTNode;
import io.safelang.ast.FunctionDeclarationNode;
import java.util.Map;

interface CFormatContext {

  String infer(ASTNode node);

  boolean stringlike(ASTNode node);

  Map<String, String> variables();

  FunctionDeclarationNode function(String name);
}
