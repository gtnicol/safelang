package io.safelang.analyzer;

import io.safelang.ModuleRegistry;
import io.safelang.ast.*;
import io.safelang.runtime.BuiltinRegistry;
import java.util.*;

/**
 * Handles type inference, type matching, generic binding, union narrowing, argument validation, and
 * type lookups for the semantic analyzer.
 */
class TypeResolver {

  private final Map<String, FunctionDeclarationNode> functions;
  private final Map<String, TypeDeclarationNode> types;
  private final Map<String, EnumDeclarationNode> enums;
  private final Map<String, TypeNode> aliases;
  private final ModuleRegistry registry;
  private final Map<ASTNode, TypeNode> cache;
  private final Deque<TypeNode> expectedTypes = new ArrayDeque<>();
  private TypeEnvironment scope;

  TypeResolver(
      final Map<String, FunctionDeclarationNode> functions,
      final Map<String, TypeDeclarationNode> types,
      final Map<String, EnumDeclarationNode> enums,
      final Map<String, TypeNode> aliases,
      final ModuleRegistry registry,
      final Map<ASTNode, TypeNode> cache) {
    this.functions = functions;
    this.types = types;
    this.enums = enums;
    this.aliases = aliases;
    this.registry = registry;
    this.cache = cache;
  }

  void setScope(final TypeEnvironment scope) {
    this.scope = scope;
  }

  void pushExpected(final TypeNode expected) {
    expectedTypes.push(expected);
  }

  void popExpected() {
    expectedTypes.pop();
  }

  private TypeNode expected() {
    return expectedTypes.isEmpty() ? null : expectedTypes.peek();
  }

