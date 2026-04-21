package io.safelang.compiler.c;

import io.safelang.ast.ASTNode;
import io.safelang.ast.EnumDeclarationNode;
import java.util.Map;

/**
 * Context exposed to {@link CBuiltinResolver} so it can compile sub-expressions, query inferred
 * types, and call back into the visitor's helpers (format specifiers, value wrappers, type
 * predicates) without depending on {@link CCodeGenerator}'s concrete state.
 */
interface CBuiltinContext {

  /** Recursively compile an AST node back through the top-level visitor. */
  String emit(ASTNode node);

  /** Best-effort SAFE type inference. */
  String infer(ASTNode node);

  /** Wrap a raw C value as a {@code SAFEValue} union of the given SAFE type. */
  String wrap(String code, String type);

  /** {@code map<K, V>} → value type {@code V}. */
  String valued(String type);

  /** {@code map<K, V>} → key type {@code K}. */
  String keyed(String type);

  boolean isPointerType(String type);

  boolean isIntegerKeyed(String key);

  boolean isFloatKeyed(String key);

  boolean isGenericType(String type);

  /** Map a SAFE type name to SAFE_KIND_* constant ("0" for scalars). */
  String safeKindOf(String type);

  /** Enum registry — for distinguishing enum-typed values during list append. */
  Map<String, EnumDeclarationNode> enumerations();

  /** Printf format specifier for an expression (delegates to {@link CFormatResolver}). */
  String format(ASTNode node);

  /**
   * True when the expression is statically known to be boolean (drives stringification in print).
   */
  boolean isBooleanExpression(ASTNode node);

  /** C-escape a Java string literal for embedding in printf. */
  String escape(String text);
}
