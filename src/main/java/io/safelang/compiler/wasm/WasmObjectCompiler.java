package io.safelang.compiler.wasm;

import static io.safelang.compiler.wasm.WasmOpcode.I32_STORE;
import static io.safelang.compiler.wasm.WasmOpcode.I64_LOAD;
import static io.safelang.compiler.wasm.WasmOpcode.I64_STORE;

import io.safelang.ast.AssignmentNode;
import io.safelang.ast.FieldAccessNode;
import io.safelang.ast.ObjectCreationNode;
import io.safelang.compiler.CompilerException;
import java.util.List;

final class WasmObjectCompiler {

  private final WasmRuntimeContext runtime;
  private final TypeRegistry types;
  private final WasmTypeResolver resolver;
  private final WasmObjectContext context;

  WasmObjectCompiler(
      final WasmRuntimeContext runtime,
      final TypeRegistry types,
      final WasmTypeResolver resolver,
      final WasmObjectContext context) {
    this.runtime = runtime;
    this.types = types;
    this.resolver = resolver;
    this.context = context;
  }

  SymbolKey fieldChain(
      final WasmFunction function,
      final SymbolKey receiverType,
      final List<String> parts,
      final int start,
      final String owner) {
    var type = receiverType;
    for (var index = start; index < parts.size(); index++) {
      function.emitCall(runtime.untagPointer);
      final var field = parts.get(index);
      final var offset = resolver.fieldOffset(type, field);
      if (offset < 0) {
        throw new CompilerException("Cannot resolve field '" + field + "' on " + owner);
      }
      function.emitLoad(I64_LOAD, 3, 8 + offset * 8);
      type = resolver.fieldType(type, field);
    }
    return type;
  }

  SymbolKey creation(
      final WasmFunction function, final String module, final ObjectCreationNode node) {
    final var type = types.nominal(module, node.type());
    final var decl = type != null ? types.struct(type) : null;
    if (decl == null) {
      throw new CompilerException("Unknown struct type: " + node.type());
    }
    final var count = node.fields().size();
    final var size = 8 + count * 8;
    // Allocate with SAFE_KIND_OBJECT and a heap-field bitmap in meta so
    // dispose-with-children releases heap fields when refs → 0.
    final var meta = WasmRefcount.bitmapOverFields(decl.fields());
    function.emitI32Const(size);
    function.emitI32Const(5 /* SAFE_KIND_OBJECT */);
    function.emitI32Const(meta);
    function.emitCall(runtime.rcAlloc);
    final var pointer = function.addLocal(WasmOpcode.TYPE_I32);
    function.emitLocalSet(pointer);

    final var object = types.object(type);
    function.emitLocalGet(pointer);
    function.emitI32Const(object >= 0 ? object : 0);
    function.emitStore(I32_STORE, 2, 0);

    final var fields = types.fields(type);
    final var declFields = decl.fields();
    for (var index = 0; index < node.fields().size(); index++) {
      function.emitLocalGet(pointer);
      final var field = node.fields().get(index);
      context.compile(field.value());
      var offset = index;
      if (fields != null) {
        final var declared = fields.indexOf(field.field());
        if (declared >= 0) {
          offset = declared;
        }
      }
      // Retain aliased heap-field values so the struct owns its own ref.
      // Fresh producers transfer their refs=1 allocation without a retain.
      if (offset < declFields.size()
          && WasmRefcount.isHeapType(declFields.get(offset).type().fullName())
          && !context.isFreshProducer(field.value())) {
        function.emitCall(runtime.retainTagged);
      }
      function.emitStore(I64_STORE, 3, 8 + offset * 8);
    }

    function.emitLocalGet(pointer);
    context.tag(WasmRuntime.TAG_OBJECT);
    return type;
  }

  SymbolKey access(final WasmFunction function, final FieldAccessNode node) {
    final var receiverType = context.compile(node.receiver());
    function.emitCall(runtime.untagPointer);
    final var offset = resolver.fieldOffset(receiverType, node.field());
    if (offset < 0) {
      throw new CompilerException("Cannot resolve field '" + node.field() + "' on receiver");
    }
    function.emitLoad(I64_LOAD, 3, 8 + offset * 8);
    return resolver.fieldType(receiverType, node.field());
  }

  void assignment(final WasmFunction function, final AssignmentNode node) {
    final var parts = node.parts();
    context.compile(node.value());
    final var scratch = function.addLocal(WasmOpcode.TYPE_I64);
    function.emitLocalSet(scratch);

    final var local = context.local(parts.getFirst());
    if (local >= 0) {
      function.emitLocalGet(local);
    } else {
      final var global = context.global(parts.getFirst());
      if (global != null) {
        function.emitGlobalGet(global);
      } else {
        throw new CompilerException("Cannot assign to unknown variable '" + parts.getFirst() + "'");
      }
    }

    var type = context.value(parts.getFirst());
    for (var index = 1; index < parts.size() - 1; index++) {
      function.emitCall(runtime.untagPointer);
      final var field = parts.get(index);
      final var offset = resolver.fieldOffset(type, field);
      if (offset < 0) {
        throw new CompilerException("Cannot resolve field '" + field + "'");
      }
      function.emitLoad(I64_LOAD, 3, 8 + offset * 8);
      type = resolver.fieldType(type, field);
    }

    function.emitCall(runtime.untagPointer);
    final var field = parts.getLast();
    final var offset = resolver.fieldOffset(type, field);
    if (offset < 0) {
      throw new CompilerException("Cannot resolve field '" + field + "'");
    }
    function.emitLocalGet(scratch);
    function.emitStore(I64_STORE, 3, 8 + offset * 8);
  }
}