  TypeNode resolve(final ASTNode node) {
    if (node == null) return null;
    return switch (node) {
      case LiteralNode literal -> {
        final var name =
            switch (literal) {
              case LiteralNode.IntLiteral ignored -> "int";
              case LiteralNode.FloatLiteral ignored -> "float";
              case LiteralNode.UintLiteral ignored -> "uint";
              case LiteralNode.StringLiteral ignored -> "string";
              case LiteralNode.BoolLiteral ignored -> "boolean";
            };
        yield simple(literal.line(), literal.column(), name);
      }
      case VariableReferenceNode reference -> {
        final var parts = reference.parts();
        if (parts.isEmpty()) {
          yield null;
        }
        // Module-qualified variable via dot syntax: math.PI → parts=["math","PI"]
        if (parts.size() >= 2 && registry != null && registry.has(parts.get(0))) {
          final var module = parts.get(0);
          final var program = registry.program(module);
          if (program != null) {
            final var target = parts.get(1);
            // Search both declarations and statements for module constants
            for (final var declaration : program.declarations()) {
              if (declaration instanceof VariableDeclarationNode variable) {
                if (variable.name().equals(target) && variable.type() != null) {
                  yield variable.type();
                }
              }
            }
            for (final var statement : program.statements()) {
              if (statement instanceof VariableDeclarationNode variable) {
                if (variable.name().equals(target) && variable.type() != null) {
                  yield variable.type();
                }
              }
            }
          }
        }
        // Module-qualified variable via prefix (colon) syntax
        if (reference.hasPrefix() && registry != null && registry.has(reference.prefix())) {
          final var program = registry.program(reference.prefix());
          if (program != null && !parts.isEmpty()) {
            final var target = parts.get(0);
            // Search both declarations and statements for module constants
            for (final var declaration : program.declarations()) {
              if (declaration instanceof VariableDeclarationNode variable) {
                if (variable.name().equals(target) && variable.type() != null) {
                  yield variable.type();
                }
              }
            }
            for (final var statement : program.statements()) {
              if (statement instanceof VariableDeclarationNode variable) {
                if (variable.name().equals(target) && variable.type() != null) {
                  yield variable.type();
                }
              }
            }
          }
        }
        final var type = scope.variable(parts.get(0));
        if (type != null) {
          if (parts.size() == 1) {
            yield type;
          }
          yield resolveFieldType(type, parts, 1);
        }
        // Function reference: bare name resolves to fn type
        if (parts.size() == 1) {
          final var function = functions.get(parts.get(0));
          if (function != null) {
            final var parameters = new ArrayList<TypeNode>();
            for (final var parameter : function.parameters()) {
              parameters.add(parameter.type());
            }
            parameters.add(function.returns());
            yield TypeNode.withParameters(node.line(), node.column(), "fn", parameters);
          }
        }
        yield null;
      }
      case FunctionCallNode call -> {
        // Qualified variant construction: mod:Ok(42) → the owning enum's type.
        if (call.hasPrefix() && registry.has(call.prefix())) {
          for (final var declaration : registry.enums(call.prefix()).values()) {
            if (!declaration.isPublic()) continue;
            for (final var info : declaration.variants()) {
              if (info.name().equals(call.name())) {
                yield simple(call.line(), call.column(), declaration.name());
              }
            }
          }
        }
        // If the construction context pins an enum type, prefer that enum's variant so the
        // resolved type matches the declared target (fixes `mod_b.Outcome s = Ok("hello")`).
        final var expected = expected();
        if (expected != null) {
          final var fullName = expected.name();
          final var enumName =
              fullName.contains(".") ? fullName.substring(fullName.lastIndexOf('.') + 1) : fullName;
          if (findVariant(enumName, call.name()) != null) {
            yield simple(call.line(), call.column(), enumName);
          }
        }
        final var variant = findVariant(call.name());
        if (variant != null) {
          yield findEnumType(call.name());
        }
        FunctionDeclarationNode function = null;
        if (call.hasPrefix() && registry.has(call.prefix())) {
          function = registry.function(call.prefix(), call.name());
          // Use the builtin signature when there is no SAFE wrapper function (a builtin invoked
          // directly under its module, e.g. file:sread -> ReadResult), or when it carries richer
          // generic type info than the wrapper.
          final var builtin = BuiltinRegistry.signature(call.name());
          if (builtin != null
              && call.prefix().equals(BuiltinRegistry.module(call.name()))
              && (function == null || hasVariables(builtin))) {
            function = builtin;
          }
        } else if (!call.hasPrefix()) {
          function = functions.get(call.name());
        }
        if (function != null) {
          if (hasVariables(function)) {
            final var bindings = new HashMap<String, TypeNode>();
            for (int i = 0; i < function.parameters().size() && i < call.arguments().size(); i++) {
              final var resolved = resolve(call.arguments().get(i));
              if (resolved != null) {
                bind(function.parameters().get(i).type(), resolved, bindings);
              }
            }
            yield substitute(function.returns(), bindings);
          }
          yield narrow(function.returns(), function, call.arguments());
        }
        // Call-through-value: f(args) where f is fn(...) -> T
        if (!call.hasPrefix()) {
          final var variable = scope.variable(call.name());
          if (variable != null && variable.isFunction()) {
            yield variable.returnType();
          }
        }
        yield null;
      }
      case ObjectCreationNode creation -> {
        yield simple(creation.line(), creation.column(), creation.type());
      }
      case ListLiteralNode list -> {
        var result = new TypeNode(list.line(), list.column(), "list");
        if (!list.elements().isEmpty()) {
          final var element = resolve(list.elements().get(0));
          if (element != null) {
            result = TypeNode.withParameters(list.line(), list.column(), "list", List.of(element));
            for (int i = 1; i < list.elements().size(); i++) {
              final var other = resolve(list.elements().get(i));
              if (other != null && !matches(element, other)) {
                error(
                    "List element type mismatch at index "
                        + i
                        + ": expected "
                        + element.fullName()
                        + " but got "
                        + other.fullName(),
                    node);
              }
            }
          }
        }
        yield result;
      }
      case SetLiteralNode set -> {
        var result = new TypeNode(set.line(), set.column(), "set");
        if (!set.elements().isEmpty()) {
          final var element = resolve(set.elements().get(0));
          if (element != null) {
            result = TypeNode.withParameters(set.line(), set.column(), "set", List.of(element));
            for (int i = 1; i < set.elements().size(); i++) {
              final var other = resolve(set.elements().get(i));
              if (other != null && !matches(element, other)) {
                error(
                    "Set element type mismatch at index "
                        + i
                        + ": expected "
                        + element.fullName()
                        + " but got "
                        + other.fullName(),
                    node);
              }
            }
          }
        }
        yield result;
      }
      case MapLiteralNode map -> {
        var result = new TypeNode(map.line(), map.column(), "map");
        if (!map.entries().isEmpty()) {
          final var entry = map.entries().get(0);
          final var key = resolve(entry.key());
          final var element = resolve(entry.value());
          if (key != null && element != null) {
            result =
                TypeNode.withParameters(map.line(), map.column(), "map", List.of(key, element));
            for (int i = 1; i < map.entries().size(); i++) {
              final var other = map.entries().get(i);
              final var actual = resolve(other.key());
              final var found = resolve(other.value());
              if (actual != null && !matches(key, actual)) {
                error(
                    "Map key type mismatch at entry "
                        + i
                        + ": expected "
                        + key.fullName()
                        + " but got "
                        + actual.fullName(),
                    node);
              }
              if (found != null && !matches(element, found)) {
                error(
                    "Map value type mismatch at entry "
                        + i
                        + ": expected "
                        + element.fullName()
                        + " but got "
                        + found.fullName(),
                    node);
              }
            }
          }
        }
        yield result;
      }
      case BinaryExpressionNode binary -> {
        final var operator = binary.operator();
        if (isComparison(operator) || isLogical(operator) || "in".equals(operator)) {
          yield simple(binary.line(), binary.column(), "boolean");
        }
        final var left = resolve(binary.left());
        final var right = resolve(binary.right());
        if ("+".equals(operator)) {
          // String concat if either operand is string
          if (left != null && "string".equals(left.name())) yield left;
          if (right != null && "string".equals(right.name())) yield right;
        }
        // Numeric promotion: if either side is float, result is float
        if (left != null && right != null) {
          if ("float".equals(left.name()) || "float".equals(right.name())) {
            yield simple(binary.line(), binary.column(), "float");
          }
          // Uint promotion: if either side is uint, result is uint
          if ("uint".equals(left.name()) || "uint".equals(right.name())) {
            yield simple(binary.line(), binary.column(), "uint");
          }
        }
        yield left;
      }
      case UnaryExpressionNode unary -> {
        if ("!".equals(unary.operator())) {
          yield simple(unary.line(), unary.column(), "boolean");
        }
        yield resolve(unary.operand());
      }
      case IfExpressionNode conditional -> {
        final var then = resolve(conditional.then());
        if (conditional.hasOtherwise()) {
          final var otherwise = resolve(conditional.otherwise());
          if (then != null
              && otherwise != null
              && !matches(then, otherwise)
              && !matches(otherwise, then)) {
            error(
                "If branch type mismatch: then is "
                    + then.fullName()
                    + " but else is "
                    + otherwise.fullName(),
                node);
          }
          yield then;
        }
        // Without else branch, runtime produces void
        yield simple(node.line(), node.column(), "void");
      }
      case CaseExpressionNode match -> {
        TypeNode first = null;
        if (!match.branches().isEmpty()) {
          first = resolve(match.branches().get(0).result());
          for (int i = 1; i < match.branches().size(); i++) {
            final var branch = resolve(match.branches().get(i).result());
            if (first != null
                && branch != null
                && !matches(first, branch)
                && !matches(branch, first)) {
              error(
                  "Case branch type mismatch: expected "
                      + first.fullName()
                      + " but got "
                      + branch.fullName(),
                  node);
            }
          }
        }
        if (match.hasFallback()) {
          final var fallback = resolve(match.fallback());
          if (first != null
              && fallback != null
              && !matches(first, fallback)
              && !matches(fallback, first)) {
            error(
                "Case fallback type mismatch: expected "
                    + first.fullName()
                    + " but got "
                    + fallback.fullName(),
                node);
          }
          if (first == null) {
            first = fallback;
          }
        }
        yield first;
      }
      case IndexAccessNode access -> {
        final var container = resolve(access.container());
        if (container != null
            && "list".equals(container.name())
            && !container.parameters().isEmpty()) {
          yield container.parameters().get(0);
        }
        if (container != null
            && "map".equals(container.name())
            && container.parameters().size() >= 2) {
          yield container.parameters().get(1);
        }
        if (container != null
            && "tuple".equals(container.name())
            && !container.parameters().isEmpty()) {
          // For tuple[i] with literal index, return the specific element type
          if (access.index() instanceof LiteralNode.IntLiteral literal) {
            final var position = (int) literal.value();
            if (position >= 0 && position < container.parameters().size()) {
              yield container.parameters().get(position);
            }
          }
          // Non-literal index is type-unsound for heterogeneous tuples
          yield null;
        }
        yield null;
      }
      case TupleLiteralNode tuple -> {
        final var parameters = new ArrayList<TypeNode>();
        for (final var element : tuple.elements()) {
          parameters.add(resolve(element));
        }
        yield TypeNode.withParameters(node.line(), node.column(), "tuple", parameters);
      }
      case LambdaNode lambda -> {
        final var parameters = new ArrayList<TypeNode>();
        // Temporarily push lambda params into scope to resolve body type
        final var outer = scope;
        scope = scope.child();
        for (final var parameter : lambda.parameters()) {
          if (parameter.type() != null) {
            parameters.add(parameter.type());
            scope.define(parameter.name(), parameter.type(), false);
          } else {
            final var placeholder = simple(node.line(), node.column(), "?");
            parameters.add(placeholder);
            scope.define(parameter.name(), placeholder, false);
          }
        }
        // Body type as return type
        final var body = resolve(lambda.body());
        parameters.add(body != null ? body : simple(node.line(), node.column(), "void"));
        scope = outer;
        yield TypeNode.withParameters(node.line(), node.column(), "fn", parameters);
      }
      case DoExpressionNode block -> {
        final var cached = cache.get(block);
        if (cached != null) yield cached;
        final var resolved = resolve(block.expression());
        if (resolved != null) cache.put(block, resolved);
        yield resolved;
      }
      case RangeNode ignored -> {
        yield TypeNode.withParameters(
            node.line(), node.column(), "list", List.of(simple(node.line(), node.column(), "int")));
      }
      case StringInterpolationNode ignored -> {
        yield simple(node.line(), node.column(), "string");
      }
      case FieldAccessNode access -> {
        final var receiver = resolve(access.receiver());
        if (receiver != null) {
          final var declaration = types.get(receiver.name());
          if (declaration != null) {
            final var field = findField(declaration, access.field());
            if (field != null) {
              yield field.type();
            }
          }
        }
        yield null;
      }
      default -> null;
    };
  }

