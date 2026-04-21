package io.safelang.compiler.wasm;

import io.safelang.ast.TypeNode;
import java.util.List;
import java.util.Map;

interface WasmLambdaCompilerContext {

  WasmModule module();

  void setCurrent(WasmFunction function);

  WasmFunction current();

  void setStateInFunction(boolean value);

  void pushScope();

  void popScope();

  Map<String, Integer> scope();

  Map<String, SymbolKey> typeScope();

  SymbolKey resolveNominalType(TypeNode type);

  List<String> captures(int index);

  List<SymbolKey> captureTypes(int index);

  int resolveLocal(String name);

  Integer global(String name);

  void addCode(int functionIndex, byte[] code);

  void setInFunction(boolean value);
}
