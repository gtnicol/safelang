package io.safelang.compiler.wasm;

import static io.safelang.compiler.wasm.WasmOpcode.*;

import io.safelang.ModuleRegistry;
import io.safelang.ast.*;
import io.safelang.compiler.CompilerException;
import io.safelang.runtime.BuiltinRegistry;
import io.safelang.runtime.SAFEValue;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Per-module WASM compiler — the orchestrator that walks the AST and emits a complete {@code .wasm}
 * binary.
 *
 * <p>Replaces the previous monolithic {@code WasmModuleCompiler}. This class is deliberately a thin
 * top-level visitor that delegates each concern to a dedicated helper:
 *
 * <ul>
 *   <li>{@link WasmCompilationState} — scopes, locals, lambda counter, data offset
 *   <li>{@link WasmRuntimeContext} — runtime / WASI / builtin function indices
 *   <li>{@link WasmTypeResolver} — nominal type lookup
 *   <li>{@link WasmCaseCompiler} — pattern-match emission
 *   <li>{@link WasmObjectCompiler} — struct creation / field access / assignment
 *   <li>{@link WasmLambdaPlanner} / {@link WasmLambdaCompiler} — closures
 *   <li>{@link WasmMapSupport} — map literal emission
 *   <li>{@link WasmBuiltinRegistrar} / {@link WasmBuiltinEmitter} — builtin stubs
 * </ul>
 *
 * <p>This class deliberately does <b>not</b> own state that already lives in a helper. Anything
 * per-compilation lives on {@link WasmCompilationState}; the runtime function indices live on
 * {@link WasmRuntimeContext}; the per-module symbol tables live on {@link ModuleSymbols}.
 *
 * <h2>Status</h2>
 *
 * The reconstruction is in-progress. Visit methods that have not yet been re-implemented throw a
 * clear {@link CompilerException}, so that any test that exercises an unimplemented node fails fast
 * with a useful message instead of producing silently broken bytecode.
 */
public final class WasmCompiler extends AbstractASTVisitor<SymbolKey> {

  // === Construction-time inputs ===
  private final TypeRegistry types;
  private final ModuleSymbols symbols;
  private final ModuleRegistry registry;

  // === Per-compilation state holders ===
  private final WasmCompilationState state;
  private final WasmRuntimeContext runtime = new WasmRuntimeContext();
  private final WasmModule module = new WasmModule();
  private final WasmTypeResolver resolver;

  // === Helpers (constructed lazily once runtime/module are set up) ===
  private final io.safelang.compiler.refcount.RefcountPolicy rc =
      new io.safelang.compiler.refcount.RefcountPolicy(Set.of(), new HashMap<>());
  private WasmBuiltinSupport support;
  private WasmBuiltinEmitter emitter;
  private WasmBuiltinRegistrar registrar;
  private WasmCaseCompiler cases;
  private WasmObjectCompiler objects;
  private WasmLambdaPlanner planner;
  private WasmLambdaCompiler lambdas;
  private WasmMapSupport maps;
  private WasmBinaryEmitter binary;

  // === Visitor scratch state ===
  /** The function currently being emitted into. {@code null} between functions. */
  private WasmFunction current;

  public WasmCompiler(
      final String name,
      final boolean main,
      final TypeRegistry types,
      final ModuleSymbols symbols,
      final ModuleRegistry registry,
      final int dataOffset,
      final int tableOffset) {
    this.types = types;
    this.symbols = symbols;
    this.registry = registry;
    this.state = new WasmCompilationState(name, main, dataOffset, tableOffset);
    this.resolver = new WasmTypeResolver(types, state);
  }

  /** Compile the given program to a complete {@code .wasm} binary. */
  public byte[] compile(final ProgramNode program) {
    final var builder = new WasmRuntimeBuilder(module, state, runtime);

    // Phase A: all imports (host builtins + WASI + cross-module functions).
    // Must happen before any addFunction call so the WASM import/local index
    // boundary is stable.
    builder.importHosts();
    final var importedModules = new LinkedHashSet<String>();
    for (final var imported : program.imports()) {
      importModuleFunctions(imported);
      importedModules.add(imported.module());
    }
    // Also import any module referenced via a `prefix:name(...)` call but not
    // declared explicitly. The interpreter resolves these transitively, so we
    // mirror that behaviour here. Module names of the closure module and
    // builtins are reserved and skipped.
    for (final var module : WasmPrefixCollector.collect(program)) {
      if (importedModules.contains(module)) continue;
      if (!registry.has(module)) continue;
      importModuleFunctions(new ImportNode(0, 0, module));
      importedModules.add(module);
    }

    // Phase B: local helpers, wrappers, and the per-compilation helper objects.
    this.support = builder.emitHelpersAndWrappers();
    setupHelpers();

    // Phase C: walk the AST.
    visitProgram(program);

    return module.assemble();
  }

  /** Final data-section offset after compilation (page-aligned). */
  public int dataEnd() {
    return state.dataEnd();
  }

  /** Final function-table offset after compilation. */
  public int tableEnd() {
    return state.tableEnd();
  }

  // ========== Setup ==========

  /** Construct the per-compilation helper objects once runtime indices are known. */
  private void setupHelpers() {
    this.maps = new WasmMapSupport(runtime);
    this.objects = new WasmObjectCompiler(runtime, types, resolver, new ObjectAdapter());
    this.cases =
        new WasmCaseCompiler(
            runtime,
            types,
            new CaseAdapter(),
            (subject, name) -> resolver.patternVariant(subject, name),
            runtime.valuesEqual);
    this.planner =
        new WasmLambdaPlanner(
            state,
            new LinkedHashMap<>(),
            state.tableEntries,
            state.funcToTableIdx,
            state.lambdaCaptures,
            state.lambdaCaptureTypes,
            new LambdaHooks());
    this.lambdas = new WasmLambdaCompiler();
    this.emitter =
        new WasmBuiltinEmitter(module, runtime, types, state.builtins, support, this::intern);
    this.registrar = new WasmBuiltinRegistrar(state.name, state.main, module, state, symbols);
    this.binary = new WasmBinaryEmitter(runtime, support, state, new BinaryAdapter());
  }

  // ========== Phase helpers ==========

  /** Import every public function and constant from {@code source}. */
  private void importModuleFunctions(final ImportNode source) {
    if (registry.program(source.module()) == null) {
      return; // unknown module — let later resolution fail with a clear message
    }
    for (final var function : registry.functions(source.module()).values()) {
      if (source.isSelective() && !source.symbols().contains(function.name())) continue;

      final var arity = function.parameters().size();
      final var params = new int[arity];
      Arrays.fill(params, WasmOpcode.TYPE_I64);
      final var type = module.addType(params, new int[] {WasmOpcode.TYPE_I64});
      final var index = module.importFunction(source.module(), function.name(), type);
      final var qualified = source.module() + "$" + function.name();
      state.moduleImports.put(qualified, index);
      state.moduleImportArities.put(qualified, arity);
      symbols.addImport(source.module(), function.name(), index, arity);
    }
    for (final var constant : registry.constants(source.module()).values()) {
      // Module constants are exposed as 0-arg accessor functions.
      if (source.isSelective() && !source.symbols().contains(constant.name())) continue;

      final var type = module.addType(new int[] {}, new int[] {WasmOpcode.TYPE_I64});
      final var index = module.importFunction(source.module(), constant.name(), type);
      final var qualified = source.module() + "$" + constant.name();
      state.moduleImports.put(qualified, index);
      state.moduleImportArities.put(qualified, 0);
      symbols.addImport(source.module(), constant.name(), index, 0);
    }
  }

  /** Reserve a function slot for a module-level constant accessor. */
  private void registerConstant(final VariableDeclarationNode constant) {
    final var type = module.addType(new int[] {}, new int[] {WasmOpcode.TYPE_I64});
    final var index = module.addFunction(type);
    if (state.main) {
      // Constants in the main program are local; no export needed.
      symbols.addLocal(constant.name(), index, 0);
    } else {
      module.exportFunction(constant.name(), index);
      symbols.addExport(constant.name(), index, 0);
    }
  }

  /** Emit the body of a previously-registered constant accessor. */
  private void emitConstantBody(final VariableDeclarationNode constant) {
    final var index = symbols.function(constant.name()).orElseThrow();
    final var type = module.addType(new int[] {}, new int[] {WasmOpcode.TYPE_I64});
    current = new WasmFunction(index, type, 0);
    state.pushScope();
    state.inFunction = true;

    if (constant.hasInitializer()) {
      emit(constant.initializer());
    } else {
      current.emitCall(runtime.tagVoid);
    }

    state.inFunction = false;
    state.popScope();
    try {
      module.addCode(index, current.encode(module));
    } catch (final RuntimeException error) {
      throw new CompilerException(
          "WASM backend: failed to encode constant '"
              + constant.name()
              + "' in module '"
              + state.name
              + "': "
              + error.getMessage(),
          error);
    }
    current = null;
  }

  /** First pass: reserve a function slot in the module for {@code function}. */
  private void registerFunction(final FunctionDeclarationNode function) {
    final var arity = function.parameters().size();
    final var params = new int[arity];
    Arrays.fill(params, WasmOpcode.TYPE_I64);
    final var type = module.addType(params, new int[] {WasmOpcode.TYPE_I64});
    final var index = module.addFunction(type);
    if (function.isPublic() || state.main) {
      module.exportFunction(function.name(), index);
      symbols.addExport(function.name(), index, arity);
    } else {
      symbols.addLocal(function.name(), index, arity);
    }
    symbols.attach(function.name(), function);
  }

