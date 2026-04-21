package io.safelang.analyzer;

import io.safelang.ModuleRegistry;
import io.safelang.ast.EnumDeclarationNode;
import io.safelang.ast.ImportNode;
import io.safelang.ast.TypeDeclarationNode;
import io.safelang.ast.TypeNode;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

final class SemanticImportProcessor {

  private final ModuleRegistry registry;
  private final Map<String, EnumDeclarationNode> enums;
  private final Map<String, TypeDeclarationNode> types;
  private final Map<String, TypeNode> aliases;
  private final Set<String> imported;
  private final Map<String, Set<String>> selective;
  private final Set<String> full;
  private final Map<String, String> typeModule;
  private final SemanticImportHooks hooks;

  SemanticImportProcessor(
      final ModuleRegistry registry,
      final Map<String, EnumDeclarationNode> enums,
      final Map<String, TypeDeclarationNode> types,
      final Map<String, TypeNode> aliases,
      final Set<String> imported,
      final Map<String, Set<String>> selective,
      final Set<String> full,
      final Map<String, String> typeModule,
      final SemanticImportHooks hooks) {
    this.registry = registry;
    this.enums = enums;
    this.types = types;
    this.aliases = aliases;
    this.imported = imported;
    this.selective = selective;
    this.full = full;
    this.typeModule = typeModule;
    this.hooks = hooks;
  }

  void process(final ImportNode node) {
    if (!registry.has(node.module())) {
      hooks.error("Unknown module: " + node.module(), node);
      return;
    }

    if (node.isSelective()) {
      // A prior non-selective import of this module grants full access; absorb.
      if (full.contains(node.module())) {
        return;
      }
      final var selected = new HashSet<>(node.symbols());
      selective.computeIfAbsent(node.module(), k -> new HashSet<>()).addAll(selected);

      final var available = new HashSet<String>();
      for (final var entry : registry.functions(node.module()).entrySet()) {
        if (entry.getValue().isPublic()) {
          available.add(entry.getKey());
        }
      }
      for (final var entry : registry.enums(node.module()).entrySet()) {
        if (entry.getValue().isPublic()) {
          available.add(entry.getKey());
        }
      }
      available.addAll(registry.types(node.module()).keySet());
      available.addAll(registry.aliases(node.module()).keySet());
      available.addAll(registry.constants(node.module()).keySet());
      for (final var symbol : node.symbols()) {
        if (!available.contains(symbol)) {
          hooks.error("Symbol '" + symbol + "' not found in module '" + node.module() + "'", node);
        }
      }

      for (final var entry : registry.enums(node.module()).entrySet()) {
        if (selected.contains(entry.getKey()) && entry.getValue().isPublic()) {
          enums.put(entry.getKey(), entry.getValue());
          imported.add(entry.getKey());
          hooks.conflict(entry.getValue(), node.module(), node);
        }
      }
      for (final var entry : registry.types(node.module()).entrySet()) {
        if (selected.contains(entry.getKey())) {
          types.put(entry.getKey(), entry.getValue());
          typeModule.put(entry.getKey(), node.module());
        }
      }
      for (final var entry : registry.aliases(node.module()).entrySet()) {
        if (selected.contains(entry.getKey())) {
          aliases.put(entry.getKey(), entry.getValue().target());
        }
      }
      return;
    }

    full.add(node.module());
    selective.remove(node.module());
    for (final var entry : registry.enums(node.module()).entrySet()) {
      if (entry.getValue().isPublic()) {
        enums.put(entry.getKey(), entry.getValue());
        imported.add(entry.getKey());
        hooks.conflict(entry.getValue(), node.module(), node);
      }
    }
    for (final var entry : registry.types(node.module()).entrySet()) {
      types.put(entry.getKey(), entry.getValue());
      typeModule.put(entry.getKey(), node.module());
    }
    for (final var entry : registry.aliases(node.module()).entrySet()) {
      aliases.put(entry.getKey(), entry.getValue().target());
    }
  }
}
