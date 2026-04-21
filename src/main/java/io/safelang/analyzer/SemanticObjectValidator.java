package io.safelang.analyzer;

import io.safelang.ast.ASTNode;
import io.safelang.ast.ObjectCreationNode;
import io.safelang.ast.TypeDeclarationNode;
import java.util.HashSet;
import java.util.Map;
import java.util.function.BiConsumer;

final class SemanticObjectValidator {

  private final TypeResolver resolver;
  private final Map<String, TypeDeclarationNode> types;
  private final Map<String, String> typeModule;
  private final BiConsumer<String, ASTNode> error;

  SemanticObjectValidator(
      final TypeResolver resolver,
      final Map<String, TypeDeclarationNode> types,
      final Map<String, String> typeModule,
      final BiConsumer<String, ASTNode> error) {
    this.resolver = resolver;
    this.types = types;
    this.typeModule = typeModule;
    this.error = error;
  }

  void validate(final ObjectCreationNode node, final boolean module) {
    final var name = node.type();
    final var declaration = types.get(name);
    if (declaration == null) {
      error.accept("Undefined type: " + name, node);
      return;
    }

    final var provided = new HashSet<String>();
    for (final var field : node.fields()) {
      if (!provided.add(field.field())) {
        error.accept("Duplicate field '" + field.field() + "' in " + name + " literal", node);
        continue;
      }
      final var expected = resolver.findField(declaration, field.field());
      if (expected == null) {
        error.accept("Unknown field '" + field.field() + "' in type '" + name + "'", node);
      } else {
        final var resolved = resolver.resolve(field.value());
        if (resolved != null && !resolver.matches(expected.type(), resolved)) {
          error.accept(
              "Field '"
                  + field.field()
                  + "' of type '"
                  + name
                  + "': expected "
                  + expected.type().fullName()
                  + " but got "
                  + resolved.fullName(),
              node);
        }
      }
    }

    for (final var field : declaration.fields()) {
      if (!provided.contains(field.name())) {
        error.accept(
            "Missing required field '" + field.name() + "' in " + name + " creation", node);
      }
    }

    if (!module && typeModule.containsKey(name)) {
      for (final var field : declaration.fields()) {
        if (!field.isPublic()) {
          error.accept(
              "Cannot construct type '"
                  + name
                  + "' from outside its module — it has private field '"
                  + field.name()
                  + "'",
              node);
          break;
        }
      }
    }
  }
}
