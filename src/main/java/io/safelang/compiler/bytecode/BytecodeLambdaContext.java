package io.safelang.compiler.bytecode;

import io.safelang.ast.ASTNode;
import io.safelang.bytecode.*;
import java.util.List;
import java.util.Map;

interface BytecodeLambdaContext {

  Map<String, Integer> slots();

  int count(List<ASTNode> body);

  String next();

  int add(String name);

  int reserve();

  void register(String name, int position);

  void push();

  void pop();

  BytecodeChunk chunk();

  int allocate(String name);

  void compile(ASTNode node);

  void define(int position, FunctionDefinition definition);
}
