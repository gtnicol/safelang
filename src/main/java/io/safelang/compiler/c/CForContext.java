package io.safelang.compiler.c;

import io.safelang.ast.ASTNode;
import io.safelang.ast.EnumDeclarationNode;
import java.util.Map;
import java.util.Set;

/**
 * Context exposed to {@link CForCompiler} so it can compile the iterable expression and the loop
 * body, query SAFE type information, and bind the loop variable into the surrounding scope's
 * variable map.
 */
interface CForContext {

  /** Recursively compile an AST node back through the top-level visitor. */
  String emit(ASTNode node);

  /** Mangle a user-supplied identifier to avoid collision with C reserved words. */
  String user(String name);

  /** Is the SAFE type a heap-allocated, refcounted value carrying a SAFEHeader? */
  boolean isHeapRc(String type);

  /**
   * Are we generating code for the top-level program (not inside a module function)? Scope-release
   * is only safe at the top level right now — stdlib functions frequently escape heap values into
   * containers without the retain-on-insert discipline required to make a release safe.
   */
  boolean inTopLevel();

  /**
   * Produce the C release statements for a heap-owning local (empty if nothing needs releasing).
   * Handles heap-RC values and struct-valued locals whose fields hold heap pointers.
   */
  String releaseForLocal(String name, String type);

  /** Best-effort SAFE type inference. */
  String infer(ASTNode node);

  /** Translate a SAFE type to its C type (delegates to {@link CTypeMapper}). */
  String translate(String type);

  /** {@code map<K, V>} → key type {@code K}. */
  String keyed(String type);

  boolean isPointerType(String type);

  boolean isFunctionType(String type);

  /** True if {@code type} is a user-defined struct (stored heap-boxed in list elements). */
  boolean isStruct(String type);

  /** Recursive-enum set (for emitting pointer-typed loop variables). */
  Set<String> recursive();

  /** Enum registry — to detect arena-boxed enum loop variables. */
  Map<String, EnumDeclarationNode> enumerations();

  /**
   * Local variable type map for the current frame. The for-compiler binds the loop variable here so
   * subsequent statements in the body can resolve it.
   */
  Map<String, String> variables();

  /** Append the current indent prefix to {@code builder}. */
  void indent(StringBuilder builder);

  /** Increment the visitor's indent level (for nested blocks). */
  void indentInc();

  /** Decrement the visitor's indent level. */
  void indentDec();
}