  /** Second pass: emit the body of a previously-registered function. */
  private void emitFunctionBody(final FunctionDeclarationNode function) {
    final var index = symbols.function(function.name()).orElseThrow();
    final var arity = function.parameters().size();
    final var params = new int[arity];
    Arrays.fill(params, WasmOpcode.TYPE_I64);
    final var type = module.addType(params, new int[] {WasmOpcode.TYPE_I64});

    current = new WasmFunction(index, type, arity);
    state.pushScope();
    state.inFunction = true;

    // Reset per-function contract scratch state.
    state.ensuresActive = false;
    state.decreasesActive = false;
    state.resultLocal = -1;
    state.savedDecreasesLocal = -1;
    state.decreasesGlobal = -1;
    state.currentEnsures = null;
    state.currentFunctionName = function.name();

    // Bind parameters as the first locals (slots 0..arity-1).
    for (var i = 0; i < function.parameters().size(); i++) {
      final var parameter = function.parameters().get(i);
      state.scope().put(parameter.name(), i);
      final var nominal = resolver.nominalType(parameter.type());
      if (nominal != null) {
        state.typeScope().put(parameter.name(), nominal);
      }
      recordPrimitive(parameter.name(), parameter.type());
    }

    // Fill in default arguments for any parameter whose value is tagged void.
    // The caller pads missing arguments with TAG_VOID; the default expression
    // is evaluated here so it can reference earlier parameter locals.
    for (var i = 0; i < function.parameters().size(); i++) {
      final var parameter = function.parameters().get(i);
      if (!parameter.hasDefault()) continue;
      current.emitLocalGet(i);
      current.emitI64Const(WasmRuntime.TAG_VOID);
      current.emit(I64_EQ);
      current.emitIf(WasmOpcode.TYPE_VOID);
      emit(parameter.initial());
      current.emitLocalSet(i);
      current.emit(END);
    }

    // Reject NaN for any float-typed parameter. Matches the interpreter's
    // reject() and the C backend's per-parameter guard (CCodeGenerator:571).
    // We emit the check only when the declared type is `float` or a union
    // containing `float`; other types cannot carry a NaN payload.
    for (var i = 0; i < function.parameters().size(); i++) {
      final var parameter = function.parameters().get(i);
      if (!acceptsFloat(parameter.type())) continue;
      emitNaNReject(i, function.name());
    }

    // === Contract prologue ===
    // requires: trap if the precondition is false.
    if (function.hasRequires()) {
      emit(function.requires());
      current.emitCall(runtime.untagInt);
      current.emit(I64_EQZ); // 1 if false (precondition failed)
      current.emitIf(WasmOpcode.TYPE_VOID);
      final var offset = intern("Requires contract failed for function: " + function.name());
      current.emitI32Const(offset);
      current.emitCall(runtime.trapWithMessage);
      current.emit(END);
    }

    // ensures: allocate a local for the would-be return value, plus a name
    // binding for `result` so the postcondition expression can reference it.
    if (function.hasEnsures()) {
      state.resultLocal = current.addLocal(WasmOpcode.TYPE_I64);
      state.scope().put("result", state.resultLocal);
      state.ensuresActive = true;
      state.currentEnsures = function.ensures();
    }

    // decreases: per-function global + saved local. The global holds the
    // current measure for in-flight calls, using -1 as the "no call active"
    // sentinel. A measure of 0 is a legal value, so 0 cannot double as the
    // sentinel — doing so would let a recursive call from a decreases(0)
    // parent bypass the strict-decrease check entirely (the interpreter and
    // bytecode VM track in-flight via a non-empty stack, not a zero value).
    // The saved local lets each call restore the parent's value on exit.
    if (function.hasDecreases()) {
      state.decreasesGlobal = module.addGlobal(WasmOpcode.TYPE_I64, true, -1L);
      state.savedDecreasesLocal = current.addLocal(WasmOpcode.TYPE_I64);
      state.decreasesActive = true;

      // Save the parent call's measure (or -1 if this is the first call).
      current.emitGlobalGet(state.decreasesGlobal);
      current.emitLocalSet(state.savedDecreasesLocal);

      // Evaluate the new measure into a local so we can re-use it.
      final var measure = current.addLocal(WasmOpcode.TYPE_I64);
      emit(function.decreases());
      current.emitCall(runtime.untagInt);
      current.emitLocalSet(measure);

      // Negative measure → trap.
      current.emitLocalGet(measure);
      current.emitI64Const(0);
      current.emit(I64_LT_S);
      current.emitIf(WasmOpcode.TYPE_VOID);
      current.emitI32Const(
          intern("Decreases measure must be non-negative for: " + function.name()));
      current.emitCall(runtime.trapWithMessage);
      current.emit(END);

      // If a parent call is in-flight (saved >= 0), require strict decrease.
      // Sentinel -1 means "no parent call".
      current.emitLocalGet(state.savedDecreasesLocal);
      current.emitI64Const(0);
      current.emit(I64_GE_S);
      current.emitIf(WasmOpcode.TYPE_VOID);
      current.emitLocalGet(measure);
      current.emitLocalGet(state.savedDecreasesLocal);
      current.emit(I64_GE_S); // new >= saved → trap
      current.emitIf(WasmOpcode.TYPE_VOID);
      current.emitI32Const(intern("Decreases clause not satisfied for: " + function.name()));
      current.emitCall(runtime.trapWithMessage);
      current.emit(END);
      current.emit(END);

      // Install the new measure as the current global value.
      current.emitLocalGet(measure);
      current.emitGlobalSet(state.decreasesGlobal);
    }

    // === Body emission ===
    state.currentFunctionBody = function.body();
    for (final var statement : function.body()) {
      emit(statement);
    }

    // === Fallthrough epilogue ===
    // Functions in SAFE always return a value; if no explicit return was
    // emitted, the natural fallthrough pushes tagged void.
    // Release body-level heap locals before returning — params stay alive
    // for the caller (borrowed-reference convention).
    emitFunctionBodyScopeRelease(null);
    current.emitCall(runtime.tagVoid);
    if (state.ensuresActive || state.decreasesActive) {
      // With contracts active, that fallthrough goes through the same
      // epilogue helper that visitReturn uses, so ensures/decreases
      // handling is uniform.
      emitFunctionEpilogue(/* alsoReturn= */ false);
    }
    state.currentFunctionBody = null;

    // Clean up scratch state. The flags are reset on the next function entry,
    // but clearing here makes it impossible for stale state to leak between
    // functions if a future change forgets the reset.
    state.ensuresActive = false;
    state.decreasesActive = false;
    state.resultLocal = -1;
    state.savedDecreasesLocal = -1;
    state.decreasesGlobal = -1;
    state.currentEnsures = null;
    state.currentFunctionName = null;

    state.inFunction = false;
    state.popScope();
    try {
      module.addCode(index, current.encode(module));
    } catch (final RuntimeException error) {
      throw new CompilerException(
          "WASM backend: failed to encode user function '"
              + function.name()
              + "' in module '"
              + state.name
              + "': "
              + error.getMessage(),
          error);
    }
    current = null;
  }

  /**
   * Emit the contract epilogue for the function currently being compiled. The would-be return value
   * must be on top of the stack on entry; this helper:
   *
   * <ol>
   *   <li>Stores TOS into {@code __result__}.
   *   <li>Evaluates {@code ensures} (if active) with {@code result} bound, trapping if the
   *       postcondition is false.
   *   <li>Restores the parent call's decreases measure (if active).
   *   <li>Pushes {@code __result__} back onto TOS.
   *   <li>If {@code alsoReturn} is true, emits a {@code RETURN} opcode.
   * </ol>
   */
  private void emitFunctionEpilogue(final boolean alsoReturn) {
    if (state.ensuresActive) {
      current.emitLocalSet(state.resultLocal);
      // Evaluate the ensures expression. `result` is already bound in the
      // current scope to point at state.resultLocal (set in emitFunctionBody),
      // so a VariableReferenceNode("result") inside the ensures expression
      // resolves correctly.
      emit(state.currentEnsures);
      current.emitCall(runtime.untagInt);
      current.emit(I64_EQZ); // 1 if false (postcondition failed)
      current.emitIf(WasmOpcode.TYPE_VOID);
      final var offset =
          intern("Ensures contract failed for function: " + state.currentFunctionName);
      current.emitI32Const(offset);
      current.emitCall(runtime.trapWithMessage);
      current.emit(END);
      current.emitLocalGet(state.resultLocal);
    }
    if (state.decreasesActive) {
      // Restore the parent's measure so reentrant callers see the correct
      // value when this function returns.
      current.emitLocalGet(state.savedDecreasesLocal);
      current.emitGlobalSet(state.decreasesGlobal);
    }
    if (alsoReturn) {
      current.emit(RETURN);
    }
  }

