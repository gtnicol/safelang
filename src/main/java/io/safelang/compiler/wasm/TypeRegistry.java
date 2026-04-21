package io.safelang.compiler.wasm;

import io.safelang.ModuleRegistry;
import io.safelang.analyzer.ImportResolver;
import io.safelang.ast.EnumDeclarationNode;
import io.safelang.ast.ProgramNode;
import io.safelang.ast.TypeDeclarationNode;
import io.safelang.ast.TypeNode;
import io.safelang.compiler.CompilerException;
import java.util.*;

/**
 * Global type ID coordinator for the WASM backend.
 *
 * <p>Built once before any module is compiled. Assigns globally unique type IDs to all enums and
 * structs across all modules (including the main program). Individual module compilations receive
 * this as a read-only input.
 *
 * <p>This is the single source of truth for type identity. There are no fallback lookups — if a
 * type is not registered, it does not exist.
 */
public final class TypeRegistry {

  /** Module name for top-level (main program) declarations. */
  public static final String MAIN = "__main__";

  // Enum storage: (module, enumName) → declaration
  private final Map<SymbolKey, EnumDeclarationNode> enums = new LinkedHashMap<>();
  // Enum type IDs: (module, enumName) → globally unique ID
  private final Map<SymbolKey, Integer> types = new LinkedHashMap<>();
  // Struct storage: (module, structName) → declaration
  private final Map<SymbolKey, TypeDeclarationNode> structs = new LinkedHashMap<>();
  // Struct field lists: (module, structName) → ordered field names
  private final Map<SymbolKey, List<String>> fields = new LinkedHashMap<>();
  // Struct type IDs (object IDs): (module, structName) → globally unique ID
  private final Map<SymbolKey, Integer> objects = new LinkedHashMap<>();
  // Reverse lookup: bare variant name → list of owning enum keys
  private final Map<String, List<SymbolKey>> owners = new LinkedHashMap<>();
  // Reverse lookup: bare nominal type name → all owning keys (enums + structs)
  private final Map<String, List<SymbolKey>> nominals = new LinkedHashMap<>();
  // Module import scopes used for resolving bare nominal names safely.
  private final Map<String, List<String>> importedModules = new LinkedHashMap<>();
  private final Map<String, Map<String, List<String>>> selectiveImports = new LinkedHashMap<>();
  // Recursive enum detection
  private final Set<SymbolKey> recursive = new LinkedHashSet<>();
  private int enumId = 0;
  private int objectId = 0;

  /**
   * Build a TypeRegistry by scanning all modules in the given registry, plus the main program's own
   * declarations.
   */
  public static TypeRegistry build(final ModuleRegistry registry, final ProgramNode program) {
    final var result = new TypeRegistry();

    // Phase 1: register all module types
    for (final var module : registry.modules()) {
      final var source = registry.program(module);
      if (source == null) {
        continue;
      }
      result.registerImports(module, source);
      for (final var declaration : source.declarations()) {
        if (declaration instanceof EnumDeclarationNode enumeration) {
          result.register(module, enumeration);
        } else if (declaration instanceof TypeDeclarationNode type) {
          result.registerStruct(module, type);
        }
      }
    }

    // Phase 2: register main program types
    result.registerImports(MAIN, program);
    for (final var declaration : program.declarations()) {
      if (declaration instanceof EnumDeclarationNode enumeration) {
        result.register(MAIN, enumeration);
      } else if (declaration instanceof TypeDeclarationNode type) {
        result.registerStruct(MAIN, type);
      }
    }

    // Phase 3: detect recursive enums
    result.detectRecursive();

    return result;
  }

  private void registerImports(final String module, final ProgramNode program) {
    final var imported = new ArrayList<String>();
    for (final var entry : program.imports()) {
      imported.add(entry.module());
    }
    final var folded = ImportResolver.fold(program.imports());
    final var selective = new LinkedHashMap<String, List<String>>();
    for (final var entry : folded.entrySet()) {
      selective.put(entry.getKey(), List.copyOf(entry.getValue()));
    }
    importedModules.put(module, List.copyOf(imported));
    selectiveImports.put(module, Collections.unmodifiableMap(selective));
  }

