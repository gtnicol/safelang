package io.safelang.compiler.wasm;

import io.safelang.ast.FunctionDeclarationNode;
import java.util.*;

/**
 * Per-module symbol table for WASM compilation.
 *
 * <p>Each SAFE module being compiled gets its own ModuleSymbols instance. Symbols are categorized
 * as local (private to this module's .wasm), exported (public functions/globals visible to
 * importers), or imported (functions/globals from other modules this module depends on).
 *
 * <p>Resolution order: local → exported → imported. No fallback scanning. If a symbol is not found,
 * the caller gets empty/null — not a guess.
 */
public final class ModuleSymbols {

  // Local (private) functions — not exported
  private final Map<String, Integer> local = new LinkedHashMap<>();
  private final Map<String, Integer> localArity = new LinkedHashMap<>();
  // Exported (public) functions
  private final Map<String, Integer> exported = new LinkedHashMap<>();
  private final Map<String, Integer> exportedArity = new LinkedHashMap<>();
  // Imported functions from other modules
  private final Map<String, Import> imported = new LinkedHashMap<>();
  private final Map<String, Integer> importedArity = new LinkedHashMap<>();
  // Declared nominal value types by variable name
  private final Map<String, SymbolKey> types = new LinkedHashMap<>();
  // Original AST declarations for user-defined functions, indexed by name.
  // Used by the compiler to resolve return types, default arguments, etc.
  private final Map<String, FunctionDeclarationNode> declarations = new LinkedHashMap<>();

  /** Register a local (private) function. */
  public void addLocal(final String name, final int index, final int arity) {
    local.put(name, index);
    localArity.put(name, arity);
  }

  // === Registration ===

  /** Register an exported (public) function. */
  public void addExport(final String name, final int index, final int arity) {
    exported.put(name, index);
    exportedArity.put(name, arity);
  }

  /** Register an imported function from another module. */
  public void addImport(final String module, final String name, final int index, final int arity) {
    imported.put(name, new Import(module, name, index));
    importedArity.put(name, arity);
  }

  /**
   * Register an imported function with a local alias (for selective imports or when the import name
   * differs from the usage name).
   */
  public void addImport(
      final String module,
      final String name,
      final String alias,
      final int index,
      final int arity) {
    imported.put(alias, new Import(module, name, index));
    importedArity.put(alias, arity);
  }

  /** Record the declared enum/struct type of a value. */
  public void declare(final String variable, final SymbolKey type) {
    types.put(variable, type);
  }

  /** Attach the AST declaration for a previously-registered function. */
  public void attach(final String name, final FunctionDeclarationNode declaration) {
    declarations.put(name, declaration);
  }

  /** Look up the AST declaration for a function. Returns null if absent. */
  public FunctionDeclarationNode declaration(final String name) {
    return declarations.get(name);
  }

  /**
   * Resolve a function by name. Searches local → exported → imported. Returns {@link
   * OptionalInt#empty()} if not found.
   */
  public OptionalInt function(final String name) {
    final var result = local.get(name);
    if (result != null) return OptionalInt.of(result);
    final var export = exported.get(name);
    if (export != null) return OptionalInt.of(export);
    final var imp = imported.get(name);
    if (imp != null) return OptionalInt.of(imp.index());
    return OptionalInt.empty();
  }

  // === Resolution ===

  /** Resolve a function's arity by name. Returns {@link OptionalInt#empty()} if not found. */
  public OptionalInt arity(final String name) {
    final var result = localArity.get(name);
    if (result != null) return OptionalInt.of(result);
    final var export = exportedArity.get(name);
    if (export != null) return OptionalInt.of(export);
    final var imp = importedArity.get(name);
    if (imp != null) return OptionalInt.of(imp);
    return OptionalInt.empty();
  }

  /** Resolve an imported function by name. Returns null if not an import. */
  public Import importedFunction(final String name) {
    return imported.get(name);
  }

  /** Get the declared nominal type of a value. Returns null if not tracked. */
  public SymbolKey declared(final String variable) {
    return types.get(variable);
  }

  /** Check if a function is known (local, exported, or imported). */
  public boolean hasFunction(final String name) {
    return local.containsKey(name) || exported.containsKey(name) || imported.containsKey(name);
  }

  /** Check if a function is exported (public). */
  public boolean isExported(final String name) {
    return exported.containsKey(name);
  }

  /** Get all exported function names. */
  public Set<String> exports() {
    return Collections.unmodifiableSet(exported.keySet());
  }

  /** Get all imported function entries. */
  public Collection<Import> imports() {
    return Collections.unmodifiableCollection(imported.values());
  }

  /** An imported symbol: the source module and the WASM function/global index. */
  public record Import(String module, String name, int index) {}
}