  /**
   * Emit the {@code _start} entry point that initialises the heap and runs the program's top-level
   * statements.
   */
  private void emitStart(final ProgramNode program) {
    final var type = module.addType(new int[] {}, new int[] {});
    final var index = module.addFunction(type);
    module.exportFunction("_start", index);

    current = new WasmFunction(index, type, 0);
    state.pushScope();
    state.inFunction = true;

    // safe_set_heap(dataEnd) — initialise the bump allocator past our data.
    current.emitI32Const(state.dataEnd());
    current.emitCall(state.builtins.get("safe_set_heap"));

    // Phase 6.1: interned string literals have SAFE_REFS_IMMORTAL +
    // SAFE_KIND_STRING baked into the data-section SAFEHeader by
    // intern(), so retain/release on them short-circuit without needing
    // a runtime init loop here (which wouldn't reach stdlib modules'
    // data sections anyway — they have no _start).

    // Emit each top-level statement (declarations are handled separately).
    for (final var statement : program.statements()) {
      emit(statement);
    }

    // Phase 0 instrumentation: report peak heap bytes at program end. The
    // builtin is a no-op unless SAFE_HEAP_REPORT is set in the environment.
    current.emitCall(state.builtins.get("safe_heap_report"));

    state.inFunction = false;
    state.popScope();
    try {
      module.addCode(index, current.encode(module));
    } catch (final RuntimeException error) {
      throw new CompilerException(
          "WASM backend: failed to encode _start function in module '"
              + state.name
              + "': "
              + error.getMessage(),
          error);
    }
    current = null;
  }

  /**
   * Intern a string literal into the data section. Layout: [8-byte SAFEHeader][4-byte length][UTF-8
   * bytes] The returned offset points at the length prefix — the same pointer shape the C runtime
   * expects — so {@code safe_header(body)} reads the eight bytes before it and finds a valid
   * SAFEHeader stamped with SAFE_REFS_IMMORTAL and SAFE_KIND_STRING.
   *
   * <p>The header is baked into the data section at compile time so it works across modules: stdlib
   * preloads (strings.wasm, std.wasm, etc.) don't run a _start init routine, so we can't rely on a
   * runtime init loop to stamp the immortal magic. Writing the magic directly makes every module's
   * intern pool consistent on the first retain/release.
   *
   * <p>SAFEHeader bytes 0..3 (refs) = 0xFFFFFFFF little-endian (SAFE_REFS_IMMORTAL), byte 4 (kind)
   * = SAFE_KIND_STRING (8), rest zero.
   */
  private int intern(final String text) {
    final var existing = state.strings.get(text);
    if (existing != null) {
      return existing;
    }
    // Prepend 8 bytes for SAFEHeader. The returned "body" is the length-
    // prefix offset so it keeps the same semantics as before.
    final var headerOffset = state.dataOffset;
    final var bodyOffset = headerOffset + 8;
    final var bytes = text.getBytes(StandardCharsets.UTF_8);
    final var payload = new byte[8 + 4 + bytes.length];
    // SAFEHeader at 0..7: refs=0xFFFFFFFF (IMMORTAL), kind=SAFE_KIND_STRING(8),
    // meta=0, size_class=0.
    payload[0] = (byte) 0xFF;
    payload[1] = (byte) 0xFF;
    payload[2] = (byte) 0xFF;
    payload[3] = (byte) 0xFF;
    payload[4] = (byte) 8; // SAFE_KIND_STRING
    // payload[5..7] stay zero
    payload[8] = (byte) (bytes.length & 0xFF);
    payload[9] = (byte) ((bytes.length >> 8) & 0xFF);
    payload[10] = (byte) ((bytes.length >> 16) & 0xFF);
    payload[11] = (byte) ((bytes.length >> 24) & 0xFF);
    System.arraycopy(bytes, 0, payload, 12, bytes.length);
    module.addData(headerOffset, payload);
    state.strings.put(text, bodyOffset);
    state.dataOffset = headerOffset + payload.length;
    // Round up to 8-byte alignment so subsequent allocations stay aligned.
    state.dataOffset = (state.dataOffset + 7) & ~7;
    return bodyOffset;
  }

  // ========== Visitor entry helper ==========

  /**
   * Compile {@code node} into the active function and return the nominal type of the value it
   * leaves on the stack, or {@code null} if the value has no static nominal type (primitive int,
   * list, void, etc.).
   */
  private SymbolKey emit(final ASTNode node) {
    return node.accept(this);
  }

  private CompilerException unsupported(final String node) {
    return new CompilerException(
        "WASM backend: " + node + " not yet implemented in WasmCompiler reconstruction");
  }

  /** True when {@code type} is {@code float} or a union that includes {@code float}. */
  private static boolean acceptsFloat(final TypeNode type) {
    if (type == null) return false;
    if ("float".equals(type.fullName())) return true;
    if (type.isUnion() && type.members() != null) {
      for (final var member : type.members()) {
        if ("float".equals(member.fullName())) return true;
      }
    }
    return false;
  }

  /**
   * Emit a NaN guard for the parameter at {@code slot}. The parameter is a tagged i64; if the tag
   * is {@code TAG_FLOAT} and the payload is NaN ({@code f != f}), trap with a diagnostic message.
   */
  private void emitNaNReject(final int slot, final String name) {
    // if ((v & 0xF) == TAG_FLOAT) { ... }
    current.emitLocalGet(slot);
    current.emitI64Const(WasmRuntime.TAG_MASK);
    current.emit(I64_AND);
    current.emitI64Const(WasmRuntime.TAG_FLOAT);
    current.emit(I64_EQ);
    current.emitIf(WasmOpcode.TYPE_VOID);

    // f = reinterpret((v & ~TAG_MASK)); if (f != f) trap
    final var raw = current.addLocal(WasmOpcode.TYPE_F64);
    current.emitLocalGet(slot);
    current.emitI64Const(~WasmRuntime.TAG_MASK);
    current.emit(I64_AND);
    current.emit(F64_REINTERPRET_I64);
    current.emitLocalTee(raw);
    current.emitLocalGet(raw);
    current.emit(F64_NE); // NaN != NaN → 1
    current.emitIf(WasmOpcode.TYPE_VOID);
    current.emitI32Const(intern("NaN is not allowed as an argument to function '" + name + "'"));
    current.emitCall(runtime.trapWithMessage);
    current.emit(END);

    current.emit(END);
  }

  // ========== AST visitor methods ==========
  //
  // Each visit method below is a placeholder. They are intentionally explicit
  // (rather than inheriting AbstractASTVisitor's no-op default) so that any
  // accidental use produces a clear failure rather than silent miscompilation.

  @Override
  public SymbolKey visitProgram(final ProgramNode node) {
    // Imports were already added in compile() phase A so the WASM
    // import/local function boundary stays stable. Walk declarations next.

    // Pre-register every user function in this program so call sites emitted
    // later can reference them by index regardless of source order. We also
    // pre-register module constants as 0-arg accessor functions so importing
    // modules can call them by name.
    for (final var declaration : node.declarations()) {
      if (declaration instanceof FunctionDeclarationNode function) {
        registerFunction(function);
      } else if (declaration instanceof VariableDeclarationNode constant) {
        registerConstant(constant);
      }
    }

    // Phase 3: ask the registrar to allocate a stub function for every builtin
    // referenced in this program. The stub bodies are emitted by the deferred
    // queue once all user functions have been registered.
    registrar.register(
        node,
        (name, index, type, arity) -> {
          try {
            emitter.compile(name, index, type, arity);
          } catch (final RuntimeException error) {
            throw new CompilerException(
                "WASM backend: failed to emit builtin stub '"
                    + name
                    + "' (index "
                    + index
                    + ") in module '"
                    + state.name
                    + "': "
                    + error.getMessage(),
                error);
          }
        });

    // Phase 4: emit each user function body and constant accessor body.
    for (final var declaration : node.declarations()) {
      if (declaration instanceof FunctionDeclarationNode function) {
        emitFunctionBody(function);
      } else if (declaration instanceof VariableDeclarationNode constant) {
        emitConstantBody(constant);
      }
    }

    // Phase 5: emit the main entry point if this is the top-level program.
    if (state.main) {
      emitStart(node);
    }

    // Phase 6: drain the deferred queue (builtin stub bodies, lambdas, etc.).
    while (!state.deferred.isEmpty()) {
      final var pending = new ArrayList<>(state.deferred);
      state.deferred.clear();
      for (final var task : pending) {
        try {
          task.run();
        } catch (final RuntimeException error) {
          throw new CompilerException(
              "WASM backend: deferred emission failed in module '"
                  + state.name
                  + "': "
                  + error.getMessage(),
              error);
        }
      }
    }

    // Phase 7: install the lambda function table entries. Each entry binds a
    // table slot to the WASM function index of a planned lambda body.
    for (var i = 0; i < state.tableEntries.size(); i++) {
      module.addElement(state.tableOffset + i, state.tableEntries.get(i));
    }
    return null;
  }

  @Override
  public SymbolKey visitFunctionDeclaration(final FunctionDeclarationNode node) {
    // The two-pass walker in visitProgram already handles function declarations,
    // so this entry point is only reached for nested declarations (which SAFE
    // does not currently allow). Throw to surface any future regression early.
    throw unsupported("nested FunctionDeclarationNode");
  }

  @Override
  public SymbolKey visitVariableDeclaration(final VariableDeclarationNode node) {
    if (node.hasInitializer()) {
      // Heap locals take ownership via emitOwning (handles mixed-arm
      // ternaries). Scalar locals just emit the initializer; retain on
      // scalar tags is a cheap tag-dispatch no-op but the branch into
      // runtime is still worth avoiding.
      if (WasmRefcount.isHeapType(typeFullName(node.type()))) {
        emitOwning(node.initializer());
      } else {
        emit(node.initializer());
      }
    } else {
      current.emitCall(runtime.tagVoid);
    }
    final var slot = state.allocLocal(current, node.name(), resolver.nominalType(node.type()));
    current.emitLocalSet(slot);
    recordPrimitive(node.name(), node.type());
    return null;
  }