  private void register(final String module, final EnumDeclarationNode enumeration) {
    final var key = new SymbolKey(module, enumeration.name());
    if (enums.containsKey(key)) {
      return; // already registered (e.g. re-imported)
    }
    enums.put(key, enumeration);
    types.put(key, enumId++);
    nominals.computeIfAbsent(enumeration.name(), ignored -> new ArrayList<>()).add(key);

    for (final var variant : enumeration.variants()) {
      owners.computeIfAbsent(variant.name(), k -> new ArrayList<>()).add(key);
    }
  }

  private void registerStruct(final String module, final TypeDeclarationNode type) {
    final var key = new SymbolKey(module, type.name());
    if (structs.containsKey(key)) {
      return;
    }
    structs.put(key, type);
    objects.put(key, objectId++);
    nominals.computeIfAbsent(type.name(), ignored -> new ArrayList<>()).add(key);

    final var names = new ArrayList<String>();
    for (final var field : type.fields()) {
      names.add(field.name());
    }
    fields.put(key, Collections.unmodifiableList(names));
  }

  /** Detect direct and mutual recursion between enums within the same module. */
  private void detectRecursive() {
    final var edges = new LinkedHashMap<SymbolKey, Set<SymbolKey>>();
    for (final var entry : enums.entrySet()) {
      final var key = entry.getKey();
      final var references = new LinkedHashSet<SymbolKey>();
      for (final var variant : entry.getValue().variants()) {
        for (final var field : variant.fields()) {
          final var target = localEnumReference(key, field);
          if (target != null) {
            references.add(target);
          }
        }
      }
      edges.put(key, references);
    }

    for (final var key : edges.keySet()) {
      if (reaches(edges, key, key, new LinkedHashSet<>())) {
        recursive.add(key);
      }
    }
  }

  private boolean reaches(
      final Map<SymbolKey, Set<SymbolKey>> edges,
      final SymbolKey start,
      final SymbolKey current,
      final Set<SymbolKey> seen) {
    for (final var next : edges.getOrDefault(current, Set.of())) {
      if (next.equals(start)) {
        return true;
      }
      if (seen.add(next) && reaches(edges, start, next, seen)) {
        return true;
      }
    }
    return false;
  }

  private SymbolKey localEnumReference(final SymbolKey owner, final TypeNode type) {
    if (type == null || type.isVariable() || type.isUnion()) {
      return null;
    }
    if (type.isQualified()) {
      if (type.parts().size() < 2 || !owner.module().equals(type.parts().getFirst())) {
        return null;
      }
      final var key = new SymbolKey(owner.module(), type.parts().getLast());
      return enums.containsKey(key) ? key : null;
    }
    final var key = new SymbolKey(owner.module(), type.name());
    return enums.containsKey(key) ? key : null;
  }

  private SymbolKey directNominal(final String module, final String name) {
    if (module == null || name == null) {
      return null;
    }
    final var key = new SymbolKey(module, name);
    final var hasEnum = enums.containsKey(key);
    final var hasStruct = structs.containsKey(key);
    if (hasEnum && hasStruct) {
      throw new CompilerException("Ambiguous type '" + name + "' in module '" + module + "'");
    }
    return hasEnum || hasStruct ? key : null;
  }

  /**
   * Resolve a nominal enum/struct type name in the given module context.
   *
   * <p>Module-local matches win. Otherwise the bare name must be globally unique.
   */
  public SymbolKey nominal(final String module, final String name) {
    if (name == null || name.isBlank()) {
      return null;
    }
    final var direct = directNominal(module, name);
    if (direct != null) {
      return direct;
    }

    final var imported = importedNominal(module, name);
    if (imported != null) {
      return imported;
    }

    final var candidates = nominals.getOrDefault(name, List.of());
    if (candidates.isEmpty()) {
      return null;
    }
    if (candidates.size() == 1) {
      return candidates.getFirst();
    }
    throw new CompilerException("Ambiguous type: " + name);
  }

  private SymbolKey importedNominal(final String module, final String name) {
    if (module == null) {
      return null;
    }

    final var imports = importedModules.getOrDefault(module, List.of());
    if (imports.isEmpty()) {
      return null;
    }

    final var selective = selectiveImports.getOrDefault(module, Map.of());
    final var matches = new ArrayList<SymbolKey>();
    for (final var imported : imports) {
      final var symbols = selective.get(imported);
      if (symbols != null && !symbols.contains(name)) {
        continue;
      }
      final var candidate = directNominal(imported, name);
      if (candidate != null) {
        matches.add(candidate);
      }
    }

    if (matches.isEmpty()) {
      return null;
    }
    if (matches.size() == 1) {
      return matches.getFirst();
    }
    throw new CompilerException(
        "Ambiguous imported type '" + name + "' in module '" + module + "'");
  }

