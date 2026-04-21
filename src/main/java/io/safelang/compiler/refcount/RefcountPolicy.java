package io.safelang.compiler.refcount;

import io.safelang.ast.ASTNode;
import io.safelang.ast.FieldDeclarationNode;
import io.safelang.ast.FunctionCallNode;
import io.safelang.ast.IfExpressionNode;
import io.safelang.ast.ObjectCreationNode;
import io.safelang.ast.TypeDeclarationNode;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Shared reference-counting classification logic. Both the C and WASM backends consult a {@link
 * RefcountPolicy} to decide which SAFE types require retain/release bookkeeping, which SAFE_KIND_*
 * code to stamp into the SAFEHeader for a given type, and how to encode per-kind {@code meta}
 * bitmaps for bitmap-kinded allocations (TUPLE / OBJECT / ENUM / CLOSURE).
 *
 * <p>The policy is backend-agnostic — it only answers "is this a heap type" and "what kind does it
 * allocate as" in terms of SAFE type names and AST nodes. The emitter is responsible for producing
 * the actual safe_alloc/safe_retain/safe_release calls.
 */
public final class RefcountPolicy {

  /**
   * SAFE_KIND_* constants as C expressions. Kept as strings so they embed unchanged into C source.
   * The WASM backend maps the same names to integer literals.
   */
  public static final String KIND_NONE = "0";

  public static final String KIND_LIST = "SAFE_KIND_LIST";
  public static final String KIND_MAP = "SAFE_KIND_MAP";
  public static final String KIND_BYTES = "SAFE_KIND_BYTES";
  public static final String KIND_TUPLE = "SAFE_KIND_TUPLE";
  public static final String KIND_OBJECT = "SAFE_KIND_OBJECT";
  public static final String KIND_ENUM = "SAFE_KIND_ENUM";
  public static final String KIND_CLOSURE = "SAFE_KIND_CLOSURE";
  public static final String KIND_STRING = "SAFE_KIND_STRING";
  public static final String KIND_SET = "SAFE_KIND_SET";

  /**
   * Integer values matching the C constants. Used by the WASM emitter and by bitmap helpers that
   * compare against a kind code.
   */
  public static final int KIND_LIST_CODE = 1;

  public static final int KIND_MAP_CODE = 2;
  public static final int KIND_BYTES_CODE = 3;
  public static final int KIND_TUPLE_CODE = 4;
  public static final int KIND_OBJECT_CODE = 5;
  public static final int KIND_ENUM_CODE = 6;
  public static final int KIND_CLOSURE_CODE = 7;
  public static final int KIND_STRING_CODE = 8;
  public static final int KIND_SET_CODE = 9;

  private final Set<String> recursiveEnums;
  private final Map<String, TypeDeclarationNode> structs;

  public RefcountPolicy(
      final Set<String> recursiveEnums, final Map<String, TypeDeclarationNode> structs) {
    this.recursiveEnums = recursiveEnums;
    this.structs = structs;
  }

  /**
   * True when a SAFE value of {@code type} lives on the refcounted heap — its storage is preceded
   * by a SAFEHeader and retain/release calls need to be emitted on assignment, scope exit, and
   * container insert/remove.
   */
  public boolean isHeap(final String type) {
    if (type == null) return false;
    if (type.startsWith("list<")
        || type.startsWith("map<")
        || type.startsWith("set<")
        || "bytes".equals(type)
        // Closures are always boxed since bug 006, so `fn<...>` values
        // are SAFEClosure* and need retain/release at scope/assignment.
        || type.startsWith("fn<")
        || "fn".equals(type)) {
      return true;
    }
    // Self-referencing enums are heap-boxed with SAFE_KIND_ENUM.
    return recursiveEnums.contains(type);
  }

  /**
   * Map a SAFE type name to the SAFE_KIND_* constant string used in SAFEHeader.kind. Returns {@link
   * #KIND_NONE} for scalar / value types.
   */
  public String kindOf(final String type) {
    if (type == null) return KIND_NONE;
    if (type.startsWith("list<")) return KIND_LIST;
    if (type.startsWith("map<")) return KIND_MAP;
    if (type.startsWith("set<")) return KIND_SET;
    if ("bytes".equals(type)) return KIND_BYTES;
    if (type.startsWith("tuple<")) return KIND_TUPLE;
    if (type.startsWith("fn<") || "fn".equals(type)) return KIND_CLOSURE;
    if (recursiveEnums.contains(type)) return KIND_ENUM;
    return KIND_NONE;
  }

  /** Integer code variant of {@link #kindOf}, for WASM emission. */
  public int kindCodeOf(final String type) {
    return switch (kindOf(type)) {
      case KIND_LIST -> KIND_LIST_CODE;
      case KIND_MAP -> KIND_MAP_CODE;
      case KIND_BYTES -> KIND_BYTES_CODE;
      case KIND_TUPLE -> KIND_TUPLE_CODE;
      case KIND_ENUM -> KIND_ENUM_CODE;
      case KIND_SET -> KIND_SET_CODE;
      default -> 0;
    };
  }

  /**
   * Is the expression a "fresh owning" producer — its result is a refs==1 allocation whose first
   * slot takes ownership without a retain? Covers direct function calls, object/enum constructions,
   * and ternaries whose arms are all fresh. Variable references are NOT fresh — they're borrowed
   * references and assignment needs to retain.
   */
  public boolean isFreshProducer(final ASTNode node) {
    if (node instanceof FunctionCallNode) return true;
    if (node instanceof ObjectCreationNode) return true;
    if (node instanceof IfExpressionNode ifExpr) {
      return isFreshProducer(ifExpr.then())
          && (!ifExpr.hasOtherwise() || isFreshProducer(ifExpr.otherwise()));
    }
    return false;
  }

  /**
   * Compute the OBJECT-kind meta bitmap for a struct declaration: bit N is set iff field N has a
   * heap-RC type. Fields 0..7 fit in the fast-path byte; types with more than 8 heap fields need a
   * side-table extension (not yet emitted — documented in safe_refcount.h).
   */
  public int fieldBitmap(final TypeDeclarationNode struct) {
    if (struct == null) return 0;
    return bitmapOverFields(struct.fields());
  }

  /**
   * Same as {@link #fieldBitmap(TypeDeclarationNode)} but takes the raw field list, so callers that
   * already walk fields don't need to look the declaration back up.
   */
  public int bitmapOverFields(final List<FieldDeclarationNode> fields) {
    int bits = 0;
    final int limit = Math.min(fields.size(), 8);
    for (int i = 0; i < limit; i++) {
      if (isHeap(fields.get(i).type().fullName())) {
        bits |= 1 << i;
      }
    }
    return bits;
  }

  /**
   * Compute a meta bitmap for any sequence of heap-or-scalar slots (variant payload fields, closure
   * captures, tuple elements). {@code slotTypes} is the SAFE type name per slot in declaration
   * order; slots beyond index 7 are not encoded.
   */
  public int bitmapOverTypes(final List<String> slotTypes) {
    int bits = 0;
    final int limit = Math.min(slotTypes.size(), 8);
    for (int i = 0; i < limit; i++) {
      if (isHeap(slotTypes.get(i))) {
        bits |= 1 << i;
      }
    }
    return bits;
  }

  /**
   * The struct declaration for {@code typeName}, or null if not a known struct. Callers that want
   * to release per-field heap members at scope exit use this to walk the declared fields.
   */
  public TypeDeclarationNode struct(final String typeName) {
    return structs.get(typeName);
  }
}