  /** Extract SAFE type name from a TypeNode, null-safe. */
  private static String typeFullName(final TypeNode type) {
    return type == null ? null : type.fullName();
  }

  /**
   * Track the SAFE primitive type (if any) for a variable so binary expressions can dispatch on it.
   * Nominal types are tracked separately via {@link WasmCompilationState#typeScope()}.
   */
  private void recordPrimitive(final String name, final TypeNode type) {
    if (type == null) return;
    final var full = type.fullName();
    if (full == null || full.isBlank()) return;
    // Only track simple primitive names; leave compound/generic types alone.
    switch (full) {
      case "int", "uint", "float", "string", "bool", "boolean", "void", "bytes" ->
          state.primitiveScope().put(name, full);
      default -> {}
    }
  }

  @Override
  public SymbolKey visitAssignment(final AssignmentNode node) {
    if (node.parts().size() == 1) {
      final var name = node.parts().getFirst();
      final var local = state.resolveLocal(name);
      if (local < 0) {
        throw new CompilerException("WASM backend: unresolved assignment target '" + name + "'");
      }
      if (mayBeHeap(name)) {
        emitHeapReassignment(local, node.value());
      } else {
        emit(node.value());
        current.emitLocalSet(local);
      }
      return null;
    }
    // Nested field assignment (e.g. `foo.bar = value`) delegates to the
    // object compiler which knows how to walk the field chain.
    objects.assignment(current, node);
    return null;
  }

  /**
   * True when the named local might hold a heap-tagged value that needs RC bookkeeping on
   * reassignment. Skipped for known scalars (int/uint/float/ bool/void); a missing primitive record
   * is assumed heap so RC discipline stays correct even for types we haven't classified statically.
   */
  private boolean mayBeHeap(final String name) {
    final var primitive = state.resolvePrimitive(name);
    if (primitive == null) return true;
    return switch (primitive) {
      case "int", "uint", "float", "bool", "boolean", "void" -> false;
      default -> true;
    };
  }

  /**
   * Emit refcount-balanced reassignment {@code local = rhs}.
   *
   * <p>Strategy depends on rhs shape:
   *
   * <ul>
   *   <li>Ternary — recurse into each arm with the full assignment, so the fresh-vs-aliased
   *       decision matches the branch actually taken.
   *   <li>Fresh producer (function call, constructor) — no retain; guarded release (skip when rhs
   *       returned the same pointer, as happens with {@code list_append} mutating in place).
   *   <li>Aliased expression (variable ref, field access, etc.) — retain, unconditional release.
   *       Self-assign {@code x = x} balances because retain and release cancel.
   * </ul>
   */
  private void emitHeapReassignment(final int local, final ASTNode rhs) {
    if (rhs instanceof IfExpressionNode ifExpr) {
      emit(ifExpr.condition());
      current.emitCall(runtime.untagInt);
      current.emit(I32_WRAP_I64);
      current.emitIf(WasmOpcode.TYPE_VOID);
      emitHeapReassignment(local, ifExpr.then());
      current.emit(ELSE);
      if (ifExpr.hasOtherwise()) {
        emitHeapReassignment(local, ifExpr.otherwise());
      }
      current.emit(END);
      return;
    }
    emit(rhs);
    final var tmp = current.addLocal(WasmOpcode.TYPE_I64);
    if (rc.isFreshProducer(rhs)) {
      // Fresh RHS owns its +1; pointer-guard so a mutation-in-place
      // producer (same pointer) doesn't free data we just wrote.
      current.emitLocalSet(tmp);
      current.emitLocalGet(tmp);
      current.emitLocalGet(local);
      current.emit(I64_NE);
      current.emitIf(WasmOpcode.TYPE_VOID);
      current.emitLocalGet(local);
      current.emitCall(runtime.releaseTagged);
      current.emit(END);
    } else {
      // Aliased RHS: retain then unconditional release. Self-assign
      // (x = x) balances because retain+release cancel on the same block.
      current.emitCall(runtime.retainTagged);
      current.emitLocalSet(tmp);
      current.emitLocalGet(local);
      current.emitCall(runtime.releaseTagged);
    }
    current.emitLocalGet(tmp);
    current.emitLocalSet(local);
  }

  /**
   * Emit {@code node} so the value left on the stack is an owning reference. Used by
   * container/struct construction and variable declaration. Ternaries recurse per-arm so each
   * branch yields owning.
   */
  private void emitOwning(final ASTNode node) {
    if (node instanceof IfExpressionNode ifExpr) {
      emit(ifExpr.condition());
      current.emitCall(runtime.untagInt);
      current.emit(I32_WRAP_I64);
      current.emitIf(WasmOpcode.TYPE_I64);
      emitOwning(ifExpr.then());
      current.emit(ELSE);
      if (ifExpr.hasOtherwise()) {
        emitOwning(ifExpr.otherwise());
      } else {
        current.emitCall(runtime.tagVoid);
      }
      current.emit(END);
      return;
    }
    emit(node);
    if (!rc.isFreshProducer(node)) {
      current.emitCall(runtime.retainTagged);
    }
  }

  @Override
  public SymbolKey visitForStatement(final ForStatementNode node) {
    // Compile the iterable and save it to a local (tagged).
    emit(node.iterable());
    final var iterable = current.addLocal(WasmOpcode.TYPE_I64);
    current.emitLocalSet(iterable);

    // If the iterable is a map, replace it with its key list so the loop
    // below can iterate over the keys uniformly.
    current.emitLocalGet(iterable);
    current.emitCall(runtime.tag);
    current.emitI32Const(WasmRuntime.TAG_MAP);
    current.emit(I32_EQ);
    current.emitIf(WasmOpcode.TYPE_VOID);
    current.emitLocalGet(iterable);
    current.emitCall(support.mapKeys());
    current.emitLocalSet(iterable);
    current.emit(END);

    // If the iterable is a string, expand it to a list of single-character
    // strings via safe_str_chars so the same loop body works.
    current.emitLocalGet(iterable);
    current.emitCall(runtime.tag);
    current.emitI32Const(WasmRuntime.TAG_STRING);
    current.emit(I32_EQ);
    current.emitIf(WasmOpcode.TYPE_VOID);
    current.emitLocalGet(iterable);
    current.emitCall(runtime.untagPointer);
    current.emitCall(state.builtins.get("safe_str_chars"));
    WasmEmit.retagPointer(current, WasmRuntime.TAG_LIST);
    current.emitLocalSet(iterable);
    current.emit(END);

    // length = listLength(iterable) (raw i64 after untag)
    current.emitLocalGet(iterable);
    current.emitCall(support.listLength());
    current.emitCall(runtime.untagInt);
    final var length = current.addLocal(WasmOpcode.TYPE_I64);
    current.emitLocalSet(length);

    // index = 0 (raw i64)
    final var index = current.addLocal(WasmOpcode.TYPE_I64);
    current.emitI64Const(0);
    current.emitLocalSet(index);

    // Push a fresh scope for the loop variable.
    state.pushScope();
    final var loopVar = state.allocLocal(current, node.variable(), null);

    // block { loop { br_if 1 (index >= length); body; index++; br 0 } }
    current.emitBlock(WasmOpcode.TYPE_VOID);
    current.emitLoop(WasmOpcode.TYPE_VOID);

    current.emitLocalGet(index);
    current.emitLocalGet(length);
    current.emit(I64_GE_S);
    current.emitBrIf(1); // exit outer block if index >= length

    // loopVar = listGet(iterable, tagged_index)
    current.emitLocalGet(iterable);
    current.emitLocalGet(index);
    current.emitCall(runtime.tagInt);
    current.emitCall(support.listGet());
    current.emitLocalSet(loopVar);

    for (final var statement : node.body()) {
      emit(statement);
    }

    // Scope-release: drop heap-tagged body locals at iteration end so
    // per-iteration allocations don't leak across iterations. The loop
    // variable itself was retain-on-alias when written via listGet (which
    // returns an owning ref), so release here balances.
    emitIterationScopeRelease(node.body(), node.variable());

    // index = index + 1; continue
    current.emitLocalGet(index);
    current.emitI64Const(1);
    current.emit(I64_ADD);
    current.emitLocalSet(index);
    current.emitBr(0);

    current.emit(END); // loop
    current.emit(END); // block

    state.popScope();
    return null;
  }

  /**
   * Release every heap-tagged local declared directly in a loop body, plus (if non-null) the loop
   * variable itself. safe_rc_release_tagged is tag- dispatched so scalar locals (int counters etc.)
   * slip through as a cheap no-op. Only direct {@link VariableDeclarationNode} children of the body
   * list are released — nested scopes (case bindings, inner {@code do} blocks) manage their own
   * lifetimes.
   */
  private void emitIterationScopeRelease(final List<ASTNode> body, final String loopVar) {
    for (final var stmt : body) {
      if (stmt instanceof VariableDeclarationNode decl) {
        final var slot = state.resolveLocal(decl.name());
        if (slot >= 0 && mayBeHeap(decl.name())) {
          current.emitLocalGet(slot);
          current.emitCall(runtime.releaseTagged);
        }
      }
    }
    if (loopVar != null) {
      final var slot = state.resolveLocal(loopVar);
      if (slot >= 0) {
        current.emitLocalGet(slot);
        current.emitCall(runtime.releaseTagged);
      }
    }
  }