  /** Resolve a nominal enum/struct type from a type reference. */
  public SymbolKey nominal(final String module, final TypeNode type) {
    if (type == null || type.isVariable() || type.isUnion()) {
      return null;
    }
    if (type.isQualified()) {
      if (type.parts().size() < 2) {
        return null;
      }
      return directNominal(type.parts().getFirst(), type.parts().getLast());
    }
    return nominal(module, type.name());
  }

  /** Get the globally unique type ID for an enum. Returns -1 if not found. */
  public int type(final String module, final String name) {
    final var id = types.get(new SymbolKey(module, name));
    return id != null ? id : -1;
  }

  // === Query methods ===

  /** Get the globally unique type ID for an enum by key. Returns -1 if not found. */
  public int type(final SymbolKey key) {
    final var id = types.get(key);
    return id != null ? id : -1;
  }

  /** Get the globally unique object ID for a struct. Returns -1 if not found. */
  public int object(final String module, final String name) {
    final var id = objects.get(new SymbolKey(module, name));
    return id != null ? id : -1;
  }

  /** Get the globally unique object ID for a struct by key. Returns -1 if not found. */
  public int object(final SymbolKey key) {
    final var id = objects.get(key);
    return id != null ? id : -1;
  }

  /** Get the enum declaration. Returns null if not found. */
  public EnumDeclarationNode enumeration(final String module, final String name) {
    return enums.get(new SymbolKey(module, name));
  }

  /** Get the enum declaration by key. Returns null if not found. */
  public EnumDeclarationNode enumeration(final SymbolKey key) {
    return enums.get(key);
  }

  /** Get all enums registered for a given module. */
  public Map<SymbolKey, EnumDeclarationNode> enums(final String module) {
    final var result = new LinkedHashMap<SymbolKey, EnumDeclarationNode>();
    for (final var entry : enums.entrySet()) {
      if (entry.getKey().module().equals(module)) {
        result.put(entry.getKey(), entry.getValue());
      }
    }
    return Collections.unmodifiableMap(result);
  }

  /** Get the struct declaration. Returns null if not found. */
  public TypeDeclarationNode struct(final String module, final String name) {
    return structs.get(new SymbolKey(module, name));
  }

  /** Get the struct declaration by key. Returns null if not found. */
  public TypeDeclarationNode struct(final SymbolKey key) {
    return structs.get(key);
  }

  /** Get the ordered field names for a struct. Returns null if not found. */
  public List<String> fields(final String module, final String name) {
    return fields.get(new SymbolKey(module, name));
  }

  /** Get the ordered field names for a struct by key. Returns null if not found. */
  public List<String> fields(final SymbolKey key) {
    return fields.get(key);
  }

  /** Get the field index within a struct. Returns -1 if not found. */
  public int field(final String module, final String name, final String target) {
    final var list = fields.get(new SymbolKey(module, name));
    return list != null ? list.indexOf(target) : -1;
  }

  /** Get the field index within a struct by key. Returns -1 if not found. */
  public int field(final SymbolKey key, final String target) {
    final var list = fields.get(key);
    return list != null ? list.indexOf(target) : -1;
  }

  /** Resolve the nominal type of a struct field, or null for primitive/container fields. */
  public SymbolKey fieldType(final SymbolKey key, final String target) {
    final var struct = structs.get(key);
    if (struct == null) {
      return null;
    }
    for (final var field : struct.fields()) {
      if (field.name().equals(target)) {
        return nominal(key.module(), field.type());
      }
    }
    return null;
  }

  /** Resolve the nominal type of an enum variant field, or null when it is not nominal. */
  public SymbolKey variantFieldType(
      final SymbolKey key, final int variantIndex, final int fieldIndex) {
    final var enumeration = enums.get(key);
    if (enumeration == null || variantIndex < 0 || variantIndex >= enumeration.variants().size()) {
      return null;
    }
    final var variant = enumeration.variants().get(variantIndex);
    if (fieldIndex < 0 || fieldIndex >= variant.fields().size()) {
      return null;
    }
    return nominal(key.module(), variant.fields().get(fieldIndex));
  }

  /** Check if an enum is recursive (self-referencing). */
  public boolean recursive(final SymbolKey key) {
    return recursive.contains(key);
  }

