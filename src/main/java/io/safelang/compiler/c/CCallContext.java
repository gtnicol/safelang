package io.safelang.compiler.c;

import io.safelang.ModuleRegistry;
import io.safelang.ast.ASTNode;
import io.safelang.ast.EnumDeclarationNode;
import io.safelang.ast.FunctionDeclarationNode;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Context exposed to {@link CCallCompiler} for emitting SAFE function calls.
 *
 * <p>Function-call dispatch is the most cross-cutting cluster in the C backend: it touches the
 * imported-modules set, the function/enum registries, the current scope's variable map, the
 * registered builtins, the chained-default-argument alias map, and the visitor's name mangler. The
 * context surface is correspondingly wide.
 */
interface CCallContext {

  /** Recursively compile an AST node back through the top-level visitor. */
  String emit(ASTNode node);

  /** Translate a SAFE type to its C type. */
  String translate(String type);

  /** Best-effort SAFE type inference. */
  String infer(ASTNode node);

  /**
   * Whether {@code node} produces a FRESH, OWNED heap value (a +1 the receiver must release) — used
   * to release throwaway heap temporaries passed to a borrowing function.
   */
  boolean isFreshHeap(ASTNode node);

  /** {@code fn<P1, P2, ..., R>} → ordered list of all type parameters. */
  List<String> params(String fnType);

  /** Local variable type map for the current frame. */
  Map<String, String> variables();

  /** Function registry — keyed by both bare and mangled names. */
  Map<String, FunctionDeclarationNode> functions();

  /** Enum registry. */
  Map<String, EnumDeclarationNode> enumerations();

  /** Set of enums imported into the current module (for collision tie-breaking). */
  Set<String> imported();

  /** Module-imported names (used to detect prefixed calls). */
  Set<String> modules();

  /** Set of mangled function names that have been declared in the output unit. */
  Set<String> emitted();

  /** Registry of cross-module functions; may be {@code null}. */
  ModuleRegistry registry();

  /** The module currently being emitted, or {@code null} at top level. */
  String currentModule();

  /** Apply C name mangling for module-qualified symbols ({@code safe__module_name}). */
  String mangle(String module, String name);

  /** Apply C name mangling for top-level user functions. */
  String mangle(String name);

  /** Delegate to the builtin resolver. */
  String resolveBuiltin(String name, List<ASTNode> arguments);

  /**
   * Wrap a function-call argument expression so that, when the arg is a user-struct alias (variable
   * reference) with heap-refcounted fields, those fields are retained before the call. The callee
   * releases struct-param heap fields at function exit — together these keep the refcount balanced
   * across C's implicit struct-copy on argument pass. Returns {@code argCode} unchanged when no
   * wrap is needed.
   */
  String wrapStructArgForCall(String argCode, ASTNode argNode);

  // === Chained-default-argument alias map ===

  /** Read the active parameter-name → temp-variable alias map. */
  Map<String, String> aliases();

  /** Replace the active alias map (used by {@code pad} for nested defaults). */
  void aliases(Map<String, String> active);
}
