package io.safelang.compiler.wasm;

import io.safelang.ast.FunctionDeclarationNode;
import io.safelang.ast.TypeNode;
import io.safelang.compiler.CompilerException;
import io.safelang.runtime.BuiltinRegistry;

final class WasmTypeResolver {

  private final TypeRegistry types;
  private final WasmCompilationState state;

  WasmTypeResolver(final TypeRegistry types, final WasmCompilationState state) {
    this.types = types;
    this.state = state;
  }

  SymbolKey nominalType(final TypeNode type) {
    return types.nominal(state.moduleKey(), type);
  }

  SymbolKey nominalType(final String module, final TypeNode type) {
    return types.nominal(module, type);
  }

  SymbolKey functionReturnType(final FunctionDeclarationNode declaration, final String module) {
    return declaration != null ? types.nominal(module, declaration.returns()) : null;
  }

  SymbolKey builtinReturnType(final String builtin) {
    final var info = BuiltinRegistry.get(builtin);
    return info != null ? types.nominal(state.moduleKey(), info.signature().returns()) : null;
  }

  SymbolKey fieldType(final SymbolKey receiverType, final String field) {
    return receiverType != null ? types.fieldType(receiverType, field) : null;
  }

  int fieldOffset(final SymbolKey receiverType, final String field) {
    if (receiverType == null) {
      throw new CompilerException(
          "WASM backend: field access '" + field + "' has no static receiver type");
    }
    return types.field(receiverType, field);
  }

  TypeRegistry.Variant patternVariant(final SymbolKey subjectType, final String variantName) {
    if (subjectType != null) {
      return types.enumeration(subjectType) != null
          ? types.resolve(subjectType, variantName)
          : null;
    }
    return types.variant(state.moduleKey(), variantName);
  }
}