  void validateStructural(final TypeNode declared, final ASTNode node) {
    if (declared == null) {
      return;
    }

    // For union types, skip deep structural validation — matches() handles it
    if (declared.isUnion()) {
      return;
    }

    switch (node) {
      case MapLiteralNode map -> {
        if (map.entries().isEmpty()) {
          return;
        }
        if (!"map".equals(declared.name()) || declared.parameters().size() != 2) {
          error("Type mismatch: expected " + declared.fullName() + " but got map literal", node);
          return;
        }
        final var key = declared.parameters().get(0);
        final var element = declared.parameters().get(1);
        for (final var entry : map.entries()) {
          validateStructural(key, entry.key());
          validateStructural(element, entry.value());
        }
      }
      case ListLiteralNode list -> {
        if (list.elements().isEmpty()) {
          return;
        }
        if (!"list".equals(declared.name()) || declared.parameters().size() != 1) {
          error("Type mismatch: expected " + declared.fullName() + " but got list literal", node);
          return;
        }
        final var element = declared.parameters().get(0);
        for (final var item : list.elements()) {
          validateStructural(element, item);
        }
      }
      case SetLiteralNode set -> {
        if (set.elements().isEmpty()) {
          return;
        }
        if (!"set".equals(declared.name()) || declared.parameters().size() != 1) {
          error("Type mismatch: expected " + declared.fullName() + " but got set literal", node);
          return;
        }
        final var element = declared.parameters().get(0);
        for (final var item : set.elements()) {
          validateStructural(element, item);
        }
      }
      case LiteralNode literal -> {
        final var actual =
            switch (literal) {
              case LiteralNode.IntLiteral ignored -> "int";
              case LiteralNode.FloatLiteral ignored -> "float";
              case LiteralNode.UintLiteral ignored -> "uint";
              case LiteralNode.StringLiteral ignored -> "string";
              case LiteralNode.BoolLiteral ignored -> "boolean";
            };
        final var resolved = resolveAlias(declared);
        if (!actual.equals(resolved.name())) {
          error(
              "Type mismatch: expected " + declared.fullName() + " but got " + actual + " literal",
              node);
        }
      }
      case ObjectCreationNode object -> {
        final var resolved = resolveAlias(declared);
        if (!resolved.name().equals(object.type())) {
          error(
              "Type mismatch: expected " + declared.fullName() + " but got " + object.type(), node);
        }
      }
      default -> {}
    }
  }