  @Override
  public SymbolKey visitWhileStatement(final WhileStatementNode node) {
    // Constrained while: the bound is pre-evaluated and decremented each
    // iteration; exit when the bound hits zero or the user condition is false.
    emit(node.bound());
    current.emitCall(runtime.untagInt);
    final var remaining = current.addLocal(WasmOpcode.TYPE_I64);
    current.emitLocalSet(remaining);

    // Negative bound → trap. The other backends (Interpreter:595,
    // BytecodeVM, CCodeGenerator:934) all reject this; silently exiting
    // with zero iterations would hide user errors.
    current.emitLocalGet(remaining);
    current.emitI64Const(0);
    current.emit(I64_LT_S);
    current.emitIf(WasmOpcode.TYPE_VOID);
    current.emitI32Const(intern("While loop bound must be non-negative"));
    current.emitCall(runtime.trapWithMessage);
    current.emit(END);

    current.emitBlock(WasmOpcode.TYPE_VOID);
    current.emitLoop(WasmOpcode.TYPE_VOID);

    // if remaining <= 0, exit
    current.emitLocalGet(remaining);
    current.emitI64Const(0);
    current.emit(I64_LE_S);
    current.emitBrIf(1);

    // if !condition, exit
    emit(node.condition());
    current.emitCall(runtime.untagInt);
    current.emit(I64_EQZ);
    current.emitBrIf(1);

    for (final var statement : node.body()) {
      emit(statement);
    }

    // Same iteration-end scope-release discipline as visitForStatement;
    // no loop variable for while-loops, so pass null.
    emitIterationScopeRelease(node.body(), null);

    // remaining = remaining - 1; continue
    current.emitLocalGet(remaining);
    current.emitI64Const(1);
    current.emit(I64_SUB);
    current.emitLocalSet(remaining);
    current.emitBr(0);
    current.emit(END);
    current.emit(END);
    return null;
  }

  @Override
  public SymbolKey visitReturn(final ReturnNode node) {
    if (node.hasExpression()) {
      emit(node.expression());
    } else {
      current.emitCall(runtime.tagVoid);
    }
    // Stash the return value in a scratch local so we can release body
    // locals (which might alias something on the stack) without corrupting
    // the returned value. Skip releasing the returned variable's slot so
    // ownership transfers cleanly to the caller.
    final var returnValue = current.addLocal(WasmOpcode.TYPE_I64);
    current.emitLocalSet(returnValue);
    emitFunctionBodyScopeRelease(returnedVariableName(node));
    current.emitLocalGet(returnValue);
    // If the surrounding function has ensures/decreases, route through the
    // epilogue helper so the contract checks fire before every return path.
    if (state.ensuresActive || state.decreasesActive) {
      emitFunctionEpilogue(/* alsoReturn= */ true);
    } else {
      current.emit(RETURN);
    }
    return null;
  }

  /**
   * Extract the variable name from a simple {@code return identifier} return; null for other return
   * forms. Used to skip the returned slot when emitting function-body scope-release so ownership
   * transfers cleanly without a spurious release.
   */
  private static String returnedVariableName(final ReturnNode node) {
    if (!node.hasExpression()) return null;
    if (node.expression() instanceof VariableReferenceNode ref
        && !ref.hasPrefix()
        && ref.parts().size() == 1) {
      return ref.parts().getFirst();
    }
    return null;
  }

  /**
   * Release heap-tagged body locals of the function currently being emitted. Parameters are NOT
   * released — WASM follows a borrowed- reference calling convention: callers retain before the
   * call (where needed for struct-field aliasing) and callees leave parameters untouched on return.
   *
   * <p>Only direct {@link VariableDeclarationNode} children of the function body are considered;
   * nested scopes (case bindings, loop bodies) manage their own releases.
   */
  private void emitFunctionBodyScopeRelease(final String skipName) {
    final var body = state.currentFunctionBody;
    if (body == null) return;
    for (final var stmt : body) {
      if (stmt instanceof VariableDeclarationNode decl) {
        if (skipName != null && skipName.equals(decl.name())) continue;
        if (mayBeHeap(decl.name())) {
          final var slot = state.resolveLocal(decl.name());
          if (slot >= 0) {
            current.emitLocalGet(slot);
            current.emitCall(runtime.releaseTagged);
          }
        }
      }
    }
  }

  @Override
  public SymbolKey visitExpressionStatement(final ExpressionStatementNode node) {
    emit(node.expression());
    // Every expression leaves a tagged i64 on the stack; an expression
    // statement discards it.
    current.emit(DROP);
    return null;
  }

  @Override
  public SymbolKey visitBinaryExpression(final BinaryExpressionNode node) {
    binary.emit(node);
    return null;
  }

  /** Best-effort static type lookup, for primitive operator dispatch only. */
  private String typeOf(final ASTNode node) {
    if (node instanceof LiteralNode literal) {
      return switch (literal) {
        case LiteralNode.IntLiteral ignored -> "int";
        case LiteralNode.UintLiteral ignored -> "uint";
        case LiteralNode.FloatLiteral ignored -> "float";
        case LiteralNode.StringLiteral ignored -> "string";
        case LiteralNode.BoolLiteral ignored -> "bool";
      };
    }
    if (node instanceof StringInterpolationNode) {
      return "string";
    }
    if (node instanceof VariableReferenceNode reference && reference.parts().size() == 1) {
      return state.resolvePrimitive(reference.parts().getFirst());
    }
    if (node instanceof BinaryExpressionNode binary) {
      // String + anything is a string under SAFE's implicit-stringify rule.
      if ("+".equals(binary.operator())
          && ("string".equals(typeOf(binary.left())) || "string".equals(typeOf(binary.right())))) {
        return "string";
      }
    }
    return null;
  }

  private boolean isStringExpression(final ASTNode node) {
    return "string".equals(typeOf(node));
  }

  @Override
  public SymbolKey visitUnaryExpression(final UnaryExpressionNode node) {
    switch (node.operator()) {
      case "-" -> {
        current.emitI64Const(0);
        emit(node.operand());
        current.emitCall(runtime.untagInt);
        current.emit(I64_SUB);
        current.emitCall(runtime.tagInt);
      }
      case "!" -> {
        emit(node.operand());
        current.emitCall(runtime.untagInt);
        current.emit(I64_EQZ);
        WasmEmit.retagBool(current);
      }
      case "~" -> {
        emit(node.operand());
        current.emitCall(runtime.untagInt);
        current.emitI64Const(-1);
        current.emit(I64_XOR);
        current.emitCall(runtime.tagInt);
      }
      default ->
          throw new CompilerException(
              "WASM backend: unsupported unary operator '" + node.operator() + "'");
    }
    return null;
  }

  @Override
  public SymbolKey visitIfExpression(final IfExpressionNode node) {
    emit(node.condition());
    current.emitCall(runtime.untagInt);
    current.emit(I32_WRAP_I64);
    current.emitIf(WasmOpcode.TYPE_I64);
    final var thenType = emit(node.then());
    current.emit(ELSE);
    if (node.hasOtherwise()) {
      emit(node.otherwise());
    } else {
      current.emitCall(runtime.tagVoid);
    }
    current.emit(END);
    // Both branches have the same static type per the type checker; the then
    // branch is the canonical answer.
    return thenType;
  }

  @Override
  public SymbolKey visitCaseExpression(final CaseExpressionNode node) {
    // Evaluate the subject once and save it in a local; the case compiler
    // re-reads it for every pattern test.
    final var subjectType = emit(node.subject());
    final var subject = current.addLocal(WasmOpcode.TYPE_I64);
    current.emitLocalSet(subject);
    return cases.compile(current, node.branches(), subject, subjectType, node.fallback());
  }

