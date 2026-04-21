package io.safelang.analyzer;

import io.safelang.ast.*;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class SemanticAssignmentChecker {

  private final TypeResolver resolver;
  private final Map<String, TypeDeclarationNode> types;
  private final Map<String, String> typeModule;
  private final Set<String> external;
  private final SemanticAssignmentHooks hooks;

  SemanticAssignmentChecker(
      final TypeResolver resolver,
      final Map<String, TypeDeclarationNode> types,
      final Map<String, String> typeModule,
      final Set<String> external,
      final SemanticAssignmentHooks hooks) {
    this.resolver = resolver;
    this.types = types;
    this.typeModule = typeModule;
    this.external = external;
    this.hooks = hooks;
  }

  void check(final AssignmentNode node) {
    hooks.analyze(node.value());
    final var parts = node.parts();
    final var name = parts.get(0);

    if (parts.size() == 1) {
      if (!defined(name, node)) {
        return;
      }
      if (hooks.scope().isConst(name)) {
        hooks.error("Cannot reassign const variable: " + name, node);
      }
      final var declared = hooks.scope().variable(name);
      if (declared != null) {
        final var resolved = resolver.resolve(node.value());
        if (resolved != null && !resolver.matches(declared, resolved)) {
          hooks.error(
              "Type mismatch: cannot assign "
                  + resolved.fullName()
                  + " to variable '"
                  + name
                  + "' of type "
                  + declared.fullName(),
              node);
        }
      }
      return;
    }

    if (!defined(name, node)) {
      return;
    }
    if (hooks.scope().isConst(name)) {
      hooks.error("Cannot reassign const variable: " + name, node);
    }
    final var field = resolver.resolveField(parts);
    if (field != null) {
      checkField(parts, name, field, node);
      return;
    }
    checkMissingField(parts, name, node);
  }

  void check(final IndexAssignmentNode node) {
    hooks.analyze(node.container());
    for (final var index : node.indices()) {
      hooks.analyze(index);
    }
    hooks.analyze(node.value());

    if (node.container() instanceof VariableReferenceNode reference) {
      final var name = reference.parts().get(0);
      if (hooks.scope().isConst(name)) {
        hooks.error("Cannot modify const variable: " + name, node);
      }
    }

    var type = resolver.resolve(node.container());
    for (var index = 0; index < node.indices().size() && type != null; index++) {
      if ("map".equals(type.name()) && !type.parameters().isEmpty()) {
        final var expected = type.parameters().get(0);
        final var actual = resolver.resolve(node.indices().get(index));
        if (actual != null && !resolver.matches(expected, actual)) {
          hooks.error(
              "Map key type mismatch: expected "
                  + expected.fullName()
                  + " but got "
                  + actual.fullName(),
              node);
        }
      }
      if ("list".equals(type.name()) && !type.parameters().isEmpty()) {
        type = type.parameters().get(0);
      } else if ("map".equals(type.name()) && type.parameters().size() >= 2) {
        type = type.parameters().get(1);
      } else {
        type = null;
      }
    }

    final var assigned = resolver.resolve(node.value());
    if (type != null && assigned != null && !resolver.matches(type, assigned)) {
      hooks.error(
          "Index assignment type mismatch: expected "
              + type.fullName()
              + " but got "
              + assigned.fullName(),
          node);
    }
  }

  private boolean defined(final String name, final ASTNode node) {
    if (!hooks.scope().has(name) && !external.contains(name)) {
      hooks.error("Undefined variable: " + name, node);
      return false;
    }
    return true;
  }

  private void checkField(
      final List<String> parts,
      final String name,
      final FieldDeclarationNode field,
      final AssignmentNode node) {
    if (field.isConst()) {
      hooks.error("Cannot reassign const field: " + parts.get(parts.size() - 1), node);
    }
    if (!field.isPublic() && !hooks.module()) {
      final var root = hooks.scope().variable(name);
      if (root != null && typeModule.containsKey(root.name())) {
        hooks.error(
            "Cannot assign to private field '"
                + parts.get(parts.size() - 1)
                + "' of type '"
                + root.name()
                + "'",
            node);
      }
    }
    final var resolved = resolver.resolve(node.value());
    if (resolved != null && !resolver.matches(field.type(), resolved)) {
      hooks.error(
          "Field '"
              + parts.get(parts.size() - 1)
              + "': expected "
              + field.type().fullName()
              + " but got "
              + resolved.fullName(),
          node);
    }
  }

  private void checkMissingField(
      final List<String> parts, final String name, final AssignmentNode node) {
    final var root = hooks.scope().variable(name);
    if (root == null) {
      return;
    }
    var type = types.get(root.name());
    for (var index = 1; index < parts.size(); index++) {
      if (type == null) {
        break;
      }
      final var field = resolver.findField(type, parts.get(index));
      if (field == null) {
        hooks.error("Unknown field '" + parts.get(index) + "' in type '" + type.name() + "'", node);
        break;
      }
      if (index < parts.size() - 1) {
        type = types.get(field.type().name());
      }
    }
  }
}