  /** Check if an enum is recursive by module and name. */
  public boolean recursive(final String module, final String name) {
    return recursive.contains(new SymbolKey(module, name));
  }

  /**
   * Resolve a variant by module context and variant name.
   *
   * <p>Resolution is scope-respecting and mirrors {@link #nominal(String, String)}:
   *
   * <ol>
   *   <li>Module-local enums in {@code module} are checked first.
   *   <li>Then directly imported modules (honoring selective imports). Transitive imports are
   *       <em>not</em> consulted.
   *   <li>If {@code module} is null (no calling-module context), the bare name must be globally
   *       unique.
   * </ol>
   *
   * @param module the module context (nullable — null means search all)
   * @param name the variant name
   * @return the resolved variant, or null if no enum in scope contains this variant name
   * @throws CompilerException if the variant name is ambiguous within the resolution scope
   */
  public Variant variant(final String module, final String name) {
    if (name == null) {
      return null;
    }
    final var candidates = owners.getOrDefault(name, List.of());
    if (candidates.isEmpty()) {
      return null;
    }

    // No module context: global resolution. Used by top-level lookups where
    // there is no calling-module scope to honour.
    if (module == null) {
      if (candidates.size() == 1) {
        return resolve(candidates.getFirst(), name);
      }
      throw new CompilerException("Ambiguous variant: " + name);
    }

    // Module-local match wins over imports.
    final var local = new ArrayList<SymbolKey>();
    for (final var key : candidates) {
      if (key.module().equals(module)) {
        local.add(key);
      }
    }
    if (local.size() == 1) {
      return resolve(local.getFirst(), name);
    }
    if (local.size() > 1) {
      throw new CompilerException("Ambiguous variant '" + name + "' in module '" + module + "'");
    }

    // Imported modules only — no transitive fallback through the global owners map.
    return importedVariant(module, name, candidates);
  }

  private Variant importedVariant(
      final String module, final String name, final List<SymbolKey> candidates) {
    final var imports = importedModules.getOrDefault(module, List.of());
    if (imports.isEmpty()) {
      return null;
    }
    final var selective = selectiveImports.getOrDefault(module, Map.of());
    final var matches = new ArrayList<SymbolKey>();
    for (final var imported : imports) {
      final var symbols = selective.get(imported);
      if (symbols != null && !symbols.contains(name)) {
        continue;
      }
      for (final var candidate : candidates) {
        if (candidate.module().equals(imported)) {
          matches.add(candidate);
        }
      }
    }
    if (matches.isEmpty()) {
      return null;
    }
    if (matches.size() == 1) {
      return resolve(matches.getFirst(), name);
    }
    throw new CompilerException(
        "Ambiguous imported variant '" + name + "' in module '" + module + "'");
  }

  /**
   * Resolve a variant within a specific known enum.
   *
   * @param key the enum's SymbolKey
   * @param name the variant name
   * @return the resolved variant, or null if the enum doesn't contain this variant
   */
  public Variant resolve(final SymbolKey key, final String name) {
    final var enumeration = enums.get(key);
    if (enumeration == null) {
      return null;
    }
    final var id = types.get(key);
    for (var i = 0; i < enumeration.variants().size(); i++) {
      final var variant = enumeration.variants().get(i);
      if (variant.name().equals(name)) {
        return new Variant(key, id, i, variant.fields().size());
      }
    }
    return null;
  }

  /**
   * Find the SymbolKey for the enum that owns a given variant, scoped to a module. Returns null if
   * no enum in the specified module contains the variant.
   */
  public SymbolKey owner(final String module, final String name) {
    final var candidates = owners.getOrDefault(name, List.of());
    for (final var key : candidates) {
      if (key.module().equals(module)) {
        return key;
      }
    }
    return null;
  }

  /** Get all registered enum keys. */
  public Set<SymbolKey> enumKeys() {
    return Collections.unmodifiableSet(enums.keySet());
  }

  /** Get all registered struct keys. */
  public Set<SymbolKey> structKeys() {
    return Collections.unmodifiableSet(structs.keySet());
  }

  /** Total number of registered enum types. */
  public int enumCount() {
    return enums.size();
  }

  /** Total number of registered struct types. */
  public int structCount() {
    return structs.size();
  }

  /** Result of resolving a variant: its owning enum's type ID and the variant's index. */
  public record Variant(SymbolKey owner, int type, int index, int arity) {}
}
