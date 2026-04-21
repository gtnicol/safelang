package io.safelang.compiler.wasm;

import java.util.*;

final class WasmCompilationState {

  final String name;
  final boolean main;
  final Map<String, Integer> strings = new LinkedHashMap<>();
  final Deque<Map<String, Integer>> scopes = new ArrayDeque<>();
  final Deque<Map<String, SymbolKey>> typeScopes = new ArrayDeque<>();
  final List<Runnable> deferred = new ArrayList<>();

  /**
   * Arity of each registered builtin stub. Builtin stubs live separately from user-defined
   * functions: user functions and their arities are recorded in {@link ModuleSymbols}, but builtin
   * stubs are an internal artefact of the WASM lowering and don't belong in the symbol table.
   */
  final Map<String, Integer> stubArities = new LinkedHashMap<>();

  /**
   * Cross-module function imports keyed by qualified name {@code "module$name"}. This is
   * intentionally separate from {@link ModuleSymbols#imports()}: the symbol table tracks imports by
   * bare name, but we need a fully-qualified key here so that two imported modules exporting a
   * same-name function (e.g. {@code io:println} vs {@code pretty:println}) don't collide.
   */
  final Map<String, Integer> moduleImports = new LinkedHashMap<>();

  final Map<String, Integer> moduleImportArities = new LinkedHashMap<>();
  final int[] callImports = new int[9];
  final Map<String, Integer> builtins = new LinkedHashMap<>();
  final Map<String, Integer> stubs = new LinkedHashMap<>();
  final List<Integer> tableEntries = new ArrayList<>();
  final Map<Integer, Integer> funcToTableIdx = new LinkedHashMap<>();
  final Map<Integer, List<String>> lambdaCaptures = new LinkedHashMap<>();
  final Map<Integer, List<SymbolKey>> lambdaCaptureTypes = new LinkedHashMap<>();

  /**
   * Per-scope primitive type tags for variables, parallel to {@link #scopes}. The string is a SAFE
   * primitive type name like {@code "string"} / {@code "int"} / {@code "float"} / {@code "bool"} —
   * used to drive monomorphic operator dispatch in the WASM compiler. Nominal (struct/enum) types
   * live in {@link #typeScopes} as before.
   */
  final Deque<Map<String, String>> primitiveScopes = new ArrayDeque<>();

  int dataOffset;
  boolean inFunction;
  int lambdaCounter;
  int tableOffset;
  // === Per-function contract emission state (set in emitFunctionBody) ===
  // These are scratch fields that {@link WasmCompiler#visitReturn} consults
  // when the function being emitted has an active contract. They are reset
  // before each function body is emitted; outside emitFunctionBody they are
  // meaningless.
  boolean ensuresActive;
  boolean decreasesActive;
  int resultLocal = -1;
  int savedDecreasesLocal = -1;
  int decreasesGlobal = -1;
  io.safelang.ast.ASTNode currentEnsures;
  String currentFunctionName;

  /**
   * Top-level statements of the function currently being emitted. Used by visitReturn and the
   * fall-through epilogue to release heap-tagged body locals before return. {@code null} outside
   * emitFunctionBody.
   */
  List<io.safelang.ast.ASTNode> currentFunctionBody;

  WasmCompilationState(
      final String name, final boolean main, final int dataOffset, final int tableOffset) {
    this.name = name;
    this.main = main;
    this.dataOffset = dataOffset;
    this.tableOffset = tableOffset;
  }

  String moduleKey() {
    return main ? TypeRegistry.MAIN : name;
  }

  int dataEnd() {
    return (dataOffset + 7) & ~7;
  }

  int tableEnd() {
    return tableOffset + tableEntries.size();
  }

  void pushScope() {
    scopes.push(new LinkedHashMap<>());
    typeScopes.push(new LinkedHashMap<>());
    primitiveScopes.push(new LinkedHashMap<>());
  }

  void popScope() {
    scopes.pop();
    typeScopes.pop();
    primitiveScopes.pop();
  }

  Map<String, String> primitiveScope() {
    return primitiveScopes.peek();
  }

  String resolvePrimitive(final String name) {
    for (final var scope : primitiveScopes) {
      final var type = scope.get(name);
      if (type != null) {
        return type;
      }
    }
    return null;
  }

  Map<String, Integer> scope() {
    return scopes.peek();
  }

  Map<String, SymbolKey> typeScope() {
    return typeScopes.peek();
  }

  int resolveLocal(final String name) {
    for (final var scope : scopes) {
      final var index = scope.get(name);
      if (index != null) {
        return index;
      }
    }
    return -1;
  }

  SymbolKey resolveValueType(final String name) {
    for (final var scope : typeScopes) {
      final var type = scope.get(name);
      if (type != null) {
        return type;
      }
    }
    return null;
  }

  int allocLocal(final WasmFunction function, final String name) {
    return allocLocal(function, name, null);
  }

  int allocLocal(final WasmFunction function, final String name, final SymbolKey type) {
    final var index = function.addLocal(WasmOpcode.TYPE_I64);
    scope().put(name, index);
    if (type != null) {
      typeScope().put(name, type);
    }
    return index;
  }
}