  void validateArguments(
      final FunctionDeclarationNode function, final String name, final FunctionCallNode node) {
    final var count = Math.min(function.parameters().size(), node.arguments().size());
    if (hasVariables(function)) {
      final var bindings = new HashMap<String, TypeNode>();
      final var parameters = function.parameters();
      for (int i = 0; i < count; i++) {
        final var resolved = resolve(node.arguments().get(i));
        if (resolved != null) {
          if (!bind(parameters.get(i).type(), resolved, bindings)) {
            error(
                "Function '"
                    + name
                    + "' argument "
                    + (i + 1)
                    + ": type mismatch for generic parameter",
                node);
          }
        }
      }
      for (int i = 0; i < count; i++) {
        final var resolved = resolve(node.arguments().get(i));
        final var parameter = substitute(parameters.get(i).type(), bindings);
        if (resolved != null && parameter != null && !matches(parameter, resolved)) {
          error(
              "Function '"
                  + name
                  + "' argument "
                  + (i + 1)
                  + ": expected "
                  + parameter.fullName()
                  + " but got "
                  + resolved.fullName(),
              node);
        }
      }
    } else {
      for (int i = 0; i < count; i++) {
        final var resolved = resolve(node.arguments().get(i));
        final var parameter = function.parameters().get(i).type();
        if (resolved != null && parameter != null && !matches(parameter, resolved)) {
          error(
              "Function '"
                  + name
                  + "' argument "
                  + (i + 1)
                  + ": expected "
                  + parameter.fullName()
                  + " but got "
                  + resolved.fullName(),
              node);
        }
      }
    }
  }