  @Override
  public SymbolKey visitFunctionCall(final FunctionCallNode node) {
    // Enum variant construction: `Ok(x)`, `Some(v)`, etc.
    // These parse as FunctionCallNode but resolve to a variant in TypeRegistry.
    if (!node.hasPrefix()) {
      final var variant = types.variant(state.moduleKey(), node.name());
      if (variant != null) {
        return emitVariantConstruction(variant, node.arguments());
      }
    }

    // Struct construction: `Point(3, 4)`. These also parse as FunctionCallNode.
    if (!node.hasPrefix()) {
      final var structKey = types.nominal(state.moduleKey(), node.name());
      if (structKey != null && types.struct(structKey) != null) {
        return emitStructConstruction(structKey, node.arguments());
      }
    }

    // Module-prefixed call: io:println(...) or types:Ok(7)
    if (node.hasPrefix()) {
      // Module-qualified enum variant constructor.
      final var prefixedVariant = types.variant(node.prefix(), node.name());
      if (prefixedVariant != null) {
        return emitVariantConstruction(prefixedVariant, node.arguments());
      }
      for (final var argument : node.arguments()) {
        emit(argument);
      }
      final var qualified = node.prefix() + "$" + node.name();
      final var imported = state.moduleImports.get(qualified);
      if (imported == null) {
        // Qualified module-owned builtin with no SAFE trampoline (e.g. std:range): dispatch to
        // the builtin stub, mirroring the unqualified builtin path. Args are already on the stack.
        final var stub = state.stubs.get(node.name());
        if (stub != null
            && BuiltinRegistry.isBuiltin(node.name())
            && node.prefix().equals(BuiltinRegistry.module(node.name()))) {
          padArgs(node.arguments().size(), state.stubArities.get(node.name()));
          current.emitCall(stub);
          return null;
        }
        throw new CompilerException(
            "WASM backend: unresolved module call '" + node.prefix() + ":" + node.name() + "'");
      }
      padArgs(node.arguments().size(), state.moduleImportArities.get(qualified));
      current.emitCall(imported);
      return returnTypeOfModuleFunction(node.prefix(), node.name());
    }

    // Inside a module, builtins shadow user-defined functions of the same
    // name (so io.safe's `println(s)` calls the builtin, not itself).
    if (!state.main && BuiltinRegistry.isBuiltin(node.name())) {
      final var stub = state.stubs.get(node.name());
      if (stub != null) {
        for (final var argument : node.arguments()) emit(argument);
        padArgs(node.arguments().size(), state.stubArities.get(node.name()));
        current.emitCall(stub);
        return null;
      }
    }

    // User-defined function in this module.
    final var local = symbols.function(node.name());
    if (local.isPresent()) {
      for (final var argument : node.arguments()) emit(argument);
      padArgs(node.arguments().size(), symbols.arity(node.name()).orElse(0));
      current.emitCall(local.getAsInt());
      return returnTypeOf(node.name());
    }

    // Top-level main may also reach builtins via stdlib wrappers.
    final var stub = state.stubs.get(node.name());
    if (stub != null) {
      for (final var argument : node.arguments()) emit(argument);
      padArgs(node.arguments().size(), state.stubArities.get(node.name()));
      current.emitCall(stub);
      return null;
    }

    // Closure invocation: the name binds a local or global holding a tagged
    // closure value. Push the closure followed by the args, then call the
    // appropriate __callN trampoline imported from the __closures module.
    final var localSlot = state.resolveLocal(node.name());
    if (localSlot >= 0) {
      current.emitLocalGet(localSlot);
      for (final var argument : node.arguments()) emit(argument);
      final var arity = node.arguments().size();
      if (arity > 8) {
        throw new CompilerException(
            "WASM backend: closure arity " + arity + " exceeds MAX_ARITY=8");
      }
      current.emitCall(state.callImports[arity]);
      return null;
    }
    throw new CompilerException("WASM backend: unresolved function call '" + node.name() + "'");
  }

  /** Pad missing arguments with tagged VOID up to the callee's declared arity. */
  private void padArgs(final int provided, final Integer arity) {
    if (arity == null) return;
    for (var i = provided; i < arity; i++) {
      current.emitI64Const(WasmRuntime.TAG_VOID);
    }
  }

  /** Resolve the nominal return type of a user function defined in this module. */
  private SymbolKey returnTypeOf(final String name) {
    final var declaration = symbols.declaration(name);
    if (declaration == null) return null;
    return resolver.nominalType(declaration.returns());
  }

  /** Resolve the nominal return type of a function imported from another module. */
  private SymbolKey returnTypeOfModuleFunction(final String moduleName, final String functionName) {
    final var program = registry.program(moduleName);
    if (program == null) return null;
    for (final var declaration : program.declarations()) {
      if (declaration instanceof FunctionDeclarationNode function
          && function.name().equals(functionName)) {
        return types.nominal(moduleName, function.returns());
      }
    }
    return null;
  }

  @Override
  public SymbolKey visitVariableReference(final VariableReferenceNode node) {
    final var name = node.parts().getFirst();

    // Single-part reference may resolve as a zero-arity enum variant
    // (e.g. `None`, `Blue`) before any lexical lookup.
    if (node.parts().size() == 1) {
      final var variant = types.variant(state.moduleKey(), name);
      if (variant != null && variant.arity() == 0) {
        return emitVariantConstruction(variant, List.of());
      }
    }

    final var local = state.resolveLocal(name);
    if (local >= 0) {
      current.emitLocalGet(local);
      // Walk any trailing parts as field accesses (struct field chain).
      return walkFieldChain(state.resolveValueType(name), node.parts(), 1);
    }

    // Qualified reference: `module.CONST` — look up as a module constant.
    if (node.parts().size() == 2) {
      final var qualified = node.parts().get(0) + "$" + node.parts().get(1);
      final var imported = state.moduleImports.get(qualified);
      if (imported != null) {
        // Module constants are exposed as zero-arg imported functions.
        current.emitCall(imported);
        return null;
      }
    }

    // Reference to a module-local constant: it was registered as a 0-arg
    // accessor function during the module's own compilation.
    if (node.parts().size() == 1 && symbols.arity(name).orElse(-1) == 0) {
      final var function = symbols.function(name);
      if (function.isPresent()) {
        current.emitCall(function.getAsInt());
        return null;
      }
    }

    throw new CompilerException("WASM backend: unresolved variable '" + name + "'");
  }

  /**
   * Walk trailing parts of a {@code VariableReferenceNode} as struct fields, starting from a known
   * receiver type. Returns the type of the final field.
   */
  private SymbolKey walkFieldChain(
      final SymbolKey receiverType, final List<String> parts, final int start) {
    var type = receiverType;
    for (var i = start; i < parts.size(); i++) {
      final var field = parts.get(i);
      current.emitCall(runtime.untagPointer);
      final var offset = resolver.fieldOffset(type, field);
      if (offset < 0) {
        throw new CompilerException(
            "WASM backend: cannot resolve field '" + field + "' on " + type);
      }
      current.emitLoad(WasmOpcode.I64_LOAD, 3, 8 + offset * 8);
      type = resolver.fieldType(type, field);
    }
    return type;
  }

  /**
   * Emit a struct construction given positional arguments. Matches the layout used by {@link
   * WasmObjectCompiler#creation}: an i32 type-id header followed by i64 field slots.
   */
  private SymbolKey emitStructConstruction(
      final SymbolKey structKey, final List<ASTNode> arguments) {
    final var count = arguments.size();
    final var size = 8 + count * 8;
    // Allocate with a SAFEHeader so the block can participate in RC.
    // meta = heap-field bitmap so dispose knows which slots to release.
    final var struct = types.struct(structKey);
    final var meta = struct != null ? WasmRefcount.bitmapOverFields(struct.fields()) : 0;
    current.emitI32Const(size);
    current.emitI32Const(5 /* SAFE_KIND_OBJECT */);
    current.emitI32Const(meta);
    current.emitCall(runtime.rcAlloc);
    final var pointer = current.addLocal(WasmOpcode.TYPE_I32);
    current.emitLocalSet(pointer);

    final var object = types.object(structKey);
    current.emitLocalGet(pointer);
    current.emitI32Const(object >= 0 ? object : 0);
    current.emitStore(WasmOpcode.I32_STORE, 2, 0);

    final var structFields = struct != null ? struct.fields() : null;
    for (var i = 0; i < count; i++) {
      current.emitLocalGet(pointer);
      final var arg = arguments.get(i);
      emit(arg);
      // Retain aliased heap-field values so the struct owns its own ref.
      // Fresh producers (function calls, constructors, etc.) transfer
      // ownership of their refs=1 allocation without a retain.
      if (structFields != null
          && i < structFields.size()
          && WasmRefcount.isHeapType(structFields.get(i).type().fullName())
          && !rc.isFreshProducer(arg)) {
        current.emitCall(runtime.retainTagged);
      }
      current.emitStore(WasmOpcode.I64_STORE, 3, 8 + i * 8);
    }

    current.emitLocalGet(pointer);
    WasmEmit.retagPointer(current, WasmRuntime.TAG_OBJECT);
    return structKey;
  }

  /**
   * Emit the WASM instructions that allocate a new enum variant and populate its header + fields.
   * Leaves a tagged enum value on the stack.
   */
  private SymbolKey emitVariantConstruction(
      final TypeRegistry.Variant variant, final List<ASTNode> fields) {
    // Compile each field argument first and stash them in locals so we can
    // write them into the allocated block in any order. Aliased heap-RC
    // fields get a retain so the variant owns its own reference; fresh
    // producers transfer ownership. dispose_enum walks every slot at
    // teardown (its meta bitmap is "all slots"), so the retain here
    // balances the eventual release.
    final var values = new int[fields.size()];
    for (var i = 0; i < fields.size(); i++) {
      final var field = fields.get(i);
      emit(field);
      if (!rc.isFreshProducer(field)) {
        current.emitCall(runtime.retainTagged);
      }
      values[i] = current.addLocal(WasmOpcode.TYPE_I64);
      current.emitLocalSet(values[i]);
    }
    WasmEmit.emitVariant(current, runtime, variant.type(), variant.index(), values);
    return variant.owner();
  }

  @Override
  public SymbolKey visitObjectCreation(final ObjectCreationNode node) {
    return objects.creation(current, state.moduleKey(), node);
  }

  @Override
  public SymbolKey visitFieldAssignment(final FieldAssignmentNode node) {
    // Field assignments inside a struct literal are compiled by the object
    // compiler as part of visitObjectCreation; they should never be visited
    // standalone.
    throw unsupported("bare FieldAssignmentNode (expected inside object creation)");
  }

