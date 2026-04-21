package io.safelang.compiler.bytecode;

import io.safelang.ModuleRegistry;
import io.safelang.analyzer.ImportResolver;
import io.safelang.ast.*;
import io.safelang.bytecode.*;
import java.util.Set;

final class BytecodeImportCompiler {

  private final ModuleRegistry registry;
  private final BytecodeImportContext context;

  BytecodeImportCompiler(final ModuleRegistry registry, final BytecodeImportContext context) {
    this.registry = registry;
    this.context = context;
  }

  void compile(final ProgramNode program) {
    if (registry == null) {
      return;
    }
    final var selective = ImportResolver.fold(program.imports());

    for (final var module : registry.modules()) {
      final var selected = selective.get(module);
      final var source = registry.program(module);
      for (final var declaration : source.declarations()) {
        register(module, selected, declaration);
      }
      for (final var constant : registry.constants(module).values()) {
        global(module, constant);
      }
      for (final var declaration : source.declarations()) {
        if (declaration instanceof FunctionDeclarationNode function) {
          final var name = module + "$" + function.name();
          if (context.registered(name)) {
            context.compile(function, name, module);
          }
        }
      }
      for (final var statement : source.statements()) {
        statement(module, statement);
      }
    }
  }

  private void register(
      final String module, final Set<String> selected, final ASTNode declaration) {
    switch (declaration) {
      case TypeDeclarationNode type -> {
        if (type.isPublic() && (selected == null || selected.contains(type.name()))) {
          context.type(type);
        }
      }
      case EnumDeclarationNode enumeration -> {
        if (selected == null || !enumeration.isPublic() || selected.contains(enumeration.name())) {
          context.enumeration(enumeration);
        }
      }
      case FunctionDeclarationNode function -> {
        if (selected == null || !function.isPublic() || selected.contains(function.name())) {
          context.register(function, module + "$" + function.name());
        }
      }
      default -> {}
    }
  }

  private void global(final String module, final VariableDeclarationNode node) {
    context.push(module);
    try {
      if (node.hasInitializer()) {
        context.compile(node.initializer());
      } else {
        context.chunk().emitOpcode(OpCode.PUSH_VOID);
      }
      final var name = module + "$" + node.name();
      final var index = context.add(name);
      context.name(name, index);
      context.global(name, index, node.isConstant());
      context.chunk().emitOpShort(OpCode.STORE_GLOBAL, index);
      if (context.chunk().position() > 0) {
        context.append(module);
      }
    } finally {
      context.pop();
    }
  }

  private void statement(final String module, final ASTNode node) {
    context.push(module);
    try {
      if (node instanceof VariableDeclarationNode variable) {
        if (variable.hasInitializer()) {
          context.compile(variable.initializer());
        } else {
          context.chunk().emitOpcode(OpCode.PUSH_VOID);
        }
        final var name = module + "$" + variable.name();
        final var index = context.add(name);
        context.name(name, index);
        context.global(name, index, variable.isConstant());
        context.chunk().emitOpShort(OpCode.STORE_GLOBAL, index);
      } else {
        context.compile(node);
      }
      if (context.chunk().position() > 0) {
        context.append(module);
      }
    } finally {
      context.pop();
    }
  }
}