  /**
   * Compare type names treating a dotted module-qualified name as equivalent to the unqualified
   * last-component form. This lets {@code mod_b.Outcome} match the imported enum name {@code
   * Outcome} stored under its unqualified key.
   */
  private boolean equivalentName(final String expected, final String actual) {
    if (expected.equals(actual)) {
      return true;
    }
    final var expectedTail =
        expected.contains(".") ? expected.substring(expected.lastIndexOf('.') + 1) : expected;
    final var actualTail =
        actual.contains(".") ? actual.substring(actual.lastIndexOf('.') + 1) : actual;
    return expectedTail.equals(actualTail);
  }

  TypeNode resolveAlias(final TypeNode node) {
    if (node == null || node.isUnion() || node.isVariable()) return node;
    var result = node;
    final var visited = new HashSet<String>();
    while (aliases.containsKey(result.name()) && !result.isParameterized()) {
      if (!visited.add(result.name())) break;
      result = aliases.get(result.name());
    }
    return result;
  }

  boolean matches(final TypeNode expected, final TypeNode actual) {
    if (expected == null || actual == null) {
      return true;
    }
    // Resolve aliases before matching
    final var left = resolveAlias(expected);
    final var right = resolveAlias(actual);
    return resolved(left, right);
  }

  private boolean resolved(final TypeNode expected, final TypeNode actual) {
    if (expected == null || actual == null) {
      return true;
    }
    if (expected.isVariable() || "?".equals(expected.name())) {
      return true;
    }
    if (actual.isVariable() || "?".equals(actual.name())) {
      return true;
    }
    // Function types with inferred params: accept if either side is fn with ? placeholders
    if (expected.isFunction() && actual.isFunction()) {
      for (final var parameter : actual.parameters()) {
        if ("?".equals(parameter.name()) || parameter.isVariable()) {
          return true;
        }
      }
    }
    // If expected is a union, actual must match at least one member
    if (expected.isUnion()) {
      if (actual.isUnion()) {
        // Every member of actual must match some member of expected
        for (final var member : actual.members()) {
          var found = false;
          for (final var target : expected.members()) {
            if (matches(target, member)) {
              found = true;
              break;
            }
          }
          if (!found) return false;
        }
        return true;
      }
      for (final var member : expected.members()) {
        if (matches(member, actual)) return true;
      }
      return false;
    }
    // If actual is a union, every member must match expected
    if (actual.isUnion()) {
      for (final var member : actual.members()) {
        if (!matches(expected, member)) return false;
      }
      return true;
    }
    if (!equivalentName(expected.name(), actual.name())) {
      return false;
    }
    if (expected.parameters().size() != actual.parameters().size()) {
      // A raw (zero-parameter) type matches a parameterized one of the same name. This is a
      // deliberate inference hole, not a loose check: an empty literal `[]`/`{}` resolves to raw
      // `list`/`map`/`set` (see resolve(ListLiteralNode)), and `None`/`Err` to raw `Option`/
      // `Result`, with no element type to compare. Rejecting these requires a wildcard/unknown
      // type to carry the "element unknown" provenance — absent that, raw means "any args".
      return expected.parameters().isEmpty() || actual.parameters().isEmpty();
    }
    for (int i = 0; i < expected.parameters().size(); i++) {
      if (!matches(expected.parameters().get(i), actual.parameters().get(i))) {
        return false;
      }
    }
    return true;
  }