  @Override
  public SymbolKey visitLiteral(final LiteralNode node) {
    switch (node) {
      case LiteralNode.IntLiteral i -> current.emitI64Const(i.value() << WasmRuntime.TAG_BITS);
      case LiteralNode.UintLiteral u ->
          current.emitI64Const((u.value() << WasmRuntime.TAG_BITS) | WasmRuntime.TAG_UINT);
      case LiteralNode.FloatLiteral f -> {
        final var bits = Double.doubleToRawLongBits(f.value());
        current.emitI64Const((bits & ~WasmRuntime.TAG_MASK) | WasmRuntime.TAG_FLOAT);
      }
      case LiteralNode.BoolLiteral b ->
          current.emitI64Const(
              ((b.value() ? 1L : 0L) << WasmRuntime.TAG_BITS) | WasmRuntime.TAG_BOOL);
      case LiteralNode.StringLiteral s -> {
        final var offset = intern(s.value());
        current.emitI64Const(((long) offset << WasmRuntime.TAG_BITS) | WasmRuntime.TAG_STRING);
      }
    }
    return null;
  }

  @Override
  public SymbolKey visitListLiteral(final ListLiteralNode node) {
    // Allocate an empty list, then append each element (leaves tagged list).
    current.emitCall(support.listCreate());
    for (final var element : node.elements()) {
      emit(element);
      current.emitCall(support.listAppend());
    }
    return null;
  }

  @Override
  public SymbolKey visitMapLiteral(final MapLiteralNode node) {
    maps.compileLiteral(current, node, this);
    return null;
  }

  @Override
  public SymbolKey visitAssert(final AssertNode node) {
    // if (!condition) { trap }
    emit(node.condition());
    current.emitCall(runtime.untagInt);
    current.emit(I64_EQZ);
    current.emitIf(WasmOpcode.TYPE_VOID);
    current.emit(WasmOpcode.UNREACHABLE);
    current.emit(END);
    return null;
  }

  @Override
  public SymbolKey visitIndexAccess(final IndexAccessNode node) {
    // container[index] — dispatch based on the container's tag at runtime.
    // We evaluate the container once, stash it in a local, then branch on
    // the tag so we can call the correct runtime wrapper.
    emit(node.container());
    final var container = current.addLocal(WasmOpcode.TYPE_I64);
    current.emitLocalSet(container);

    current.emitLocalGet(container);
    current.emitCall(runtime.tag);
    current.emitI32Const(WasmRuntime.TAG_MAP);
    current.emit(I32_EQ);
    current.emitIf(WasmOpcode.TYPE_I64);
    // Map: mapGet(untag(container), taggedKey)
    current.emitLocalGet(container);
    current.emitCall(runtime.untagPointer);
    emit(node.index());
    current.emitCall(runtime.mapGet);
    current.emit(ELSE);
    current.emitLocalGet(container);
    current.emitCall(runtime.tag);
    current.emitI32Const(WasmRuntime.TAG_STRING);
    current.emit(I32_EQ);
    current.emitIf(WasmOpcode.TYPE_I64);
    // String: charat(untag(container), untag(index)) → tagged string
    current.emitLocalGet(container);
    current.emitCall(runtime.untagPointer);
    emit(node.index());
    current.emitCall(runtime.untagInt);
    current.emit(I32_WRAP_I64);
    current.emitCall(state.builtins.get("safe_str_charat"));
    WasmEmit.retagPointer(current, WasmRuntime.TAG_STRING);
    current.emit(ELSE);
    // List: listGet(container_tagged, index_tagged)
    current.emitLocalGet(container);
    emit(node.index());
    current.emitCall(support.listGet());
    current.emit(END);
    current.emit(END);
    return null;
  }

  @Override
  public SymbolKey visitIndexAssignment(final IndexAssignmentNode node) {
    // container[idx] = value — currently only supports single-level map/list
    // updates via listSet/mapPut.
    emit(node.container());
    final var container = current.addLocal(WasmOpcode.TYPE_I64);
    current.emitLocalSet(container);

    // Support only single-index form for now.
    if (node.indices().size() != 1) {
      throw new CompilerException("WASM backend: multi-index assignment not yet supported");
    }

    current.emitLocalGet(container);
    current.emitCall(runtime.tag);
    current.emitI32Const(WasmRuntime.TAG_MAP);
    current.emit(I32_EQ);
    current.emitIf(WasmOpcode.TYPE_VOID);
    // mapPut(map, key, value)
    current.emitLocalGet(container);
    emit(node.indices().getFirst());
    emit(node.value());
    current.emitCall(runtime.mapPut);
    current.emit(DROP); // discard the map pointer result
    current.emit(ELSE);
    // listSet(list_ptr, index, value) — returns void
    current.emitLocalGet(container);
    current.emitCall(runtime.untagPointer);
    emit(node.indices().getFirst());
    current.emitCall(runtime.untagInt);
    current.emit(I32_WRAP_I64);
    emit(node.value());
    current.emitCall(runtime.listSet);
    current.emit(END);
    return null;
  }

  @Override
  public SymbolKey visitStringInterpolation(final StringInterpolationNode node) {
    if (node.parts().isEmpty()) {
      final var offset = intern("");
      current.emitI64Const(((long) offset << WasmRuntime.TAG_BITS) | WasmRuntime.TAG_STRING);
      return null;
    }
    // Compile and stringify the first part.
    pushAsString(node.parts().getFirst());
    for (var i = 1; i < node.parts().size(); i++) {
      pushAsString(node.parts().get(i));
      current.emitCall(support.stringConcat());
    }
    return null;
  }

  /** Compile {@code node} and convert it to a tagged string on the stack. */
  private void pushAsString(final ASTNode node) {
    emit(node);
    if (!isStringExpression(node)) {
      current.emitCall(support.stringify());
    }
  }

  @Override
  public SymbolKey visitFieldAccess(final FieldAccessNode node) {
    return objects.access(current, node);
  }

  @Override
  public SymbolKey visitRange(final RangeNode node) {
    // A SAFE range `a..b` (optional `step s`) lowers to a fresh list:
    // build an empty list, then loop from start to end appending each value.
    // Unlike Python, SAFE ranges are INCLUSIVE of the end.
    emit(node.start());
    final var start = current.addLocal(WasmOpcode.TYPE_I64);
    current.emitCall(runtime.untagInt);
    current.emitLocalSet(start);

    emit(node.end());
    final var end = current.addLocal(WasmOpcode.TYPE_I64);
    current.emitCall(runtime.untagInt);
    current.emitLocalSet(end);

    final var step = current.addLocal(WasmOpcode.TYPE_I64);
    if (node.hasStep()) {
      emit(node.step());
      current.emitCall(runtime.untagInt);
      current.emitLocalSet(step);
    } else {
      current.emitI64Const(1);
      current.emitLocalSet(step);
    }

    // Zero step would spin forever; match Interpreter:423 / BytecodeVM:865
    // / CCodeGenerator:798 by trapping.
    current.emitLocalGet(step);
    current.emit(I64_EQZ);
    current.emitIf(WasmOpcode.TYPE_VOID);
    current.emitI32Const(intern("Range step cannot be zero"));
    current.emitCall(runtime.trapWithMessage);
    current.emit(END);

    // list = listCreate()
    current.emitCall(support.listCreate());
    final var list = current.addLocal(WasmOpcode.TYPE_I64);
    current.emitLocalSet(list);

    // Running size counter; capped at MAX_LIST_SIZE to match
    // Interpreter:433 / BytecodeVM:880.
    final var size = current.addLocal(WasmOpcode.TYPE_I64);
    current.emitI64Const(0);
    current.emitLocalSet(size);

    // index = start
    final var index = current.addLocal(WasmOpcode.TYPE_I64);
    current.emitLocalGet(start);
    current.emitLocalSet(index);

    current.emitBlock(WasmOpcode.TYPE_VOID);
    current.emitLoop(WasmOpcode.TYPE_VOID);
    // Loop exit: ascending step exits when index > end (inclusive end),
    // descending step exits when index < end.
    current.emitLocalGet(step);
    current.emitI64Const(0);
    current.emit(I64_GT_S);
    current.emitIf(WasmOpcode.TYPE_I32);
    current.emitLocalGet(index);
    current.emitLocalGet(end);
    current.emit(I64_GT_S);
    current.emit(ELSE);
    current.emitLocalGet(index);
    current.emitLocalGet(end);
    current.emit(I64_LT_S);
    current.emit(END);
    current.emitBrIf(1);

    // size >= MAX_LIST_SIZE → trap before growing the list.
    current.emitLocalGet(size);
    current.emitI64Const(SAFEValue.MAX_LIST_SIZE);
    current.emit(I64_GE_S);
    current.emitIf(WasmOpcode.TYPE_VOID);
    current.emitI32Const(intern("range size exceeds maximum of " + SAFEValue.MAX_LIST_SIZE));
    current.emitCall(runtime.trapWithMessage);
    current.emit(END);

    // list = listAppend(list, tagInt(index))
    current.emitLocalGet(list);
    current.emitLocalGet(index);
    current.emitCall(runtime.tagInt);
    current.emitCall(support.listAppend());
    current.emitLocalSet(list);

    // size += 1
    current.emitLocalGet(size);
    current.emitI64Const(1);
    current.emit(I64_ADD);
    current.emitLocalSet(size);

    // next = index + step, detecting signed overflow. Matches
    // Interpreter:440-442 which breaks out rather than wrapping.
    // Rule: with step > 0 a correct step increases index (new > index); if
    // new < index signed, the add wrapped. With step < 0 the opposite holds.
    final var next = current.addLocal(WasmOpcode.TYPE_I64);
    current.emitLocalGet(index);
    current.emitLocalGet(step);
    current.emit(I64_ADD);
    current.emitLocalSet(next);

    final var overflow = intern("range index overflow");
    current.emitLocalGet(step);
    current.emitI64Const(0);
    current.emit(I64_GT_S);
    current.emitIf(WasmOpcode.TYPE_VOID);
    current.emitLocalGet(next);
    current.emitLocalGet(index);
    current.emit(I64_LT_S);
    current.emitIf(WasmOpcode.TYPE_VOID);
    current.emitI32Const(overflow);
    current.emitCall(runtime.trapWithMessage);
    current.emit(END);
    current.emit(ELSE);
    current.emitLocalGet(next);
    current.emitLocalGet(index);
    current.emit(I64_GT_S);
    current.emitIf(WasmOpcode.TYPE_VOID);
    current.emitI32Const(overflow);
    current.emitCall(runtime.trapWithMessage);
    current.emit(END);
    current.emit(END);

    current.emitLocalGet(next);
    current.emitLocalSet(index);

    current.emitBr(0);
    current.emit(END); // loop
    current.emit(END); // block

    current.emitLocalGet(list);
    return null;
  }

