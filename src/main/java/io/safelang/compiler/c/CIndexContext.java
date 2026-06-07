package io.safelang.compiler.c;

import io.safelang.ast.ASTNode;
import io.safelang.ast.EnumDeclarationNode;
import java.util.Map;
import java.util.Set;

/**
 * Context exposed to {@link CIndexCompiler} so it can compile container and index expressions,
 * query inferred types, and dispatch to the right runtime accessor by element type
 * (list/tuple/map).
 */
interface CIndexContext {

  String emit(ASTNode node);

  String infer(ASTNode node);

  String translate(String type);

  String unwrap(String code, String type);

  /** {@code list<T>} → element type {@code T}. */
  String inner(String type);

  /** {@code map<K, V>} → value type {@code V}. */
  String valued(String type);

  /** {@code map<K, V>} → key type {@code K}. */
  String keyed(String type);

  /** {@code tuple<T1, T2, ...>} indexed by a literal index node → element type. */
  String tuple(String type, ASTNode index);

  /** Runtime function prefix for map insertion ({@code safe_map_*_put_}). */
  String putter(String key);

  /** Runtime function prefix for map access ({@code safe_map_*_get_}). */
  String getter(String key);

  boolean isPointerType(String type);

  /** True if {@code type} is a user-defined struct (stored heap-boxed in list elements). */
  boolean isStruct(String type);

  /** Is the SAFE type a heap-allocated, refcounted value carrying a SAFEHeader? */
  boolean isHeapRc(String type);

  /** Local variable type map for the current frame. */
  Map<String, String> variables();

  Map<String, EnumDeclarationNode> enumerations();

  Set<String> recursive();
}