  EnumVariantNode findVariant(final String name) {
    for (final var enumeration : enums.values()) {
      for (final var variant : enumeration.variants()) {
        if (variant.name().equals(name)) {
          return variant;
        }
      }
    }
    return null;
  }

  /**
   * Module-aware variant lookup. Restricts the search to the named enum, which is the
   * disambiguation context available at case branches (subject's declared enum type) and at
   * construction sites with a declared target type.
   */
  EnumVariantNode findVariant(final String enumName, final String variantName) {
    final var enumeration = enums.get(enumName);
    if (enumeration == null) {
      return null;
    }
    for (final var variant : enumeration.variants()) {
      if (variant.name().equals(variantName)) {
        return variant;
      }
    }
    return null;
  }

  TypeNode findEnumType(final String variant) {
    for (final var entry : enums.entrySet()) {
      for (final var v : entry.getValue().variants()) {
        if (v.name().equals(variant)) {
          return simple(v.line(), v.column(), entry.getKey());
        }
      }
    }
    return null;
  }

  FieldDeclarationNode findField(final TypeDeclarationNode type, final String name) {
    for (final var field : type.fields()) {
      if (field.name().equals(name)) {
        return field;
      }
    }
    return null;
  }

  FieldDeclarationNode resolveField(final List<String> parts) {
    if (parts.size() < 2) {
      return null;
    }
    final var root = scope.variable(parts.get(0));
    if (root == null) {
      return null;
    }
    var type = types.get(root.name());
    FieldDeclarationNode field = null;
    for (int i = 1; i < parts.size(); i++) {
      if (type == null) {
        return null;
      }
      field = findField(type, parts.get(i));
      if (field == null) {
        return null;
      }
      if (i < parts.size() - 1) {
        type = types.get(field.type().name());
      }
    }
    return field;
  }