  @Override
  public SymbolKey visitDoExpression(final DoExpressionNode node) {
    state.pushScope();
    for (final var statement : node.statements()) {
      emit(statement);
    }
    final var resultType = emit(node.expression());
    state.popScope();
    return resultType;
  }

  @Override
  public SymbolKey visitTupleLiteral(final TupleLiteralNode node) {
    // Tuples are laid out like lists internally on the WASM backend.
    current.emitCall(support.listCreate());
    for (final var element : node.elements()) {
      emit(element);
      current.emitCall(support.listAppend());
    }
    return null;
  }

  @Override
  public SymbolKey visitSetLiteral(final SetLiteralNode node) {
    // Set literals build a fresh list via listCreate, add each element via
    // safe_set_add (from the C runtime), and retag as a set pointer.
    current.emitCall(support.listCreate());
    for (final var element : node.elements()) {
      current.emitCall(runtime.untagPointer);
      emit(element);
      current.emitCall(state.builtins.get("safe_set_add"));
      WasmEmit.retagPointer(current, WasmRuntime.TAG_SET);
    }
    return null;
  }

  @Override
  public SymbolKey visitLambda(final LambdaNode node) {
    // The planner allocates a function slot, records captures, and schedules
    // the body compilation via the deferred queue. The returned plan tells us
    // the table slot to embed as the closure's code pointer.
    final var plan = planner.plan(module, node);

    // Allocate a closure struct: [i32 table_index][i32 unused][i64 env_fields...]
    final var captureCount = plan.captures().size();
    final var size = 8 + captureCount * 8;
    // Allocate with SAFE_KIND_CLOSURE and an "all captures heap" meta
    // bitmap so dispose-with-children releases each capture. Scalar
    // captures are a no-op via the tag dispatch.
    final var captureLimit = captureCount < 8 ? captureCount : 8;
    final var closureMeta = captureLimit == 0 ? 0 : (1 << captureLimit) - 1;
    current.emitI32Const(size);
    current.emitI32Const(7 /* SAFE_KIND_CLOSURE */);
    current.emitI32Const(closureMeta);
    current.emitCall(runtime.rcAlloc);
    final var pointer = current.addLocal(WasmOpcode.TYPE_I32);
    current.emitLocalSet(pointer);

    // Store the table slot at offset 0.
    current.emitLocalGet(pointer);
    current.emitI32Const(plan.table());
    current.emitStore(WasmOpcode.I32_STORE, 2, 0);

    // Copy each captured value from the current scope into the closure env.
    // Captures are aliased references into the outer scope, so retain each
    // so the closure owns its own count. Scalar captures are no-op via tag
    // dispatch.
    for (var i = 0; i < captureCount; i++) {
      current.emitLocalGet(pointer);
      final var capture = plan.captures().get(i);
      final var local = state.resolveLocal(capture);
      if (local < 0) {
        throw new CompilerException("WASM backend: cannot capture '" + capture + "'");
      }
      current.emitLocalGet(local);
      current.emitCall(runtime.retainTagged);
      current.emitStore(WasmOpcode.I64_STORE, 3, 8 + i * 8);
    }

    // Tag the pointer as a closure.
    current.emitLocalGet(pointer);
    WasmEmit.retagPointer(current, WasmRuntime.TAG_CLOSURE);
    return null;
  }

  @Override
  public SymbolKey visitDestructure(final DestructureNode node) {
    // const (a, b, c) = expr;  — evaluate once, then unpack into locals.
    emit(node.initializer());
    final var source = current.addLocal(WasmOpcode.TYPE_I64);
    current.emitLocalSet(source);

    for (var i = 0; i < node.names().size(); i++) {
      current.emitLocalGet(source);
      current.emitI64Const(i);
      current.emitCall(runtime.tagInt);
      current.emitCall(support.listGet());
      final var slot = state.allocLocal(current, node.names().get(i), null);
      current.emitLocalSet(slot);
    }
    return null;
  }

  // ========== Context adapters ==========

  private void compileLambdaBody(final String name, final LambdaNode node, final int index) {
    final var saved = current;
    lambdas.compile(new LambdaCompilerContext(), node, index, () -> emit(node.body()));
    current = saved;
  }

  /** Adapter passed to {@link WasmCaseCompiler} so it can recurse into the visitor. */
  private final class CaseAdapter implements WasmCaseContext {
    @Override
    public SymbolKey compile(final ASTNode node) {
      return emit(node);
    }

    @Override
    public void push() {
      state.pushScope();
    }

    @Override
    public void pop() {
      state.popScope();
    }

    @Override
    public int allocate(final String name, final SymbolKey type) {
      return state.allocLocal(current, name, type);
    }
  }

  /** Adapter passed to {@link WasmObjectCompiler}. */
  private final class ObjectAdapter implements WasmObjectContext {
    @Override
    public SymbolKey compile(final ASTNode node) {
      return emit(node);
    }

    @Override
    public int local(final String name) {
      return state.resolveLocal(name);
    }

    @Override
    public Integer global(final String name) {
      // The WASM backend has no module-level globals; everything is either a
      // local, an imported function, or a constant accessor function.
      return null;
    }

    @Override
    public SymbolKey value(final String name) {
      return state.resolveValueType(name);
    }

    @Override
    public void tag(final int tag) {
      // Convert the raw i32 pointer that the object compiler just pushed
      // into a tagged i64 of the requested kind.
      WasmEmit.retagPointer(current, tag);
    }

    @Override
    public boolean isFreshProducer(final ASTNode node) {
      return rc.isFreshProducer(node);
    }
  }

  /** Adapter passed to {@link WasmLambdaPlanner}. */
  private final class LambdaHooks implements WasmLambdaHooks {
    @Override
    public int local(final String name) {
      return state.resolveLocal(name);
    }

    @Override
    public boolean global(final String name) {
      // No module-level globals in the WASM backend.
      return false;
    }

    @Override
    public SymbolKey value(final String name) {
      return state.resolveValueType(name);
    }

    @Override
    public void schedule(final String name, final LambdaNode node, final int index) {
      // Lambda body compilation is deferred until the surrounding function
      // finishes emitting; the deferred queue lives on the compilation state.
      state.deferred.add(() -> compileLambdaBody(name, node, index));
    }
  }

  /** Adapter passed to {@link WasmLambdaCompiler}. */
  private final class LambdaCompilerContext implements WasmLambdaCompilerContext {
    @Override
    public WasmModule module() {
      return module;
    }

    @Override
    public void setCurrent(final WasmFunction function) {
      current = function;
    }

    @Override
    public WasmFunction current() {
      return current;
    }

    @Override
    public void setStateInFunction(final boolean value) {
      state.inFunction = value;
    }

    @Override
    public void pushScope() {
      state.pushScope();
    }

    @Override
    public void popScope() {
      state.popScope();
    }

    @Override
    public Map<String, Integer> scope() {
      return state.scope();
    }

    @Override
    public Map<String, SymbolKey> typeScope() {
      return state.typeScope();
    }

    @Override
    public SymbolKey resolveNominalType(final TypeNode type) {
      return resolver.nominalType(type);
    }

    @Override
    public List<String> captures(final int index) {
      return state.lambdaCaptures.get(index);
    }

    @Override
    public List<SymbolKey> captureTypes(final int index) {
      return state.lambdaCaptureTypes.get(index);
    }

    @Override
    public int resolveLocal(final String name) {
      return state.resolveLocal(name);
    }

    @Override
    public Integer global(final String name) {
      // The WASM backend has no module-level globals; everything is either a
      // local, an imported function, or a constant accessor function.
      return null;
    }

    @Override
    public void addCode(final int functionIndex, final byte[] code) {
      module.addCode(functionIndex, code);
    }

    @Override
    public void setInFunction(final boolean value) {
      state.inFunction = value;
    }
  }

  /** Adapter passed to {@link WasmBinaryEmitter}. */
  private final class BinaryAdapter implements WasmBinaryContext {
    @Override
    public WasmFunction current() {
      return current;
    }

    @Override
    public void emit(final ASTNode node) {
      WasmCompiler.this.emit(node);
    }
  }
}
