package io.safelang.compiler.c;

import io.safelang.ModuleRegistry;
import io.safelang.ast.EnumDeclarationNode;
import io.safelang.ast.FunctionDeclarationNode;
import io.safelang.ast.TypeDeclarationNode;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Read-only view of {@link CCodeGenerator}'s declaration tables and current scope, exposed to
 * {@link CTypeInferer} so it can resolve the SAFE type of any AST node without depending on the
 * visitor's concrete state.
 *
 * <p>Mirrors the {@link CLambdaContext} pattern: small interface, named inner adapter inside the
 * visitor.
 */
interface CInferContext {

  /** Local variable names → SAFE type strings for the current frame. */
  Map<String, String> variables();

  /** All known struct definitions, keyed by SAFE type name. */
  Map<String, TypeDeclarationNode> structs();

  /** All known enum definitions, keyed by SAFE type name. */
  Map<String, EnumDeclarationNode> enumerations();

  /**
   * All known function definitions in the current compilation unit, keyed by name (or mangled
   * name).
   */
  Map<String, FunctionDeclarationNode> functions();

  /** Set of imported module names (used for resolving qualified references). */
  Set<String> modules();

  /** Module registry for cross-module function lookup; may be {@code null}. */
  ModuleRegistry registry();

  /** The module currently being emitted, or {@code null} at top level. */
  String currentModule();

  /** Apply C name mangling for module-qualified symbols: {@code safe__module_name}. */
  String mangle(String module, String name);

  // === Type-string utilities (kept on the visitor; the inferer needs them too) ===

  /** Return the value type for {@code map<K, V>} → {@code V}. */
  String valued(String type);

  /** Return the key type for {@code map<K, V>} → {@code K}. */
  String keyed(String type);

  /** Return the element type for {@code list<T>} → {@code T}. */
  String inner(String type);

  /** Return the Nth element type for {@code tuple<T1, T2, ...>}. */
  String tuple(String type, io.safelang.ast.ASTNode index);

  /** Split a comma-separated type list, respecting angle-bracket nesting. */
  List<String> params(String fnType);
}
