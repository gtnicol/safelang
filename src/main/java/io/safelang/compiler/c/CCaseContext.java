package io.safelang.compiler.c;

import io.safelang.ast.ASTNode;
import io.safelang.ast.EnumDeclarationNode;
import java.util.Map;
import java.util.Set;

/**
 * Context exposed to {@link CCaseCompiler} so it can compile the subject, branch results, and
 * guards while binding pattern variables into the surrounding scope's variable map.
 */
interface CCaseContext {

  /** Recursively compile an AST node back through the top-level visitor. */
  String emit(ASTNode node);

  /** Mangle a user-supplied identifier to avoid collision with C reserved words. */
  String user(String name);

  /** Best-effort SAFE type inference. */
  String infer(ASTNode node);

  /** Translate a SAFE type to its C type. */
  String translate(String type);

  /**
   * Local variable type map for the current frame. The case-compiler binds pattern variables here
   * so the branch result can resolve them.
   */
  Map<String, String> variables();

  /** Enum registry — keyed by SAFE enum type name. */
  Map<String, EnumDeclarationNode> enumerations();

  /** Recursive-enum set — these use {@code ->} (pointer) field access. */
  Set<String> recursive();
}
