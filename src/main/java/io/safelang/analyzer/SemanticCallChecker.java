package io.safelang.analyzer;

import io.safelang.ModuleRegistry;
import io.safelang.ast.*;
import io.safelang.runtime.BuiltinRegistry;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class SemanticCallChecker {

  private static final Set<String> RESOURCE_MODULES = Set.of("file", "binary");
  private static final Set<String> OPEN_NAMES = Set.of("open", "fileopen", "bopen");
  private static final Set<String> CLOSE_NAMES = Set.of("close", "fileclose", "bclose");

  private final TypeResolver resolver;
  private final ModuleRegistry registry;
  private final Map<String, FunctionDeclarationNode> functions;
  private final Map<String, Set<String>> selective;
  private final SemanticCallHooks hooks;

  SemanticCallChecker(
      final TypeResolver resolver,
      final ModuleRegistry registry,
      final Map<String, FunctionDeclarationNode> functions,
      final Map<String, Set<String>> selective,
      final SemanticCallHooks hooks) {
    this.resolver = resolver;
    this.registry = registry;
    this.functions = functions;
    this.selective = selective;
    this.hooks = hooks;
  }

  void check(final FunctionCallNode node) {
    for (final var argument : node.arguments()) {
      hooks.analyze(argument);
    }

    final var name = node.name();
    track(node, name);

    if (node.hasPrefix()) {
      qualified(node, name);
      return;
    }

    // If the assignment/declaration target pins a specific enum type, prefer that enum's
    // variant. This disambiguates collisions like mod_a.Status.Ok vs mod_b.Outcome.Ok when
    // the user wrote `mod_b.Outcome s = Ok("hello")`. Expected type names may carry a module
    // qualifier ("mod_b.Outcome") — strip it to match the enum's unqualified identifier.
    final var expected = hooks.expected();
    EnumVariantNode variant = null;
    if (expected != null) {
      final var fullName = expected.name();
      final var enumName =
          fullName.contains(".") ? fullName.substring(fullName.lastIndexOf('.') + 1) : fullName;
      variant = resolver.findVariant(enumName, name);
    }
    if (variant == null) {
      variant = resolver.findVariant(name);
    }
    if (variant != null) {
      variant(node, name, variant);
      return;
    }

    if (hooks.module()) {
      final var builtin = BuiltinRegistry.id(name);
      if (builtin >= 0) {
        final var actual = node.arguments().size();
        final var min = BuiltinRegistry.minimum(builtin);
        final var max = BuiltinRegistry.arity(builtin);
        if (actual >= min && actual <= max) {
          if (hooks.strict() && !BuiltinRegistry.isStrictAllowed(name)) {
            hooks.error("Impure builtin '" + name + "' not allowed in strict mode", node);
          }
          final var signature = BuiltinRegistry.signature(name);
          if (signature != null) {
            resolver.validateArguments(signature, name, node);
          }
          return;
        }
      }
    }

    final var value = hooks.scope().variable(name);
    if (value != null && value.isFunction()) {
      value(node, name, value.parameterTypes());
      return;
    }

    final var function = functions.get(name);
    if (function == null) {
      if (BuiltinRegistry.isBuiltin(name)) {
        hooks.error(
            "Built-in '"
                + name
                + "' requires import. Use: import "
                + BuiltinRegistry.module(name)
                + ";",
            node);
      } else {
        hooks.error("Undefined function: " + name, node);
      }
      return;
    }

    if (!arity(
        function.parameters().size(),
        resolver.requiredCount(function),
        node.arguments().size(),
        "Function '" + name + "'",
        node)) {
      return;
    }

    resolver.validateArguments(function, name, node);
    if (hooks.strict() && hooks.impure(function)) {
      hooks.error("Impure function '" + name + "' not allowed in strict mode", node);
    }
    impure(node.arguments());
  }

  private void track(final FunctionCallNode node, final String name) {
    if (hooks.current() != null) {
      final var qualified = node.hasPrefix() && RESOURCE_MODULES.contains(node.prefix());
      if (OPEN_NAMES.contains(name) && (qualified || BuiltinRegistry.isBuiltin(name))) {
        hooks.open();
      }
      if (CLOSE_NAMES.contains(name) && (qualified || BuiltinRegistry.isBuiltin(name))) {
        hooks.close();
      }
    }
  }

  private void qualified(final FunctionCallNode node, final String name) {
    final var module = node.prefix();
    hooks.use(module);
    if (!registry.has(module)) {
      hooks.error("Unknown module: " + module, node);
      return;
    }
    final var selected = selective.get(module);
    if (selected != null && !selected.contains(name)) {
      // Allow qualified enum variant construction (mod:Ok(...)) even when the enum's name isn't
      // in the selective set — the variant's owning enum is the gate, and the user is explicit
      // by qualifying.
      final var qualifiedVariant = findVariant(module, name);
      if (qualifiedVariant == null) {
        hooks.error(
            "Function '"
                + name
                + "' was not included in selective import of module '"
                + module
                + "'",
            node);
        return;
      }
      variant(node, name, qualifiedVariant);
      return;
    }

    final var function = registry.function(module, name);
    if (function == null) {
      // Before erroring, check if the name refers to an enum variant exported by the module.
      // WasmCompiler.java:1054 already supports qualified variant construction but the analyzer
      // previously rejected it. This path unblocks mod:Ok(42) end-to-end.
      final var variant = findVariant(module, name);
      if (variant != null) {
        variant(node, name, variant);
        return;
      }
      // Qualified call to a module-owned builtin with no SAFE trampoline (e.g. std:range):
      // resolve directly against the registry, mirroring the unqualified builtin path.
      final var builtin = BuiltinRegistry.id(name);
      if (builtin >= 0 && module.equals(BuiltinRegistry.module(name))) {
        if (!arity(
            BuiltinRegistry.arity(builtin),
            BuiltinRegistry.minimum(builtin),
            node.arguments().size(),
            "Function '" + module + ":" + name + "'",
            node)) {
          return;
        }
        if (hooks.strict() && !BuiltinRegistry.isStrictAllowed(name)) {
          hooks.error("Impure builtin '" + name + "' not allowed in strict mode", node);
        }
        final var signature = BuiltinRegistry.signature(name);
        if (signature != null) {
          resolver.validateArguments(signature, name, node);
        }
        return;
      }
      hooks.error("Undefined function '" + name + "' in module '" + module + "'", node);
      return;
    }
    if (!function.isPublic()) {
      hooks.error("Cannot access private function '" + name + "' in module '" + module + "'", node);
      return;
    }
    if (!arity(
        function.parameters().size(),
        resolver.requiredCount(function),
        node.arguments().size(),
        "Function '" + module + ":" + name + "'",
        node)) {
      return;
    }

    if (hooks.strict()) {
      if (BuiltinRegistry.isBuiltin(name)
          && module.equals(BuiltinRegistry.module(name))
          && !BuiltinRegistry.isStrictAllowed(name)) {
        hooks.error("Impure builtin '" + name + "' not allowed in strict mode", node);
      } else if (hooks.impure(function, module)) {
        // Use the module-aware overload so unqualified calls inside the
        // called module's body resolve against THAT module's namespace, not
        // main's. This catches the audit's reproducer:
        //   strict main → mod:public → mod:private → time()
        // which the non-module-aware check missed because mod:private was
        // unresolvable in main's function map.
        hooks.error(
            "Function '"
                + module
                + ":"
                + name
                + "' calls impure builtins and is not allowed in strict mode",
            node);
      }
    }

    impure(node.arguments());
    final var builtin = BuiltinRegistry.signature(name);
    final var typed =
        (builtin != null
                && module.equals(BuiltinRegistry.module(name))
                && resolver.hasVariables(builtin))
            ? builtin
            : function;
    resolver.validateArguments(typed, name, node);
  }

  private EnumVariantNode findVariant(final String module, final String name) {
    if (!registry.has(module)) {
      return null;
    }
    for (final var enumeration : registry.enums(module).values()) {
      if (!enumeration.isPublic()) {
        continue;
      }
      for (final var variant : enumeration.variants()) {
        if (variant.name().equals(name)) {
          return variant;
        }
      }
    }
    return null;
  }

  private void variant(
      final FunctionCallNode node, final String name, final EnumVariantNode variant) {
    final var expected = variant.fields().size();
    final var actual = node.arguments().size();
    if (expected != actual) {
      hooks.error(
          "Enum variant '" + name + "' expects " + expected + " argument(s) but got " + actual,
          node);
      return;
    }
    for (var index = 0; index < expected; index++) {
      final var resolved = resolver.resolve(node.arguments().get(index));
      if (resolved != null && !resolver.matches(variant.fields().get(index), resolved)) {
        hooks.error(
            "Enum variant '"
                + name
                + "' argument "
                + (index + 1)
                + ": expected "
                + variant.fields().get(index).fullName()
                + " but got "
                + resolved.fullName(),
            node);
      }
    }
  }

  private void value(final FunctionCallNode node, final String name, final List<TypeNode> params) {
    final var expected = params.size();
    final var actual = node.arguments().size();
    if (actual != expected) {
      hooks.error(
          "Function value '" + name + "' expects " + expected + " argument(s) but got " + actual,
          node);
      return;
    }
    for (var index = 0; index < actual; index++) {
      final var resolved = resolver.resolve(node.arguments().get(index));
      if (resolved != null
          && index < params.size()
          && params.get(index) != null
          && !resolver.matches(params.get(index), resolved)) {
        hooks.error(
            "Function value '"
                + name
                + "' argument "
                + (index + 1)
                + ": expected "
                + params.get(index).fullName()
                + " but got "
                + resolved.fullName(),
            node);
      }
    }
  }

  private boolean arity(
      final int max, final int min, final int actual, final String name, final ASTNode node) {
    if (actual < min || actual > max) {
      if (min == max) {
        hooks.error(name + " expects " + max + " argument(s) but got " + actual, node);
      } else {
        hooks.error(
            name + " expects " + min + " to " + max + " argument(s) but got " + actual, node);
      }
      return false;
    }
    return true;
  }

  private void impure(final List<ASTNode> arguments) {
    if (hooks.strict()) {
      for (final var argument : arguments) {
        if (argument instanceof LambdaNode && hooks.impure(argument)) {
          hooks.error("Impure lambda argument not allowed in strict mode", argument);
          break;
        }
      }
    }
  }
}