  TypeNode resolveFieldType(final TypeNode root, final List<String> parts, final int start) {
    var type = types.get(root.name());
    TypeNode result = root;
    for (int i = start; i < parts.size(); i++) {
      if (type == null) {
        return null;
      }
      final var field = findField(type, parts.get(i));
      if (field == null) {
        return null;
      }
      result = field.type();
      if (i < parts.size() - 1) {
        type = types.get(field.type().name());
      }
    }
    return result;
  }

  boolean isEnumName(final String name) {
    return enums.containsKey(name);
  }

  boolean hasVariables(final FunctionDeclarationNode function) {
    if (variable(function.returns())) return true;
    for (final var parameter : function.parameters()) {
      if (variable(parameter.type())) return true;
    }
    return false;
  }

  private boolean variable(final TypeNode type) {
    if (type == null) return false;
    if (type.isVariable()) return true;
    if (type.isUnion()) {
      for (final var member : type.members()) {
        if (variable(member)) return true;
      }
    }
    for (final var parameter : type.parameters()) {
      if (variable(parameter)) return true;
    }
    return false;
  }

  private boolean bind(
      final TypeNode pattern, final TypeNode actual, final Map<String, TypeNode> bindings) {
    if (pattern == null || actual == null) return true;
    if (pattern.isVariable()) {
      final var existing = bindings.get(pattern.name());
      if (existing == null) {
        bindings.put(pattern.name(), actual);
        return true;
      }
      return matches(existing, actual);
    }
    if (pattern.isUnion()) {
      // For union patterns, actual must match at least one member.
      // Clone bindings to try each member; merge back on success.
      for (final var member : pattern.members()) {
        final var trial = new HashMap<>(bindings);
        if (bind(member, actual, trial)) {
          bindings.putAll(trial);
          return true;
        }
      }
      return false;
    }
    // Module-aware name match, consistent with matches(): bind `mod.Box<int>` against `Box<int>`.
    if (!equivalentName(pattern.name(), actual.name())) return false;
    // Raw on either side is the same inference hole tolerated by matches() — see the note there.
    if (pattern.parameters().isEmpty() || actual.parameters().isEmpty()) return true;
    if (pattern.parameters().size() != actual.parameters().size()) return false;
    for (int i = 0; i < pattern.parameters().size(); i++) {
      if (!bind(pattern.parameters().get(i), actual.parameters().get(i), bindings)) return false;
    }
    return true;
  }

  private TypeNode substitute(final TypeNode type, final Map<String, TypeNode> bindings) {
    if (type == null) return null;
    if (type.isVariable()) {
      final var bound = bindings.get(type.name());
      return bound != null ? bound : type;
    }
    if (type.isUnion()) {
      final var substituted = new ArrayList<TypeNode>();
      for (final var member : type.members()) {
        substituted.add(substitute(member, bindings));
      }
      return TypeNode.withMembers(type.line(), type.column(), substituted);
    }
    if (type.parameters().isEmpty()) return type;
    final var substituted = new ArrayList<TypeNode>();
    for (final var parameter : type.parameters()) {
      substituted.add(substitute(parameter, bindings));
    }
    return TypeNode.withParameters(type.line(), type.column(), type.name(), substituted);
  }

