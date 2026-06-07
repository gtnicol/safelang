package io.safelang.compiler.c;

import io.safelang.ast.ASTNode;
import io.safelang.ast.EnumDeclarationNode;
import java.util.Map;

/**
 * Context exposed to {@link CCollectionEmitter} so it can compile sub-expressions, query inferred
 * types, wrap values for tuple/set storage, and manipulate the surrounding visitor's indent level
 * when emitting nested GCC statement expressions.
 */
interface CCollectionContext {

  /** Recursively compile an AST node back through the top-level visitor. */
  String emit(ASTNode node);

  /** Best-effort SAFE type inference. */
  String infer(ASTNode node);

  /** Wrap a raw C value as a {@code SAFEValue} union of the given SAFE type. */
  String wrap(String code, String type);

  /** Translate a SAFE type to its C type (delegates to {@link CTypeMapper}). */
  String translate(String type);

  /** {@code map<K, V>} → key type {@code K} (used to pick the right put function). */
  String keyed(String type);

  /** {@code map<K, V>} → value type {@code V}. */
  String valued(String type);

  /** {@code list<T>} → element type {@code T}. */
  String inner(String type);

  /**
   * Map a SAFE type name to the SAFE_KIND_* constant (as an identifier string, e.g.
   * "SAFE_KIND_BYTES"). Returns "0" for scalar/value types.
   */
  String safeKindOf(String type);

  /** Runtime function prefix for map insertion, dispatched on key type. */
  String putter(String key);

  boolean isPointerType(String type);

  boolean isFunctionType(String type);

  /** True if {@code type} is a user-defined struct (boxed by heap-copy in list elements). */
  boolean isStruct(String type);

  /** True if {@code type} is a recursive enum/struct (its C value is already a pointer). */
  boolean isRecursive(String type);

  /** Enum registry — required to detect arena-boxed enum values. */
  Map<String, EnumDeclarationNode> enumerations();

  /** Append the current indent prefix to {@code builder}. */
  void indent(StringBuilder builder);

  /** Increment the visitor's indent level (for nested blocks). */
  void indentInc();

  /** Decrement the visitor's indent level. */
  void indentDec();
}
