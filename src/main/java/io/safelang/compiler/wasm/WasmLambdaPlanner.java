package io.safelang.compiler.wasm;

import io.safelang.ast.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Plans a lambda for compilation: assigns it a function index, table slot, and computes its
 * captured (free) variable list.
 *
 * <p>Capture analysis is a hand-written AST walker that maintains a lexical scope stack. Each
 * scope-introducing construct (lambda, case branch, do block, for/while loop) pushes a new {@link
 * Set} of bound names; references are checked against the entire stack and any name not bound there
 * but resolvable in the enclosing context becomes a free variable.
 */
final class WasmLambdaPlanner {

  private final WasmCompilationState state;
  private final Map<String, Integer> functions;
  private final List<Integer> tables;
  private final Map<Integer, Integer> slots;
  private final Map<Integer, List<String>> captures;
  private final Map<Integer, List<SymbolKey>> types;
  private final WasmLambdaHooks hooks;

  WasmLambdaPlanner(
      final WasmCompilationState state,
      final Map<String, Integer> functions,
      final List<Integer> tables,
      final Map<Integer, Integer> slots,
      final Map<Integer, List<String>> captures,
      final Map<Integer, List<SymbolKey>> types,
      final WasmLambdaHooks hooks) {
    this.state = state;
    this.functions = functions;
    this.tables = tables;
    this.slots = slots;
    this.captures = captures;
    this.types = types;
    this.hooks = hooks;
  }

  WasmLambdaPlan plan(final WasmModule module, final LambdaNode node) {
    // Capture analysis (shared with the interpreter via io.safelang.ast.FreeVariables): only names
    // that resolve as a wasm local or global in the surrounding context are captured here.
    final var free =
        io.safelang.ast.FreeVariables.of(
            node, name -> hooks.local(name) >= 0 || hooks.global(name), false);

    final var name = "__lambda_" + state.lambdaCounter++;
    final var signature = new int[1 + node.parameters().size()];
    signature[0] = WasmOpcode.TYPE_I32;
    for (var index = 1; index < signature.length; index++) {
      signature[index] = WasmOpcode.TYPE_I64;
    }
    final var type = module.addType(signature, new int[] {WasmOpcode.TYPE_I64});
    final var index = module.addFunction(type);
    functions.put(name, index);

    slots.put(index, state.tableOffset + tables.size());
    tables.add(index);
    captures.put(index, free);

    final var values = new ArrayList<SymbolKey>();
    for (final var capture : free) {
      values.add(hooks.value(capture));
    }
    types.put(index, values);
    hooks.schedule(name, node, index);
    return new WasmLambdaPlan(free, index, slots.get(index));
  }
}