  private TypeNode narrow(
      final TypeNode returns,
      final FunctionDeclarationNode function,
      final List<ASTNode> arguments) {
    if (!returns.isUnion()) return returns;

    TypeNode widest = null;
    for (int i = 0; i < function.parameters().size() && i < arguments.size(); i++) {
      final var parameter = function.parameters().get(i).type();
      if (parameter == null) continue;

      final var resolved = resolve(arguments.get(i));
      if (resolved == null || resolved.isUnion()) {
        return returns; // can't determine concrete type, keep full union
      }

      // Extract the concrete type to match against return union members.
      // Direct union parameter: use resolved type directly.
      // Parameterized with nested union: extract element type from argument.
      TypeNode concrete = null;
      if (parameter.isUnion()) {
        concrete = resolved;
      } else if (parameter.isParameterized()
          && resolved.isParameterized()
          && parameter.name().equals(resolved.name())) {
        concrete = concrete(parameter, resolved);
      }
      if (concrete == null) continue;

      TypeNode matched = null;
      for (final var member : returns.members()) {
        if (matches(member, concrete)) {
          matched = member;
          break;
        }
      }
      if (matched == null) {
        return returns; // no matching member, keep full union
      }

      if (widest == null) {
        widest = matched;
      } else if (!matched.name().equals(widest.name())) {
        if (wider(matched, widest)) {
          widest = matched;
        } else if (!wider(widest, matched)) {
          return returns; // incomparable types, keep full union
        }
      }
    }

    return widest != null ? widest : returns;
  }

  private TypeNode concrete(final TypeNode parameter, final TypeNode argument) {
    // Walk type parameters looking for a union in the parameter signature.
    // When found, use the corresponding argument type parameter as the concrete type.
    for (int j = 0; j < parameter.parameters().size() && j < argument.parameters().size(); j++) {
      final var nested = parameter.parameters().get(j);
      final var actual = argument.parameters().get(j);
      if (nested.isUnion()) {
        return actual;
      }
      // Recurse into deeper generics (e.g., list<list<int|float>>)
      if (nested.isParameterized()
          && actual.isParameterized()
          && nested.name().equals(actual.name())) {
        final var deep = concrete(nested, actual);
        if (deep != null) return deep;
      }
    }
    return null;
  }

  private boolean wider(final TypeNode a, final TypeNode b) {
    if ("float".equals(a.name())) {
      return "int".equals(b.name()) || "uint".equals(b.name());
    }
    return false;
  }

  boolean isComparison(final String operator) {
    return "==".equals(operator)
        || "!=".equals(operator)
        || "<".equals(operator)
        || ">".equals(operator)
        || "<=".equals(operator)
        || ">=".equals(operator);
  }

  boolean isLogical(final String operator) {
    return "&&".equals(operator) || "||".equals(operator);
  }

  boolean isArithmetic(final String operator) {
    return "+".equals(operator)
        || "-".equals(operator)
        || "*".equals(operator)
        || "/".equals(operator)
        || "%".equals(operator);
  }

  boolean isBitwise(final String operator) {
    return "&".equals(operator)
        || "|".equals(operator)
        || "^".equals(operator)
        || "<<".equals(operator)
        || ">>".equals(operator);
  }

  boolean isIntegral(final TypeNode type) {
    if (type.isUnion()) {
      for (final var member : type.members()) {
        if (!isIntegral(member)) return false;
      }
      return true;
    }
    final var name = type.name();
    return "int".equals(name) || "uint".equals(name);
  }

  boolean isNumeric(final TypeNode type) {
    if (type.isUnion()) {
      for (final var member : type.members()) {
        if (!isNumeric(member)) return false;
      }
      return true;
    }
    final var name = type.name();
    return "int".equals(name) || "float".equals(name) || "uint".equals(name);
  }

  int requiredCount(final FunctionDeclarationNode function) {
    var count = 0;
    for (final var parameter : function.parameters()) {
      if (!parameter.hasDefault()) count++;
    }
    return count;
  }

  TypeNode simple(final int line, final int column, final String name) {
    return new TypeNode(line, column, name);
  }

  private void error(final String message, final ASTNode node) {
    throw new SemanticException(message, node.line(), node.column());
  }
}
