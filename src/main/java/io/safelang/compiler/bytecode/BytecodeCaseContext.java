package io.safelang.compiler.bytecode;

import io.safelang.ast.ASTNode;
import io.safelang.bytecode.*;
import java.util.Map;

interface BytecodeCaseContext {

  BytecodeChunk chunk();

  Map<String, Integer> slots();

  BytecodeModule module();

  int allocate(String name);

  void compile(ASTNode node);
}
