package io.safelang.analyzer;

import io.safelang.ModuleRegistry;
import io.safelang.ast.ASTNode;
import io.safelang.ast.ASTVisitor;
import io.safelang.ast.AssertNode;
import io.safelang.ast.AssignmentNode;
import io.safelang.ast.BinaryExpressionNode;
import io.safelang.ast.CaseBranchNode;
import io.safelang.ast.CaseExpressionNode;
import io.safelang.ast.DestructureNode;
import io.safelang.ast.DoExpressionNode;
import io.safelang.ast.EnumDeclarationNode;
import io.safelang.ast.EnumPatternNode;
import io.safelang.ast.EnumVariantNode;
import io.safelang.ast.ExpressionStatementNode;
import io.safelang.ast.FieldAccessNode;
import io.safelang.ast.FieldAssignmentNode;
import io.safelang.ast.FieldDeclarationNode;
import io.safelang.ast.ForStatementNode;
import io.safelang.ast.FunctionCallNode;
import io.safelang.ast.FunctionDeclarationNode;
import io.safelang.ast.IfExpressionNode;
import io.safelang.ast.ImportNode;
import io.safelang.ast.IndexAccessNode;
import io.safelang.ast.IndexAssignmentNode;
import io.safelang.ast.LambdaNode;
import io.safelang.ast.ListLiteralNode;
import io.safelang.ast.LiteralNode;
import io.safelang.ast.MapEntryNode;
import io.safelang.ast.MapLiteralNode;
import io.safelang.ast.ObjectCreationNode;
import io.safelang.ast.ParameterNode;
import io.safelang.ast.ProgramNode;
import io.safelang.ast.RangeNode;
import io.safelang.ast.ReturnNode;
import io.safelang.ast.SetLiteralNode;
import io.safelang.ast.StringInterpolationNode;
import io.safelang.ast.TraversingASTVisitor;
import io.safelang.ast.TupleLiteralNode;
import io.safelang.ast.TypeAliasNode;
import io.safelang.ast.TypeDeclarationNode;
import io.safelang.ast.TypeNode;
import io.safelang.ast.UnaryExpressionNode;
import io.safelang.ast.VariableDeclarationNode;
import io.safelang.ast.VariableReferenceNode;
import io.safelang.ast.WhileStatementNode;
import io.safelang.runtime.BuiltinRegistry;
import io.safelang.runtime.SAFEValue;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class SemanticAnalyzer implements ASTVisitor<Void> {

  private static final Set<String> NONDETERMINISTIC =
      Set.of("OS", "ARCH", "PLATFORM", "OS_VERSION");
  private final Map<String, FunctionDeclarationNode> functions = new HashMap<>();
  private final Map<String, TypeDeclarationNode> types = new HashMap<>();
  private final Map<String, EnumDeclarationNode> enums = new HashMap<>();
  private final Map<String, TypeNode> aliases = new HashMap<>();
  private final Set<String> imported = new HashSet<>();
  private final Map<String, Set<String>> selective = new HashMap<>();
  private final Set<String> fullImports = new HashSet<>();
  private final Deque<String> caseEnums = new ArrayDeque<>();
  private final Deque<TypeNode> expectedTypes = new ArrayDeque<>();
  private final Map<String, String> typeModule = new HashMap<>();
  private final Map<String, String> variantModule = new HashMap<>();
  private final Set<String> usedModules = new HashSet<>();
  private final Map<ASTNode, TypeNode> cache = new IdentityHashMap<>();
  private final ModuleRegistry registry;
  private final List<String> warnings = new ArrayList<>();
  private final TypeResolver resolver;
  private final Deque<Frame> frames = new ArrayDeque<>();
  private PurityChecker purity;
  private Set<String> external = Set.of();
  private boolean module;
  private boolean strict;

  public SemanticAnalyzer() {
    this(new ModuleRegistry());
  }

  public SemanticAnalyzer(final ModuleRegistry registry) {
    this.registry = registry;
    this.resolver = new TypeResolver(functions, types, enums, aliases, registry, cache);
    frames.push(new Frame(new TypeEnvironment(), null));
    this.resolver.setScope(scope());
  }

  /**
   * Walk a program's top-level statements and const initializer expressions for nondeterministic
   * builtin calls. Used by {@link io.safelang.SafeFrontend} to enforce strict mode on imported
   * module initialization code, which is otherwise invisible to the cross-module call walker —
   * module top-level work runs at import time, not via a function call from a strict main, so a
   * strict main importing a module with {@code const int t = std:time();} would silently execute
   * the impure call.
   *
   * <p>Function bodies inside the module are deliberately NOT checked here: stdlib modules
   * legitimately export wrappers around nondeterministic builtins (e.g. {@code file:read}), and the
   * cross-module call walker in {@link SemanticCallChecker} already rejects them at the call site
   * from a strict main. Only top-level work — which executes unconditionally during import — needs
   * the extra check.
   *
   * @throws SemanticException if any top-level expression transitively touches a {@code
   *     NONDETERMINISTIC} builtin
   */
  public static void checkTopLevelPurity(
      final ProgramNode program, final String moduleName, final ModuleRegistry registry) {
    final var functions = new HashMap<String, FunctionDeclarationNode>();
    for (final var declaration : program.declarations()) {
      if (declaration instanceof FunctionDeclarationNode function) {
        functions.put(function.name(), function);
      }
    }
    final var purity = new PurityChecker(functions, registry);
    for (final var declaration : program.declarations()) {
      if (declaration instanceof VariableDeclarationNode variable && variable.hasInitializer()) {
        if (purity.isExpressionImpure(variable.initializer())) {
          throw new SemanticException(
              "Top-level const '"
                  + variable.name()
                  + "' in module '"
                  + moduleName
                  + "' calls nondeterministic builtins, not allowed in strict mode",
              variable.line(),
              variable.column());
        }
      }
    }
    for (final var statement : program.statements()) {
      if (purity.isExpressionImpure(statement)) {
        throw new SemanticException(
            "Top-level statement in module '"
                + moduleName
                + "' calls nondeterministic builtins, not allowed in strict mode",
            statement.line(),
            statement.column());
      }
    }
  }

  private TypeEnvironment scope() {
    return frames.peek().scope;
  }

  private FunctionDeclarationNode current() {
    return frames.peek().current;
  }

  private void nested() {
    final var child = frames.peek().scope.child();
    frames.push(new Frame(child, frames.peek().current));
    resolver.setScope(child);
  }

  private void unnest() {
    frames.pop();
    resolver.setScope(frames.peek().scope);
  }

  private void enter() {
    final var child = frames.peek().scope.child();
    frames.push(new Frame(child, null));
    resolver.setScope(child);
  }

  private void leave() {
    frames.pop();
    resolver.setScope(frames.peek().scope);
  }

  private SemanticImportProcessor importProcessor() {
    return new SemanticImportProcessor(
        registry,
        enums,
        types,
        aliases,
        imported,
        selective,
        fullImports,
        typeModule,
        new SemanticImportHooks() {
          @Override
          public void error(final String message, final ASTNode node) {
            SemanticAnalyzer.this.error(message, node);
          }

          @Override
          public void conflict(
              final EnumDeclarationNode declaration, final String module, final ASTNode node) {
            conflicts(declaration, module, node);
          }
        });
  }

  private SemanticAssignmentChecker assignmentChecker() {
    return new SemanticAssignmentChecker(
        resolver,
        types,
        typeModule,
        external,
        new SemanticAssignmentHooks() {
          @Override
          public TypeEnvironment scope() {
            return SemanticAnalyzer.this.scope();
          }

          @Override
          public boolean module() {
            return module;
          }

          @Override
          public void analyze(final ASTNode node) {
            node.accept(SemanticAnalyzer.this);
          }

          @Override
          public void error(final String message, final ASTNode node) {
            SemanticAnalyzer.this.error(message, node);
          }
        });
  }

  private SemanticCallChecker callChecker() {
    return new SemanticCallChecker(
        resolver,
        registry,
        functions,
        selective,
        new SemanticCallHooks() {
          @Override
          public FunctionDeclarationNode current() {
            return SemanticAnalyzer.this.current();
          }

          @Override
          public void analyze(final ASTNode node) {
            node.accept(SemanticAnalyzer.this);
          }

          @Override
          public void error(final String message, final ASTNode node) {
            SemanticAnalyzer.this.error(message, node);
          }

          @Override
          public void use(final String name) {
            usedModules.add(name);
          }

          @Override
          public TypeEnvironment scope() {
            return SemanticAnalyzer.this.scope();
          }

          @Override
          public boolean module() {
            return module;
          }

          @Override
          public boolean strict() {
            return strict;
          }

          @Override
          public TypeNode expected() {
            return expectedTypes.isEmpty() ? null : expectedTypes.peek();
          }

          @Override
          public boolean impure(final FunctionDeclarationNode node) {
            return purity.impure(node);
          }

          @Override
          public boolean impure(final FunctionDeclarationNode node, final String module) {
            return purity.impure(node, module);
          }

          @Override
          public boolean impure(final ASTNode node) {
            return purity.isExpressionImpure(node);
          }

          @Override
          public void open() {
            frames.peek().opens++;
          }

          @Override
          public void close() {
            frames.peek().closes++;
          }
        });
  }

  public void analyze(final ProgramNode program) {
    analyze(program, false, Set.of());
  }

  public void analyze(final ProgramNode program, final boolean strict) {
    analyze(program, strict, Set.of());
  }

  public void analyze(final ProgramNode program, final boolean strict, final Set<String> external) {
    this.strict = strict;
    this.external = external != null ? external : Set.of();
    // Reset mutable state so the analyzer can be reused across programs
    functions.clear();
    types.clear();
    enums.clear();
    aliases.clear();
    imported.clear();
    selective.clear();
    typeModule.clear();
    variantModule.clear();
    usedModules.clear();
    cache.clear();
    warnings.clear();
    frames.clear();
    frames.push(new Frame(new TypeEnvironment(), null));
    resolver.setScope(scope());
    module = false;
    program.accept(this);
  }

  public List<String> warnings() {
    return Collections.unmodifiableList(warnings);
  }

  @Override
  public Void visitProgram(final ProgramNode node) {
    module = "module".equals(node.header());

    // Register system globals so they are available for type checking
    final var integer = new TypeNode(0, 0, "int");
    final var text = new TypeNode(0, 0, "string");
    for (final var entry : BuiltinRegistry.variables().entrySet()) {
      final var type = entry.getValue().isString() ? text : integer;
      scope().define(entry.getKey(), type, true);
    }

    // Process imports first to register exported enums and types
    for (final var imp : node.imports()) {
      imp.accept(this);
    }

    for (final var declaration : node.declarations()) {
      switch (declaration) {
        case TypeDeclarationNode type -> {
          if (types.containsKey(type.name()) && !imported.contains(type.name())) {
            error("Duplicate type declaration: " + type.name(), type);
          }
          types.put(type.name(), type);
        }
        case EnumDeclarationNode enumeration -> {
          if (enums.containsKey(enumeration.name()) && !imported.contains(enumeration.name())) {
            error("Duplicate enum declaration: " + enumeration.name(), enumeration);
          }
          enums.put(enumeration.name(), enumeration);
        }
        case TypeAliasNode alias -> alias(alias);
        default -> {}
      }
    }

    // Check for duplicate enum variant names across locally declared enums
    // (imported enums from modules may share variant names like Ok/Err)
    // Modules are allowed to have shared variant names across different enums
    // since they are always accessed via qualified syntax (e.g., OpenResult.Ok)
    if (!module) {
      final var variants = new HashMap<String, String>();
      for (final var entry : enums.entrySet()) {
        if (imported.contains(entry.getKey())) continue;
        for (final var variant : entry.getValue().variants()) {
          final var previous = variants.put(variant.name(), entry.getKey());
          if (previous != null) {
            error(
                "Duplicate enum variant name '"
                    + variant.name()
                    + "' found in enums '"
                    + previous
                    + "' and '"
                    + entry.getKey()
                    + "'",
                entry.getValue());
          }
        }
      }
    }

    for (final var declaration : node.declarations()) {
      if (declaration instanceof FunctionDeclarationNode function) {
        if (functions.containsKey(function.name()) && !imported.contains(function.name())) {
          error("Duplicate function declaration: " + function.name(), function);
        }
        functions.put(function.name(), function);
      }
    }
    purity = new PurityChecker(functions, registry);

    for (final var declaration : node.declarations()) {
      declaration.accept(this);
    }
    for (final var statement : node.statements()) {
      statement.accept(this);
    }

    final var nodes = new ArrayList<ASTNode>();
    nodes.addAll(node.declarations());
    nodes.addAll(node.statements());
    final var checker = new TerminationChecker(functions, enums, module, nodes);
    checker.check();

    // Warn about unused imports (skip modules that provide builtins or types)
    if (!module) {
      final var builtins = Set.of("io", "std");
      for (final var imp : node.imports()) {
        final var name = imp.module();
        if (!usedModules.contains(name) && !builtins.contains(name) && !imported.contains(name)) {
          // Check if any enum/type from this module is registered locally
          var typesUsed = false;
          for (final var entry : typeModule.entrySet()) {
            if (name.equals(entry.getValue())) {
              typesUsed = true;
              break;
            }
          }
          if (!typesUsed) {
            warnings.add("Unused import: " + name);
          }
        }
      }
    }

    return null;
  }

  @Override
  public Void visitTypeAlias(final TypeAliasNode node) {
    // Already registered during declaration scanning
    return null;
  }

  private void alias(final TypeAliasNode node) {
    if (aliases.containsKey(node.name())) {
      error("Duplicate type alias: " + node.name(), node);
      return;
    }
    aliases.put(node.name(), node.target());
    // Post-registration cycle check: walk the chain from the new alias
    final var seen = new HashSet<String>();
    seen.add(node.name());
    var check = node.target();
    while (check != null && aliases.containsKey(check.name()) && !check.isParameterized()) {
      if (!seen.add(check.name())) {
        aliases.remove(node.name());
        error("Cyclic type alias: " + node.name(), node);
        return;
      }
      check = aliases.get(check.name());
    }
  }

  @Override
  public Void visitTypeDeclaration(final TypeDeclarationNode node) {
    return null;
  }

  @Override
  public Void visitFieldDeclaration(final FieldDeclarationNode node) {
    return null;
  }

  @Override
  public Void visitEnumDeclaration(final EnumDeclarationNode node) {
    return null;
  }

  @Override
  public Void visitEnumVariant(final EnumVariantNode node) {
    return null;
  }

  @Override
  public Void visitFunctionDeclaration(final FunctionDeclarationNode node) {
    enter();
    frames.peek().current = node;
    try {
      var seenDefault = false;
      for (final var parameter : node.parameters()) {
        if (parameter.hasDefault()) {
          seenDefault = true;
          parameter.initial().accept(this);
          final var resolved = resolver.resolve(parameter.initial());
          if (resolved != null && !resolver.matches(parameter.type(), resolved)) {
            error(
                "Default value type mismatch for parameter '"
                    + parameter.name()
                    + "': expected "
                    + parameter.type().fullName()
                    + " but got "
                    + resolved.fullName(),
                parameter);
          }
        } else if (seenDefault) {
          error(
              "Required parameter '"
                  + parameter.name()
                  + "' cannot follow a parameter with a default value",
              parameter);
        }
        scope().define(parameter.name(), parameter.type(), parameter.isConst());
      }

      if (node.hasRequires()) {
        node.requires().accept(this);
        final var resolved = resolver.resolve(node.requires());
        if (resolved != null && !"boolean".equals(resolved.name())) {
          error("Requires contract must be boolean, got " + resolved.fullName(), node);
        }
      }

      var returned = false;
      for (final var statement : node.body()) {
        if (returned) {
          warnings.add("Unreachable code after return in function '" + node.name() + "'");
          break;
        }
        statement.accept(this);
        if (statement instanceof ReturnNode) {
          returned = true;
        }
      }

      // Check return path completeness for non-void functions
      if (node.returns() != null && !"void".equals(node.returns().name())) {
        if (!returns(node.body())) {
          error("Function '" + node.name() + "' may not return a value on all paths", node);
        }
      }

      if (node.hasEnsures()) {
        scope().define("result", node.returns(), false);
        node.ensures().accept(this);
        final var resolved = resolver.resolve(node.ensures());
        if (resolved != null && !"boolean".equals(resolved.name())) {
          error("Ensures contract must be boolean, got " + resolved.fullName(), node);
        }
      }

      if (node.hasDecreases()) {
        node.decreases().accept(this);
        final var resolved = resolver.resolve(node.decreases());
        if (resolved != null && !"int".equals(resolved.name()) && !"uint".equals(resolved.name())) {
          error(
              "Decreases expression must be of type int or uint, got " + resolved.fullName(), node);
        }
      }

      // Warn about unused variables in this function scope
      for (final var unused : scope().unused()) {
        if (!unused.startsWith("_") && !"result".equals(unused)) {
          warnings.add("Unused variable '" + unused + "' in function '" + node.name() + "'");
        }
      }

      // Warn about potential resource leaks
      if (frames.peek().opens > frames.peek().closes) {
        warnings.add(
            "Potential resource leak in function '"
                + node.name()
                + "': "
                + frames.peek().opens
                + " open(s) but only "
                + frames.peek().closes
                + " close(s)");
      }
    } finally {
      leave();
    }
    return null;
  }

  @Override
  public Void visitParameter(final ParameterNode node) {
    return null;
  }

  @Override
  public Void visitVariableDeclaration(final VariableDeclarationNode node) {
    final var declared = node.type();

    if (node.hasInitializer()) {
      if (declared != null) {
        expectedTypes.push(declared);
        resolver.pushExpected(declared);
      }
      try {
        node.initializer().accept(this);
        final var resolved = resolver.resolve(node.initializer());
        if (resolved != null && declared != null) {
          if (!resolver.matches(declared, resolved)) {
            error(
                "Type mismatch: expected "
                    + declared.fullName()
                    + " but got "
                    + resolved.fullName(),
                node);
          }
        }
        resolver.validateStructural(declared, node.initializer());
      } finally {
        if (declared != null) {
          resolver.popExpected();
          expectedTypes.pop();
        }
      }
    }

    // Reject mutable map key types in type annotations
    if (declared != null && "map".equals(declared.name()) && !declared.parameters().isEmpty()) {
      final var key = declared.parameters().get(0);
      final var resolved = resolver.resolveAlias(key).name();
      if ("list".equals(resolved)
          || "map".equals(resolved)
          || "set".equals(resolved)
          || "tuple".equals(resolved)
          || types.containsKey(resolved)) {
        error("Mutable type '" + key.fullName() + "' cannot be used as a map key", node);
      }
    }

    // Reject mutable set element types in type annotations
    if (declared != null && "set".equals(declared.name()) && !declared.parameters().isEmpty()) {
      final var element = declared.parameters().get(0);
      final var resolved = resolver.resolveAlias(element).name();
      if ("list".equals(resolved)
          || "map".equals(resolved)
          || "set".equals(resolved)
          || "tuple".equals(resolved)
          || types.containsKey(resolved)) {
        error("Mutable type '" + element.fullName() + "' cannot be used as a set element", node);
      }
    }

    scope().define(node.name(), declared, node.isConstant());
    return null;
  }

  @Override
  public Void visitDestructure(final DestructureNode node) {
    node.initializer().accept(this);
    final var resolved = resolver.resolve(node.initializer());
    if (node.type() != null) {
      if (!"tuple".equals(node.type().name()) || !node.type().isTuple()) {
        error("Destructuring requires a tuple type", node);
      } else if (node.type().parameters().size() != node.names().size()) {
        error(
            "Destructuring arity mismatch: type has "
                + node.type().parameters().size()
                + " element(s) but got "
                + node.names().size()
                + " name(s)",
            node);
      }
    }
    if (node.type() != null && node.type().isTuple()) {
      for (int i = 0; i < node.names().size() && i < node.type().parameters().size(); i++) {
        scope().define(node.names().get(i), node.type().parameters().get(i), node.isConstant());
      }
    } else if (resolved != null && "tuple".equals(resolved.name()) && resolved.isTuple()) {
      for (int i = 0; i < node.names().size() && i < resolved.parameters().size(); i++) {
        scope().define(node.names().get(i), resolved.parameters().get(i), node.isConstant());
      }
    } else {
      warnings.add(
          "Cannot infer tuple element types for destructuring; consider adding a type annotation");
      for (final var name : node.names()) {
        scope().define(name, null, node.isConstant());
      }
    }
    return null;
  }

  @Override
  public Void visitAssignment(final AssignmentNode node) {
    assignmentChecker().check(node);
    return null;
  }

  @Override
  public Void visitIndexAssignment(final IndexAssignmentNode node) {
    assignmentChecker().check(node);
    return null;
  }

  @Override
  public Void visitForStatement(final ForStatementNode node) {
    node.iterable().accept(this);
    final var iterable = resolver.resolve(node.iterable());
    TypeNode element = null;
    if (iterable != null) {
      if ("list".equals(iterable.name())) {
        if (!iterable.parameters().isEmpty()) {
          element = iterable.parameters().get(0);
        }
      } else if ("string".equals(iterable.name())) {
        element = resolver.simple(node.line(), node.column(), "string");
      } else if ("set".equals(iterable.name())) {
        if (!iterable.parameters().isEmpty()) {
          element = iterable.parameters().get(0);
        }
      } else if ("map".equals(iterable.name())) {
        if (!iterable.parameters().isEmpty()) {
          element = iterable.parameters().get(0);
        }
      } else {
        error(
            "For loop requires a list, string, set, or map iterable, got " + iterable.fullName(),
            node);
      }
    }
    nested();
    try {
      scope().define(node.variable(), element, false);
      scope().markUsed(node.variable()); // Loop variables are always used
      for (final var statement : node.body()) {
        statement.accept(this);
      }
    } finally {
      unnest();
    }
    return null;
  }

  @Override
  public Void visitWhileStatement(final WhileStatementNode node) {
    node.condition().accept(this);
    final var condition = resolver.resolve(node.condition());
    if (condition != null && !"boolean".equals(condition.name())) {
      error("While condition must be boolean, got " + condition.fullName(), node);
    }
    node.bound().accept(this);
    final var bound = resolver.resolve(node.bound());
    if (bound != null && !"int".equals(bound.name()) && !"uint".equals(bound.name())) {
      error("While bound must be int or uint, got " + bound.fullName(), node);
    }
    // Collect variables referenced anywhere in the bound expression, then check that none of
    // them are assigned inside the loop body — the bound is evaluated once, so mutating an input
    // would silently change the termination guarantee.
    final var bounded = new HashSet<String>();
    node.bound().accept(new BoundCollector(bounded));
    final var checker = new BoundMutationChecker(bounded, node);
    for (final var statement : node.body()) {
      statement.accept(checker);
    }
    nested();
    try {
      for (final var statement : node.body()) {
        statement.accept(this);
      }
    } finally {
      unnest();
    }
    return null;
  }

  private SemanticObjectValidator objectValidator() {
    return new SemanticObjectValidator(resolver, types, typeModule, this::error);
  }

  private SemanticBinaryChecker binaryChecker() {
    return new SemanticBinaryChecker(resolver, this::error);
  }

  /**
   * Collects every unqualified, single-part variable name referenced anywhere in a {@code while}
   * bound expression. By extending {@link TraversingASTVisitor} it descends into every expression
   * form (if/case/do/lambda, list/map/set literals, interpolation) automatically — a hand-rolled
   * switch silently skipped the newer forms.
   */
  private static final class BoundCollector extends TraversingASTVisitor<Void> {
    private final Set<String> names;

    BoundCollector(final Set<String> names) {
      this.names = names;
    }

    @Override
    public Void visitVariableReference(final VariableReferenceNode node) {
      if (!node.hasPrefix() && node.parts().size() == 1) {
        names.add(node.parts().get(0));
      }
      return null;
    }
  }

  /**
   * Flags any assignment, index assignment, or destructuring inside a {@code while} body whose
   * target is a name used in the bound. Traversal of every other node (including nested loops and
   * the bodies of if/case/do expressions) comes from {@link TraversingASTVisitor}.
   */
  private final class BoundMutationChecker extends TraversingASTVisitor<Void> {
    private final Set<String> bounded;
    private final ASTNode origin;

    BoundMutationChecker(final Set<String> bounded, final ASTNode origin) {
      this.bounded = bounded;
      this.origin = origin;
    }

    private void check(final String name) {
      if (bounded.contains(name)) {
        error(
            "Cannot assign to '"
                + name
                + "' inside while loop — it is used in the bound expression",
            origin);
      }
    }

    @Override
    public Void visitAssignment(final AssignmentNode node) {
      check(node.parts().get(0));
      return super.visitAssignment(node);
    }

    @Override
    public Void visitIndexAssignment(final IndexAssignmentNode node) {
      if (node.container() instanceof VariableReferenceNode reference) {
        check(reference.parts().get(0));
      }
      return super.visitIndexAssignment(node);
    }

    @Override
    public Void visitDestructure(final DestructureNode node) {
      for (final var name : node.names()) {
        check(name);
      }
      return super.visitDestructure(node);
    }
  }

  @Override
  public Void visitReturn(final ReturnNode node) {
    if (current() == null) {
      return null;
    }
    final var expected = current().returns();
    if (node.hasExpression()) {
      node.expression().accept(this);
      if (expected != null && "void".equals(expected.name())) {
        error("Cannot return a value from void function '" + current().name() + "'", node);
        return null;
      }
      if (expected != null) {
        final var resolved = resolver.resolve(node.expression());
        if (resolved != null && !resolver.matches(expected, resolved)) {
          error(
              "Return type mismatch: expected "
                  + expected.fullName()
                  + " but got "
                  + resolved.fullName(),
              node);
        }
      }
    } else {
      if (expected != null && !"void".equals(expected.name())) {
        error(
            "Missing return value: function '"
                + current().name()
                + "' expects "
                + expected.fullName(),
            node);
      }
    }
    return null;
  }

  @Override
  public Void visitExpressionStatement(final ExpressionStatementNode node) {
    node.expression().accept(this);
    return null;
  }

  @Override
  public Void visitAssert(final AssertNode node) {
    node.condition().accept(this);
    final var resolved = resolver.resolve(node.condition());
    if (resolved != null && !"boolean".equals(resolved.name())) {
      error("Assert condition must be boolean, got " + resolved.fullName(), node);
    }
    if (node.hasMessage()) {
      node.message().accept(this);
    }
    return null;
  }

  @Override
  public Void visitFunctionCall(final FunctionCallNode node) {
    callChecker().check(node);
    return null;
  }

  @Override
  public Void visitObjectCreation(final ObjectCreationNode node) {
    for (final var field : node.fields()) {
      field.accept(this);
    }
    objectValidator().validate(node, module);
    return null;
  }

  // Field name validation, type checking, and access control are handled by visitObjectCreation,
  // which iterates all fields with full struct type context. FieldAssignmentNode only appears
  // inside ObjectCreationNode per the grammar, so this visitor only needs to analyze the value
  // sub-expression for scope and type resolution within the value itself.
  @Override
  public Void visitFieldAssignment(final FieldAssignmentNode node) {
    node.value().accept(this);
    return null;
  }

  @Override
  public Void visitBinaryExpression(final BinaryExpressionNode node) {
    node.left().accept(this);
    node.right().accept(this);

    final var operator = node.operator();
    final var left = resolver.resolve(node.left());
    final var right = resolver.resolve(node.right());

    if (left != null && right != null) {
      final boolean leftInferred = "?".equals(left.name()) || left.isVariable();
      final boolean rightInferred = "?".equals(right.name()) || right.isVariable();
      binaryChecker().check(node, operator, left, right, leftInferred, rightInferred);
    }

    return null;
  }

  @Override
  public Void visitUnaryExpression(final UnaryExpressionNode node) {
    node.operand().accept(this);
    if ("~".equals(node.operator())) {
      final var resolved = resolver.resolve(node.operand());
      if (resolved != null && !resolver.isIntegral(resolved)) {
        error("Operator '~' requires int or uint operand, got " + resolved.fullName(), node);
      }
    }
    return null;
  }

  @Override
  public Void visitIfExpression(final IfExpressionNode node) {
    node.condition().accept(this);
    final var condition = resolver.resolve(node.condition());
    if (condition != null && !"boolean".equals(condition.name())) {
      error("If condition must be boolean, got " + condition.fullName(), node);
    }
    node.then().accept(this);
    if (node.hasOtherwise()) {
      node.otherwise().accept(this);
    }
    return null;
  }

  @Override
  public Void visitCaseExpression(final CaseExpressionNode node) {
    node.subject().accept(this);
    final var subjectType = resolver.resolve(node.subject());
    final var enumName =
        subjectType != null && enums.containsKey(subjectType.name()) ? subjectType.name() : null;
    caseEnums.push(enumName == null ? "" : enumName);
    try {
      for (final var branch : node.branches()) {
        branch.accept(this);
      }
    } finally {
      caseEnums.pop();
    }
    if (node.hasFallback()) {
      node.fallback().accept(this);
    }

    // Exhaustiveness checking for enum subjects
    final var subject = resolver.resolve(node.subject());
    if (subject != null && enums.containsKey(subject.name())) {
      final var declaration = enums.get(subject.name());
      final var all = new HashSet<String>();
      for (final var variant : declaration.variants()) {
        all.add(variant.name());
      }
      final var covered = new HashSet<String>();
      var wildcard = node.hasFallback();
      for (final var branch : node.branches()) {
        if (branch.isWildcard()) {
          wildcard = true;
        } else if (!branch.hasGuard() && branch.pattern() instanceof EnumPatternNode pattern) {
          covered.add(pattern.variant());
        }
      }
      if (!wildcard) {
        final var missing = new HashSet<>(all);
        missing.removeAll(covered);
        if (!missing.isEmpty()) {
          warnings.add(
              "Non-exhaustive case expression at line "
                  + node.line()
                  + ": missing variant(s) "
                  + missing);
        }
      }
    }

    return null;
  }

  @Override
  public Void visitCaseBranch(final CaseBranchNode node) {
    if (node.pattern() instanceof EnumPatternNode pattern) {
      final var caseEnum = caseEnums.isEmpty() ? "" : caseEnums.peek();
      final var variant =
          caseEnum.isEmpty()
              ? resolver.findVariant(pattern.variant())
              : resolver.findVariant(caseEnum, pattern.variant());
      if (variant == null) {
        error("Unknown enum variant: " + pattern.variant(), node);
      } else if (pattern.hasBindings() && variant.fields().size() != pattern.bindings().size()) {
        error(
            "Enum variant '"
                + pattern.variant()
                + "' expects "
                + variant.fields().size()
                + " binding(s) but got "
                + pattern.bindings().size(),
            node);
      }
      nested();
      try {
        for (int i = 0; i < pattern.bindings().size(); i++) {
          final var binding = pattern.bindings().get(i);
          final var type = i < variant.fields().size() ? variant.fields().get(i) : null;
          scope().define(binding, type, false);
        }
        if (node.hasGuard()) {
          node.guard().accept(this);
          final var resolved = resolver.resolve(node.guard());
          if (resolved != null && !"boolean".equals(resolved.name())) {
            error("Guard condition must be boolean, got " + resolved.fullName(), node);
          }
        }
        node.result().accept(this);
      } finally {
        unnest();
      }
    } else {
      if (node.hasGuard()) {
        node.guard().accept(this);
        final var resolved = resolver.resolve(node.guard());
        if (resolved != null && !"boolean".equals(resolved.name())) {
          error("Guard condition must be boolean, got " + resolved.fullName(), node);
        }
      }
      node.result().accept(this);
    }
    return null;
  }

  /**
   * True when {@code module} declares a constant named {@code member} that is not {@code public}.
   * Qualified access to such a constant is rejected — the registry's export maps already hide it
   * from cross-module function/type/enum resolution, so this closes the same gap for constants.
   */
  private boolean privateConstant(final String module, final String member) {
    if (member == null || registry == null || !registry.has(module)) {
      return false;
    }
    final var program = registry.program(module);
    if (program == null) {
      return false;
    }
    for (final var declaration : program.declarations()) {
      if (declaration instanceof VariableDeclarationNode constant
          && constant.isConstant()
          && constant.name().equals(member)) {
        return !constant.isPublic();
      }
    }
    return false;
  }

  @Override
  public Void visitVariableReference(final VariableReferenceNode node) {
    if (node.hasPrefix()) {
      usedModules.add(node.prefix());
      if (!registry.has(node.prefix())) {
        error("Unknown module: " + node.prefix(), node);
      } else {
        final var allowed = selective.get(node.prefix());
        final var target = node.parts().isEmpty() ? null : node.parts().get(0);
        if (allowed != null && target != null && !allowed.contains(target)) {
          error(
              "Variable '"
                  + target
                  + "' was not included in selective import of module '"
                  + node.prefix()
                  + "'",
              node);
        }
        if (privateConstant(node.prefix(), target)) {
          error(
              "Cannot access private constant '" + target + "' of module '" + node.prefix() + "'",
              node);
        }
      }
      return null;
    }
    final var parts = node.parts();
    if (parts.isEmpty()) {
      return null;
    }
    final var name = parts.get(0);

    // Mark variable as used for unused-variable tracking
    scope().markUsed(name);

    // Block non-deterministic globals in strict mode
    if (strict && NONDETERMINISTIC.contains(name)) {
      error("Non-deterministic variable '" + name + "' not allowed in strict mode", node);
      return null;
    }

    if (!scope().has(name)
        && !functions.containsKey(name)
        && !types.containsKey(name)
        && resolver.findVariant(name) == null
        && !resolver.isEnumName(name)
        && !registry.has(name)
        && !external.contains(name)) {
      error("Undefined variable: " + name, node);
      return null;
    }
    // Check selective imports for module variable access (e.g., math.PI)
    if (registry.has(name)) {
      usedModules.add(name);
    }
    if (parts.size() > 1 && registry.has(name)) {
      final var allowed = selective.get(name);
      final var target = parts.get(1);
      if (allowed != null && !allowed.contains(target)) {
        error(
            "Variable '"
                + target
                + "' was not included in selective import of module '"
                + name
                + "'",
            node);
      }
      if (privateConstant(name, target)) {
        error("Cannot access private constant '" + target + "' of module '" + name + "'", node);
      }
    }
    // Validate field chain for multi-part references (e.g., p.x.y)
    if (parts.size() > 1 && scope().has(name) && !registry.has(name)) {
      final var root = scope().variable(name);
      if (root != null) {
        var type = types.get(root.name());
        for (int i = 1; i < parts.size(); i++) {
          if (type == null) break;
          final var field = resolver.findField(type, parts.get(i));
          if (field == null) {
            error("Unknown field '" + parts.get(i) + "' in type '" + type.name() + "'", node);
            break;
          }
          if (!field.isPublic() && !module && typeModule.containsKey(type.name())) {
            error(
                "Cannot access private field '" + parts.get(i) + "' of type '" + type.name() + "'",
                node);
            break;
          }
          if (i < parts.size() - 1) {
            type = types.get(field.type().name());
          }
        }
      }
    }
    return null;
  }

  @Override
  public Void visitLiteral(final LiteralNode node) {
    return null;
  }

  @Override
  public Void visitListLiteral(final ListLiteralNode node) {
    for (final var element : node.elements()) {
      element.accept(this);
    }
    return null;
  }

  @Override
  public Void visitMapLiteral(final MapLiteralNode node) {
    for (final var entry : node.entries()) {
      entry.accept(this);
    }
    // Reject mutable key types (list, map, set, tuple) — they corrupt HashMap internals
    if (!node.entries().isEmpty()) {
      final var resolved = resolver.resolve(node.entries().get(0).key());
      if (resolved != null) {
        final var name = resolver.resolveAlias(resolved).name();
        if ("list".equals(name)
            || "map".equals(name)
            || "set".equals(name)
            || "tuple".equals(name)
            || types.containsKey(name)) {
          error("Mutable type '" + resolved.fullName() + "' cannot be used as a map key", node);
        }
      }
    }
    return null;
  }

  @Override
  public Void visitMapEntry(final MapEntryNode node) {
    node.key().accept(this);
    node.value().accept(this);
    return null;
  }

  @Override
  public Void visitIndexAccess(final IndexAccessNode node) {
    node.container().accept(this);
    node.index().accept(this);
    final var container = resolver.resolve(node.container());
    if (container != null) {
      final var name = container.fullName();
      if (name.startsWith("list<") || "string".equals(name) || name.startsWith("tuple<")) {
        final var index = resolver.resolve(node.index());
        if (index != null && !"int".equals(index.name()) && !"uint".equals(index.name())) {
          error("Index must be int or uint, got " + index.fullName(), node);
        }
        if (name.startsWith("tuple<") && !(node.index() instanceof io.safelang.ast.LiteralNode)) {
          error("Tuple index must be a literal integer", node);
        }
      } else if (name.startsWith("map<") && !container.parameters().isEmpty()) {
        final var expected = container.parameters().get(0);
        final var actual = resolver.resolve(node.index());
        if (actual != null && !resolver.matches(expected, actual)) {
          error(
              "Map key type mismatch: expected "
                  + expected.fullName()
                  + " but got "
                  + actual.fullName(),
              node);
        }
      } else if (!name.startsWith("map<")) {
        error("Cannot index type " + name, node);
      }
    }
    return null;
  }

  @Override
  public Void visitStringInterpolation(final StringInterpolationNode node) {
    for (final var part : node.parts()) {
      part.accept(this);
    }
    return null;
  }

  @Override
  public Void visitImport(final ImportNode node) {
    importProcessor().process(node);
    return null;
  }

  private void conflicts(
      final EnumDeclarationNode declaration, final String module, final ASTNode node) {
    for (final var variant : declaration.variants()) {
      final var name = variant.name();
      final var existing = variantModule.get(name);
      if (existing != null && !existing.equals(module)) {
        warnings.add(
            "Enum variant '"
                + name
                + "' from module '"
                + module
                + "' conflicts with variant from module '"
                + existing
                + "' at line "
                + node.line());
      }
      variantModule.put(name, module);
    }
  }

  @Override
  public Void visitTupleLiteral(final TupleLiteralNode node) {
    if (node.elements().size() > SAFEValue.MAX_TUPLE_SIZE) {
      throw new SemanticException(
          "Tuple size "
              + node.elements().size()
              + " exceeds maximum of "
              + SAFEValue.MAX_TUPLE_SIZE,
          node.line(),
          node.column());
    }
    for (final var element : node.elements()) {
      element.accept(this);
    }
    return null;
  }

  @Override
  public Void visitSetLiteral(final SetLiteralNode node) {
    for (final var element : node.elements()) {
      element.accept(this);
    }
    // Reject mutable element types — they corrupt HashSet internals after mutation
    if (!node.elements().isEmpty()) {
      final var resolved = resolver.resolve(node.elements().get(0));
      if (resolved != null) {
        final var name = resolver.resolveAlias(resolved).name();
        if ("list".equals(name)
            || "map".equals(name)
            || "set".equals(name)
            || "tuple".equals(name)
            || types.containsKey(name)) {
          error("Mutable type '" + resolved.fullName() + "' cannot be used as a set element", node);
        }
      }
    }
    return null;
  }

  @Override
  public Void visitLambda(final LambdaNode node) {
    nested();
    try {
      for (final var parameter : node.parameters()) {
        if (parameter.type() != null) {
          scope().define(parameter.name(), parameter.type(), false);
        } else {
          // Untyped param — define with a placeholder so it's in scope
          scope()
              .define(
                  parameter.name(),
                  resolver.simple(parameter.line(), parameter.column(), "?"),
                  false);
        }
      }
      node.body().accept(this);
    } finally {
      unnest();
    }
    return null;
  }

  @Override
  public Void visitDoExpression(final DoExpressionNode node) {
    nested();
    try {
      for (final var statement : node.statements()) {
        statement.accept(this);
      }
      node.expression().accept(this);
      cache.put(node, resolver.resolve(node.expression()));
    } finally {
      unnest();
    }
    return null;
  }

  @Override
  public Void visitRange(final RangeNode node) {
    node.start().accept(this);
    node.end().accept(this);
    final var start = resolver.resolve(node.start());
    final var end = resolver.resolve(node.end());
    if (start != null && !"int".equals(start.name()) && !"uint".equals(start.name())) {
      error("Range start must be int or uint, got " + start.fullName(), node);
    }
    if (end != null && !"int".equals(end.name()) && !"uint".equals(end.name())) {
      error("Range end must be int or uint, got " + end.fullName(), node);
    }
    if (node.hasStep()) {
      node.step().accept(this);
      final var step = resolver.resolve(node.step());
      if (step != null && !"int".equals(step.name())) {
        error("Range step must be int, got " + step.fullName(), node);
      }
      if (node.step() instanceof LiteralNode.IntLiteral lit && lit.value() == 0) {
        error("Range step cannot be zero", node);
      }
    }
    return null;
  }

  @Override
  public Void visitType(final TypeNode node) {
    return null;
  }

  @Override
  public Void visitEnumPattern(final EnumPatternNode node) {
    return null;
  }

  @Override
  public Void visitFieldAccess(final FieldAccessNode node) {
    node.receiver().accept(this);
    final var receiver = resolver.resolve(node.receiver());
    if (receiver != null) {
      final var declaration = types.get(receiver.name());
      if (declaration != null) {
        final var field = resolver.findField(declaration, node.field());
        if (field == null) {
          error("Unknown field '" + node.field() + "' in type '" + receiver.name() + "'", node);
        } else if (!field.isPublic() && !module && typeModule.containsKey(receiver.name())) {
          error(
              "Cannot access private field '"
                  + node.field()
                  + "' of type '"
                  + receiver.name()
                  + "'",
              node);
        }
      }
    }
    return null;
  }

  private boolean returns(final List<ASTNode> body) {
    if (body.isEmpty()) return false;
    for (final var statement : body) {
      if (returns(statement)) return true;
    }
    return false;
  }

  private boolean returns(final ASTNode node) {
    if (node instanceof ReturnNode) return true;
    if (node instanceof ExpressionStatementNode expression) {
      return returns(expression.expression());
    }
    if (node instanceof IfExpressionNode conditional) {
      if (!conditional.hasOtherwise()) return false;
      return returns(conditional.then()) && returns(conditional.otherwise());
    }
    if (node instanceof CaseExpressionNode match) {
      if (!match.hasFallback()) return false;
      for (final var branch : match.branches()) {
        if (!returns(branch.result())) return false;
      }
      return returns(match.fallback());
    }
    return false;
  }

  private void error(final String message, final ASTNode node) {
    throw new SemanticException(message, node.line(), node.column());
  }

  private static final class Frame {
    final TypeEnvironment scope;
    FunctionDeclarationNode current;
    int opens;
    int closes;

    Frame(final TypeEnvironment scope, final FunctionDeclarationNode current) {
      this.scope = scope;
      this.current = current;
      this.opens = 0;
      this.closes = 0;
    }
  }
}
