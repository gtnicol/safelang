package io.safelang.compiler.bytecode;

import io.safelang.ast.ASTNode;
import io.safelang.ast.EnumDeclarationNode;
import io.safelang.ast.FunctionDeclarationNode;
import io.safelang.ast.TypeDeclarationNode;
import io.safelang.bytecode.*;

interface BytecodeImportContext {

  void type(TypeDeclarationNode node);

  void enumeration(EnumDeclarationNode node);

  void register(FunctionDeclarationNode node, String name);

  void compile(FunctionDeclarationNode node, String name, String module);

  void push(String module);

  void pop();

  void compile(ASTNode node);

  BytecodeChunk chunk();

  int add(String name);

  void name(String name, int index);

  void global(String name, int index, boolean constant);

  boolean registered(String name);

  void append(String module);
}
