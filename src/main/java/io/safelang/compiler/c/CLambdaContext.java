package io.safelang.compiler.c;

import io.safelang.ast.ASTNode;
import io.safelang.ast.LambdaNode;
import java.util.List;
import java.util.Map;

interface CLambdaContext {

  /** Mangle a user-supplied identifier to avoid collision with C reserved words. */
  String user(String name);

  /**
   * Emit {@code safe_retain(lvalue.fieldN);} for each heap-RC field of the given struct type, or
   * empty string if the type isn't a user struct or has no heap-RC fields. Used by the
   * closure-capture path so captured structs own their own counted references to heap fields.
   */
  String retainStructFields(String lvalue, String type);

  boolean has(LambdaNode node);

  String name(LambdaNode node);

  void name(LambdaNode node, String name);

  List<String> captures(LambdaNode node);

  void captures(LambdaNode node, List<String> captures);

  String next();

  Map<String, String> variables();

  String translate(String type);

  boolean struct(String type);

  boolean enumeration(String type);

  /**
   * True when the type is a heap-RC kind (list/map/set/bytes/recursive enum). Closure captures of
   * such types bump the refcount on store and release it when the closure disposes.
   */
  boolean isHeapRc(String type);

  String infer(ASTNode node);

  String body(LambdaNode node);

  void define(String code);

  void indent(StringBuilder builder);

  void pad(StringBuilder builder);
}
