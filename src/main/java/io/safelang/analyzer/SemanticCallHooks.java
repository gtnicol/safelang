package io.safelang.analyzer;

import io.safelang.ast.ASTNode;
import io.safelang.ast.FunctionDeclarationNode;
import io.safelang.ast.TypeNode;

interface SemanticCallHooks {

  FunctionDeclarationNode current();

  void analyze(ASTNode node);

  void error(String message, ASTNode node);

  void use(String name);

  TypeEnvironment scope();

  boolean module();

  boolean strict();

  /**
   * The expected type at the enclosing assignment/declaration site, if one is in scope. Used to
   * disambiguate unqualified enum variant constructors: when two imported enums share a variant
   * name, the declared target type picks the owning enum.
   */
  TypeNode expected();

  boolean impure(FunctionDeclarationNode node);

  /**
   * Transitive purity check for a function declared in the named module. Unqualified calls inside
   * the function body are resolved against {@code module}'s namespace, so the check correctly
   * catches a chain like {@code main → mod:public → mod:private → time()}.
   */
  boolean impure(FunctionDeclarationNode node, String module);

  boolean impure(ASTNode node);

  void open();

  void close();
}
