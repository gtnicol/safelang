package io.safelang.interpreter;

import io.safelang.ModuleRegistry;
import io.safelang.ast.*;
import io.safelang.interpreter.builtins.*;
import io.safelang.runtime.*;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Interpreter for the SAFE programming language. Implements the ASTVisitor interface to walk and
 * evaluate the AST.
 */
public class Interpreter implements ASTVisitor<SAFEValue> {

  private final Map<String, Environment> modules = new HashMap<>();
  private final Map<String, Set<String>> selections = new HashMap<>();
  private final Map<Integer, FileHandle> handles = new HashMap<>();
  private final Map<Integer, BinaryFileHandle> binaries = new HashMap<>();
  private final Map<String, Deque<Long>> measures = new HashMap<>();
  private final AtomicInteger counter = new AtomicInteger(0);
  private final Random[] random = {new Random()};
  private final Scanner scanner = new Scanner(System.in, StandardCharsets.UTF_8);
  private final BuiltinExecutors executors = new BuiltinExecutors();
  private final List<String> arguments;
  private final Environment root;
  private final Deque<Frame> frames = new ArrayDeque<>();
  private ModuleRegistry registry;
  private Writer output;

  public Interpreter() {
    this(List.of());
  }

  public Interpreter(final List<String> arguments) {
    this.root = new Environment();
    this.arguments = arguments != null ? arguments : List.of();
    frames.push(new Frame(root, root, 0));
    builtins();
    BuiltinRegistry.variables().forEach(root::define);
  }

  private Environment environment() {
    return frames.peek().environment;
  }

  private Environment scope() {
    return frames.peek().global;
  }

  private int depth() {
    return frames.peek().depth;
  }

  private void enter(final Environment scope) {
    frames.push(new Frame(scope, scope(), depth()));
  }

  private void module(final Environment scope) {
    frames.push(new Frame(scope, scope, depth() + 1));
  }

  private void exit() {
    frames.pop();
  }

  public void setRegistry(final ModuleRegistry registry) {
    this.registry = registry;
  }

  public void bind(final String name, final SAFEValue value) {
    root.define(name, value);
  }

  public Environment global() {
    return root;
  }

  public void setOutput(final Writer writer) {
    this.output = writer;
  }

  public void register(final String name, final BuiltinFunction function) {
    executors.register(name, function);
  }

  private void reject(final String name, final List<SAFEValue> arguments) {
    for (final var argument : arguments) {
      if (argument.isFloat() && Double.isNaN(argument.asFloat())) {
        throw new InterpreterException(
            "NaN is not allowed as an argument to function '" + name + "'");
      }
    }
  }

  /**
   * Invoke a previously-resolved function declaration with parameters already bound into {@code
   * scope}. This is the canonical "execute a function body with full contract enforcement" entry
   * point shared by every call path: direct calls ({@link #visitFunctionCall}), first-class
   * function / closure invocation ({@link #closure}), and module-prefixed calls ({@link
   * #dispatch}).
   *
   * <p>The contract logic (NaN reject, {@code requires}, {@code decreases} push, body, {@code
   * ensures}, {@code decreases} pop) lives here in one place — every backend, every call shape,
   * every contract semantics change must land here, not in three independent copies.
   */
  private SAFEValue invoke(
      final FunctionDeclarationNode declaration,
      final Environment scope,
      final List<SAFEValue> args,
      final String displayName) {
    reject(displayName, args);

    // Evaluate requires contract if present.
    if (declaration.hasRequires()) {
      enter(scope);
      try {
        final var check = declaration.requires().accept(this);
        if (!check.asBoolean()) {
          throw new InterpreterException("Requires contract failed for function: " + displayName);
        }
      } finally {
        exit();
      }
    }

    // Evaluate decreases clause if present.
    if (declaration.hasDecreases()) {
      enter(scope);
      try {
        final var measure = declaration.decreases().accept(this).asInt();
        final var stack = measures.computeIfAbsent(displayName, k -> new ArrayDeque<>());
        if (measure < 0) {
          throw new InterpreterException(
              "Decreases measure must be non-negative for: " + displayName);
        }
        if (!stack.isEmpty() && measure >= stack.peek()) {
          throw new InterpreterException(
              "Decreases clause not satisfied for: "
                  + displayName
                  + " (measure "
                  + measure
                  + " >= previous "
                  + stack.peek()
                  + ")");
        }
        stack.push(measure);
      } finally {
        exit();
      }
    }

    // Execute the body, then evaluate ensures. The decreases stack is always
    // popped, even if the body or ensures throws.
    SAFEValue result = SAFEValue.ofVoid();
    try {
      enter(scope);
      try {
        for (final var statement : declaration.body()) {
          statement.accept(this);
        }
      } catch (final ReturnException ret) {
        result = ret.value();
      } finally {
        exit();
      }

      if (declaration.hasEnsures()) {
        final var contract = scope.child();
        contract.define("result", result);
        enter(contract);
        try {
          final var check = declaration.ensures().accept(this);
          if (!check.asBoolean()) {
            throw new InterpreterException("Ensures contract failed for function: " + displayName);
          }
        } finally {
          exit();
        }
      }
    } finally {
      if (declaration.hasDecreases()) {
        measures.get(displayName).pop();
      }
    }

    return result;
  }

  private void builtins() {
    BuiltinRegistration.registerAll(
        executors, () -> output, scanner, random, handles, binaries, counter, arguments);
  }

  /** Interprets a program AST. */
  public SAFEValue interpret(final ProgramNode program) {
    try {
      return program.accept(this);
    } finally {
      cleanup();
    }
  }

  private void cleanup() {
    BuiltinRegistration.closeHandles(handles, binaries);
  }

  @Override
  public SAFEValue visitProgram(final ProgramNode node) {
    // Process imports first
    for (final var imported : node.imports()) {
      imported.accept(this);
    }

    // Register all type declarations first
    for (final ASTNode declaration : node.declarations()) {
      if (declaration instanceof TypeDeclarationNode type) {
        scope().define(type.name(), type);
      }
    }

    // Register all enum declarations
    for (final ASTNode declaration : node.declarations()) {
      if (declaration instanceof EnumDeclarationNode) {
        visitEnumDeclaration((EnumDeclarationNode) declaration);
      }
    }

    // Register all function declarations
    for (final ASTNode declaration : node.declarations()) {
      if (declaration instanceof FunctionDeclarationNode function) {
        scope().define(function.name(), function);
      }
    }

    // Execute const declarations from the declarations block
    for (final ASTNode declaration : node.declarations()) {
      if (declaration instanceof VariableDeclarationNode) {
        declaration.accept(this);
      }
    }

    // Execute all statements
    SAFEValue result = SAFEValue.ofVoid();
    for (final ASTNode statement : node.statements()) {
      result = statement.accept(this);
    }

    return result;
  }

  @Override
  public SAFEValue visitImport(final ImportNode node) {
    if (registry == null || !registry.has(node.module())) {
      return SAFEValue.ofVoid();
    }

    final var fresh = !modules.containsKey(node.module());

    if (fresh) {
      final var program = registry.program(node.module());
      final var scope = new Environment();

      // Seed module environment with system globals (VERSION, OS, ARCH, etc.)
      BuiltinRegistry.variables().forEach(scope::define);

      // Save and swap environment
      module(scope);
      try {
        // Process this module's own imports first (transitive dependencies)
        for (final var imported : program.imports()) {
          visitImport(imported);
        }

        // Register types, enums, functions in module environment
        for (final var declaration : program.declarations()) {
          switch (declaration) {
            case TypeDeclarationNode type -> scope.define(type.name(), type);
            case EnumDeclarationNode enumeration -> visitEnumDeclaration(enumeration);
            case FunctionDeclarationNode function -> scope.define(function.name(), function);
            default -> {}
          }
        }

        // Execute const declarations from declarations block
        for (final var declaration : program.declarations()) {
          if (declaration instanceof VariableDeclarationNode) {
            declaration.accept(this);
          }
        }

        // Execute top-level statements (for const declarations etc.)
        for (final var statement : program.statements()) {
          statement.accept(this);
        }
      } finally {
        exit();
      }

      modules.put(node.module(), scope);
    }

    // Register public enums and types in the importing program's global scope so variant
    // constructors and type names are available. Runs on both first-load and re-import so that
    // additional selective imports of the same module expose newly selected symbols.
    final var selective = node.isSelective();
    final var symbols = node.symbols();

    for (final var entry : registry.enums(node.module()).entrySet()) {
      final var enumeration = entry.getValue();
      if (!enumeration.isPublic()) continue;
      if (selective && !symbols.contains(enumeration.name())) {
        continue;
      }
      environment().define(enumeration.name(), enumeration);
      // Register unit variants as variables in importing scope
      for (final var variant : enumeration.variants()) {
        if (!variant.hasFields()) {
          final var value = SAFEValue.ofEnum(enumeration.name(), variant.name(), new ArrayList<>());
          environment().define(variant.name(), value);
        }
      }
    }
    for (final var entry : registry.types(node.module()).entrySet()) {
      if (selective && !symbols.contains(entry.getKey())) {
        continue;
      }
      environment().define(entry.getKey(), entry.getValue());
    }

    // Update selection filter: non-selective imports grant full access (remove filter);
    // selective imports compose additively unless a prior non-selective import already granted
    // full access (null entry with modules.contains → prior full).
    if (!selective) {
      selections.remove(node.module());
    } else {
      final var full = !fresh && !selections.containsKey(node.module());
      if (!full) {
        selections.computeIfAbsent(node.module(), k -> new HashSet<>()).addAll(symbols);
      }
    }

    return SAFEValue.ofVoid();
  }

  @Override
  public SAFEValue visitType(final TypeNode node) {
    // TypeNode is metadata, not evaluated directly
    return SAFEValue.ofVoid();
  }

  @Override
  public SAFEValue visitTupleLiteral(final TupleLiteralNode node) {
    if (node.elements().size() > SAFEValue.MAX_TUPLE_SIZE) {
      throw new InterpreterException(
          "Tuple size "
              + node.elements().size()
              + " exceeds maximum of "
              + SAFEValue.MAX_TUPLE_SIZE
              + " at line "
              + node.line());
    }
    final var elements = new ArrayList<SAFEValue>();
    for (final var element : node.elements()) {
      elements.add(element.accept(this));
    }
    return SAFEValue.ofTuple(elements);
  }

  @Override
  public SAFEValue visitSetLiteral(final SetLiteralNode node) {
    final var elements = new LinkedHashSet<SAFEValue>();
    for (final var element : node.elements()) {
      elements.add(element.accept(this));
    }
    return SAFEValue.ofSet(elements);
  }

  @Override
  public SAFEValue visitLambda(final LambdaNode node) {
    return SAFEValue.ofFunction(Closure.lambda(node, environment().snapshot()));
  }

  @Override
  public SAFEValue visitDoExpression(final DoExpressionNode node) {
    enter(environment().child());
    try {
      for (final var statement : node.statements()) {
        statement.accept(this);
      }
      return node.expression().accept(this);
    } finally {
      exit();
    }
  }

  @Override
  public SAFEValue visitRange(final RangeNode node) {
    final var start = node.start().accept(this).asInt();
    final var end = node.end().accept(this).asInt();
    final var increment = node.hasStep() ? node.step().accept(this).asInt() : 1L;
    if (increment == 0) {
      throw new InterpreterException("Range step cannot be zero");
    }
    // Detect empty range (opposite directions)
    if ((increment > 0 && start > end) || (increment < 0 && start < end)) {
      return SAFEValue.ofList(new ArrayList<>());
    }
    // Overflow-safe size calculation
    final var range = end / increment - start / increment;
    final var size = Math.abs(range) + 1;
    if (size > SAFEValue.MAX_LIST_SIZE || size < 0) {
      throw new InterpreterException("range size exceeds maximum of " + SAFEValue.MAX_LIST_SIZE);
    }
    final var list = new ArrayList<SAFEValue>();
    if (increment > 0) {
      for (long i = start; i <= end; i += increment) {
        list.add(SAFEValue.ofInt(i));
        if (i > 0 && end - i < increment) break; // would overflow
      }
    } else {
      for (long i = start; i >= end; i += increment) {
        list.add(SAFEValue.ofInt(i));
        if (i < 0 && end - i > increment) break; // would overflow
      }
    }
    return SAFEValue.ofList(list);
  }

  @Override
  public SAFEValue visitTypeAlias(final TypeAliasNode node) {
    // Type aliases are resolved at compile time — no runtime action needed
    return SAFEValue.ofVoid();
  }

  @Override
  public SAFEValue visitTypeDeclaration(final TypeDeclarationNode node) {
    // Type declarations are registered in visitProgram
    return SAFEValue.ofVoid();
  }

  @Override
  public SAFEValue visitFieldDeclaration(final FieldDeclarationNode node) {
    // Field declarations are metadata
    return SAFEValue.ofVoid();
  }

  @Override
  public SAFEValue visitFunctionDeclaration(final FunctionDeclarationNode node) {
    // Function declarations are registered in visitProgram
    return SAFEValue.ofVoid();
  }

  @Override
  public SAFEValue visitParameter(final ParameterNode node) {
    // Parameters are not evaluated directly
    return SAFEValue.ofVoid();
  }

  @Override
  public SAFEValue visitVariableDeclaration(final VariableDeclarationNode node) {
    SAFEValue value = SAFEValue.ofVoid();
    if (node.hasInitializer()) {
      value = node.initializer().accept(this);
    }
    if (node.isConstant()) {
      environment().constant(node.name(), value);
    } else {
      environment().define(node.name(), value);
    }
    return value;
  }

  @Override
  public SAFEValue visitDestructure(final DestructureNode node) {
    final var tuple = node.initializer().accept(this);
    if (!tuple.isTuple()) {
      throw new InterpreterException("Destructuring requires a tuple value, got " + tuple.type());
    }
    final var elements = tuple.asTuple();
    final var names = node.names();
    if (elements.size() != names.size()) {
      throw new InterpreterException(
          "Tuple has " + elements.size() + " elements but destructuring expects " + names.size());
    }
    for (int i = 0; i < names.size(); i++) {
      if (node.isConstant()) {
        environment().constant(names.get(i), elements.get(i));
      } else {
        environment().define(names.get(i), elements.get(i));
      }
    }
    return tuple;
  }

  @Override
  public SAFEValue visitAssignment(final AssignmentNode node) {
    final var value = node.value().accept(this);
    final var parts = node.parts();

    if (parts.size() == 1) {
      // Simple variable assignment
      final var name = parts.getFirst();
      if (environment().isConst(name)) {
        throw new InterpreterException("Cannot assign to const variable: " + name);
      }
      environment().set(name, value);
    } else {
      // Dotted field assignment: obj.field = value
      final var name = parts.getFirst();
      if (environment().isConst(name)) {
        throw new InterpreterException("Cannot assign to field of const variable: " + name);
      }
      SAFEValue obj = environment().get(name);

      for (int i = 1; i < parts.size() - 1; i++) {
        final var field = parts.get(i);
        final var fields = obj.fields();
        obj = fields.get(field);
        if (obj == null) {
          throw new InterpreterException("Field not found: " + field);
        }
      }

      // Set the final field
      final var target = parts.getLast();
      obj.setField(target, value);
    }

    return value;
  }

  @Override
  public SAFEValue visitForStatement(final ForStatementNode node) {
    final var iterable = node.iterable().accept(this);

    final List<SAFEValue> list =
        switch (iterable) {
          case SAFEValue.ListValue lv -> lv.asList();
          case SAFEValue.StringValue(String chars) -> {
            final var result = new ArrayList<SAFEValue>(chars.length());
            for (int i = 0; i < chars.length(); i++) {
              result.add(SAFEValue.ofString(String.valueOf(chars.charAt(i))));
            }
            yield result;
          }
          case SAFEValue.SetValue sv -> new ArrayList<>(sv.asSet());
          case SAFEValue.MapValue mv -> new ArrayList<>(mv.asMap().keySet());
          default ->
              throw new InterpreterException(
                  "For loop iterable must be a list, string, set, or map");
        };
    SAFEValue result = SAFEValue.ofVoid();

    final var scope = environment().child();
    enter(scope);

    try {
      for (final SAFEValue item : list) {
        environment().define(node.variable(), item);
        for (final ASTNode statement : node.body()) {
          result = statement.accept(this);
        }
      }
    } finally {
      exit();
    }

    return result;
  }

  @Override
  public SAFEValue visitWhileStatement(final WhileStatementNode node) {
    final var max = node.bound().accept(this).asInt();
    if (max < 0) {
      throw new InterpreterException("While loop bound must be non-negative, got " + max);
    }
    final var scope = environment().child();
    enter(scope);
    try {
      for (long i = 0; i < max; i++) {
        if (!node.condition().accept(this).asBoolean()) break;
        for (final var statement : node.body()) {
          statement.accept(this);
        }
      }
    } finally {
      exit();
    }
    return SAFEValue.ofVoid();
  }

  @Override
  public SAFEValue visitReturn(final ReturnNode node) {
    SAFEValue value = SAFEValue.ofVoid();
    if (node.hasExpression()) {
      value = node.expression().accept(this);
    }
    throw new ReturnException(value);
  }

  @Override
  public SAFEValue visitExpressionStatement(final ExpressionStatementNode node) {
    return node.expression().accept(this);
  }

  @Override
  public SAFEValue visitBinaryExpression(final BinaryExpressionNode node) {
    final var op = node.operator();

    // Short-circuit evaluation for logical operators — kept here because
    // BinaryDispatcher receives already-evaluated operands.
    if ("||".equals(op)) {
      final var left = node.left().accept(this);
      if (left.asBoolean()) return SAFEValue.ofBoolean(true);
      return SAFEValue.ofBoolean(node.right().accept(this).asBoolean());
    }
    if ("&&".equals(op)) {
      final var left = node.left().accept(this);
      if (!left.asBoolean()) return SAFEValue.ofBoolean(false);
      return SAFEValue.ofBoolean(node.right().accept(this).asBoolean());
    }

    final var left = node.left().accept(this);
    final var right = node.right().accept(this);
    return BinaryDispatcher.dispatch(op, left, right);
  }

  @Override
  public SAFEValue visitUnaryExpression(final UnaryExpressionNode node) {
    final var operand = node.operand().accept(this);
    final var op = node.operator();

    switch (op) {
      case "!":
        return SAFEValue.ofBoolean(!operand.asBoolean());
      case "-":
        try {
          return SAFEValue.negate(operand);
        } catch (RuntimeException e) {
          throw new InterpreterException(e.getMessage());
        }
      case "~":
        try {
          return SAFEValue.bitwiseNot(operand);
        } catch (RuntimeException e) {
          throw new InterpreterException(e.getMessage());
        }
      default:
        throw new InterpreterException("Unknown unary operator: " + op);
    }
  }

  @Override
  public SAFEValue visitIfExpression(final IfExpressionNode node) {
    final var condition = node.condition().accept(this);
    if (condition.asBoolean()) {
      return node.then().accept(this);
    } else if (node.hasOtherwise()) {
      return node.otherwise().accept(this);
    } else {
      return SAFEValue.ofVoid();
    }
  }

  @Override
  public SAFEValue visitCaseExpression(final CaseExpressionNode node) {
    final var subject = node.subject().accept(this);

    for (final CaseBranchNode branch : node.branches()) {
      // Check for wildcard pattern
      if (branch.isWildcard()) {
        if (branch.hasGuard()) {
          final var guard = branch.guard().accept(this);
          if (!guard.asBoolean()) {
            continue;
          }
        }
        return branch.result().accept(this);
      }

      final var pattern = branch.pattern();

      // Handle LiteralNode pattern (original behavior)
      if (pattern instanceof LiteralNode literal) {
        final var matched = literal.accept(this);
        if (subject.equals(matched)) {
          if (branch.hasGuard()) {
            final var guard = branch.guard().accept(this);
            if (!guard.asBoolean()) {
              continue;
            }
          }
          return branch.result().accept(this);
        }
      }
      // Handle EnumPatternNode pattern
      else if (pattern instanceof EnumPatternNode enumPattern) {
        if (subject.isEnum() && subject.variant().equals(enumPattern.variant())) {
          // Create child scope and bind captured variables
          final var scope = environment().child();
          enter(scope);

          try {
            // Bind captured variables to enum data
            if (enumPattern.hasBindings()) {
              final var bindings = enumPattern.bindings();
              final var data = subject.data();
              if (bindings.size() != data.size()) {
                throw new InterpreterException(
                    "Pattern binding count mismatch: "
                        + enumPattern.variant()
                        + " has "
                        + data.size()
                        + " field(s) but pattern binds "
                        + bindings.size());
              }
              for (int i = 0; i < bindings.size(); i++) {
                environment().define(bindings.get(i), data.get(i));
              }
            }

            // Check guard in the scope with bindings
            if (branch.hasGuard()) {
              final var guard = branch.guard().accept(this);
              if (!guard.asBoolean()) {
                continue;
              }
            }

            return branch.result().accept(this);
          } finally {
            exit();
          }
        }
      }
    }

    if (node.hasFallback()) {
      return node.fallback().accept(this);
    }

    return SAFEValue.ofVoid();
  }

  @Override
  public SAFEValue visitCaseBranch(final CaseBranchNode node) {
    // Case branches are handled by visitCaseExpression
    return SAFEValue.ofVoid();
  }

  @Override
  public SAFEValue visitFunctionCall(final FunctionCallNode node) {
    final var name = node.name();

    // Handle qualified module calls (e.g., math.factorial)
    if (node.hasPrefix()) {
      return dispatch(node);
    }

    // Check for built-in functions
    if (isBuiltin(name)) {
      if (depth() == 0) {
        // In user code, allow user-defined functions to shadow builtins
        final var match = environment().function(name);
        if (match == null) {
          throw new InterpreterException(
              "Built-in '"
                  + name
                  + "' requires import. Use: import "
                  + BuiltinRegistry.module(name)
                  + ";");
        }
        // Fall through to user-defined function handling below
      } else {
        return builtin(name, node.arguments());
      }
    }

    // Check if name matches an enum variant constructor
    final var enumeration = environment().variant(name);
    if (enumeration != null) {
      // Find the variant
      for (final EnumVariantNode variant : enumeration.variants()) {
        if (variant.name().equals(name)) {
          // Evaluate arguments
          List<SAFEValue> args = new ArrayList<>();
          for (final ASTNode arg : node.arguments()) {
            args.add(arg.accept(this));
          }

          // Create enum value
          return SAFEValue.ofEnum(enumeration.name(), name, args);
        }
      }
    }

    // Check for call-through-value: f(args) where f is a fn value
    if (environment().has(name)
        && environment().get(name) instanceof SAFEValue.FunctionValue(Closure closure)) {
      return closure(closure, node.arguments());
    }

    // Look up user-defined function
    final var declaration = environment().function(name);
    if (declaration == null) {
      throw new InterpreterException("Undefined function: " + name);
    }

    // Evaluate arguments
    List<SAFEValue> args = new ArrayList<>();
    for (final ASTNode arg : node.arguments()) {
      args.add(arg.accept(this));
    }

    // Create function scope
    final var scope = scope().child();
    final var params = declaration.parameters();

    // Fill in default values for missing arguments, evaluating defaults
    // in the function scope so earlier params are visible
    enter(scope);
    try {
      for (int i = 0; i < params.size(); i++) {
        if (i < args.size()) {
          scope.define(params.get(i).name(), args.get(i));
        } else if (params.get(i).hasDefault()) {
          final var value = params.get(i).initial().accept(this);
          scope.define(params.get(i).name(), value);
          args.add(value);
        }
      }
    } finally {
      exit();
    }

    if (params.size() != args.size()) {
      throw new InterpreterException(
          "Function " + name + " expects " + params.size() + " arguments, got " + args.size());
    }

    return invoke(declaration, scope, args, name);
  }

  @Override
  public SAFEValue visitVariableReference(final VariableReferenceNode node) {
    final var parts = node.parts();
    final var first = parts.get(0);

    // Check if this is a module-qualified variable via colon syntax (e.g., math:PI)
    if (node.hasPrefix() && modules.containsKey(node.prefix())) {
      final var allowed = selections.get(node.prefix());
      final var target = parts.getFirst();
      if (allowed != null && !allowed.contains(target)) {
        throw new InterpreterException(
            "Variable '"
                + target
                + "' was not included in selective import of module '"
                + node.prefix()
                + "'");
      }
      final var scope = modules.get(node.prefix());
      SAFEValue result = scope.get(target);
      for (int i = 1; i < parts.size(); i++) {
        if (!result.isObject()) {
          throw new InterpreterException("Cannot access field on non-object type");
        }
        result = result.fields().get(parts.get(i));
        if (result == null) {
          throw new InterpreterException("Field not found: " + parts.get(i));
        }
      }
      return result;
    }

    // Check if the first part is a module name (dot syntax)
    if (parts.size() >= 2 && modules.containsKey(first)) {
      // Check selective import restrictions
      final var allowed = selections.get(first);
      if (allowed != null && !allowed.contains(parts.get(1))) {
        throw new InterpreterException(
            "Variable '"
                + parts.get(1)
                + "' was not included in selective import of module '"
                + first
                + "'");
      }
      final var scope = modules.get(first);
      SAFEValue result = scope.get(parts.get(1));
      for (int i = 2; i < parts.size(); i++) {
        if (!result.isObject()) {
          throw new InterpreterException("Cannot access field on non-object type");
        }
        final var field = parts.get(i);
        result = result.fields().get(field);
        if (result == null) {
          throw new InterpreterException("Field not found: " + field);
        }
      }
      return result;
    }

    SAFEValue result;
    try {
      result = environment().get(first);
    } catch (RuntimeException exception) {
      // Not a variable — check if it's a function reference
      if (parts.size() == 1) {
        final var function = environment().function(first);
        if (function != null) {
          return SAFEValue.ofFunction(Closure.named(first, function, environment().snapshot()));
        }
      }
      throw exception;
    }

    // Handle dotted field access
    for (int i = 1; i < parts.size(); i++) {
      if (!result.isObject()) {
        throw new InterpreterException("Cannot access field on non-object type");
      }
      final var fields = result.fields();
      final var field = parts.get(i);
      result = fields.get(field);
      if (result == null) {
        throw new InterpreterException("Field not found: " + field);
      }
    }

    return result;
  }

  @Override
  public SAFEValue visitFieldAccess(final FieldAccessNode node) {
    final var receiver = node.receiver().accept(this);
    if (!receiver.isObject()) {
      throw new InterpreterException("Cannot access field on non-object type");
    }
    final var fields = receiver.fields();
    final var result = fields.get(node.field());
    if (result == null) {
      throw new InterpreterException("Field not found: " + node.field());
    }
    return result;
  }

  @Override
  public SAFEValue visitObjectCreation(final ObjectCreationNode node) {
    final var name = node.type();
    final var type = scope().type(name);

    if (type == null) {
      throw new InterpreterException("Undefined type: " + name);
    }

    // Evaluate assigned values first (honors source-order side effects).
    final Map<String, SAFEValue> assigned = new HashMap<>();
    for (final FieldAssignmentNode fieldAssign : node.fields()) {
      assigned.put(fieldAssign.field(), fieldAssign.value().accept(this));
    }

    // Populate the backing map in declaration order so print output mirrors the type.
    final Map<String, SAFEValue> fields = new LinkedHashMap<>();
    for (final FieldDeclarationNode fieldDecl : type.fields()) {
      final var field = fieldDecl.name();
      fields.put(field, assigned.getOrDefault(field, SAFEValue.ofVoid()));
    }
    // Any extra field assignments that weren't declared (shouldn't happen post-analysis but
    // preserve their values if they're present) go last.
    for (final var entry : assigned.entrySet()) {
      fields.putIfAbsent(entry.getKey(), entry.getValue());
    }

    return SAFEValue.ofObject(name, fields);
  }

  @Override
  public SAFEValue visitFieldAssignment(final FieldAssignmentNode node) {
    // Field assignments are handled in visitObjectCreation
    return SAFEValue.ofVoid();
  }

  @Override
  public SAFEValue visitLiteral(final LiteralNode node) {
    return switch (node) {
      case LiteralNode.IntLiteral i -> SAFEValue.ofInt(i.value());
      case LiteralNode.UintLiteral u -> SAFEValue.ofUint(u.value());
      case LiteralNode.FloatLiteral f -> SAFEValue.ofFloat(f.value());
      case LiteralNode.StringLiteral s -> SAFEValue.ofString(s.value());
      case LiteralNode.BoolLiteral b -> SAFEValue.ofBoolean(b.value());
    };
  }

  @Override
  public SAFEValue visitListLiteral(final ListLiteralNode node) {
    List<SAFEValue> elements = new ArrayList<>();
    for (final ASTNode elem : node.elements()) {
      elements.add(elem.accept(this));
    }
    return SAFEValue.ofList(elements);
  }

  @Override
  public SAFEValue visitMapLiteral(final MapLiteralNode node) {
    Map<SAFEValue, SAFEValue> map = new LinkedHashMap<>();
    for (final MapEntryNode entry : node.entries()) {
      final var key = entry.key().accept(this);
      final var value = entry.value().accept(this);
      map.put(key, value);
    }
    return SAFEValue.ofMap(map);
  }

  @Override
  public SAFEValue visitMapEntry(final MapEntryNode node) {
    // Map entries are handled in visitMapLiteral
    return SAFEValue.ofVoid();
  }

  @Override
  public SAFEValue visitAssert(final AssertNode node) {
    final var condition = node.condition().accept(this);
    if (!condition.asBoolean()) {
      String message = "Assertion failed";
      if (node.hasMessage()) {
        final var text = node.message().accept(this);
        message = text.asString();
      }
      throw new InterpreterException(message);
    }
    return SAFEValue.ofVoid();
  }

  @Override
  public SAFEValue visitIndexAccess(final IndexAccessNode node) {
    final var container = node.container().accept(this);
    final var index = node.index().accept(this);

    return switch (container) {
      case SAFEValue.ListValue list -> list.element(safeIndex(index.asInt()));
      case SAFEValue.TupleValue(List<SAFEValue> elements) -> {
        final var position = safeIndex(index.asInt());
        if (position < 0 || position >= elements.size()) {
          throw new InterpreterException("Tuple index out of bounds: " + position);
        }
        yield elements.get(position);
      }
      case SAFEValue.MapValue map -> map.entry(index);
      case SAFEValue.StringValue(String str) -> {
        final var position = (int) index.asInt();
        if (position < 0 || position >= str.length()) {
          throw new InterpreterException("String index out of bounds: " + position);
        }
        yield SAFEValue.ofString(String.valueOf(str.charAt(position)));
      }
      default -> throw new InterpreterException("Cannot index into " + container.type());
    };
  }

  @Override
  public SAFEValue visitIndexAssignment(final IndexAssignmentNode node) {
    // Check const for root container variable
    if (node.container() instanceof VariableReferenceNode ref && ref.parts().size() == 1) {
      final var name = ref.parts().getFirst();
      if (environment().isConst(name)) {
        throw new InterpreterException("Cannot assign to index of const variable: " + name);
      }
    }
    final var container = node.container().accept(this);
    final var indices = node.indices();
    final var value = node.value().accept(this);

    // Navigate through all but the last index
    SAFEValue current = container;
    for (int i = 0; i < indices.size() - 1; i++) {
      final var index = indices.get(i).accept(this);
      current =
          switch (current) {
            case SAFEValue.ListValue list -> list.element(safeIndex(index.asInt()));
            case SAFEValue.MapValue map -> map.entry(index);
            default -> throw new InterpreterException("Cannot index into " + current.type());
          };
    }

    // Apply assignment on the last index
    final var last = indices.getLast().accept(this);
    switch (current) {
      case SAFEValue.ListValue list -> list.setElement(safeIndex(last.asInt()), value);
      case SAFEValue.MapValue map -> map.setEntry(last, value);
      default -> throw new InterpreterException("Cannot index into " + current.type());
    }

    return value;
  }

  @Override
  public SAFEValue visitEnumDeclaration(final EnumDeclarationNode node) {
    // Register the enum in the environment
    scope().define(node.name(), node);

    // Register each variant as a callable constructor function
    for (final EnumVariantNode variant : node.variants()) {
      final var label = variant.name();

      // If it's a unit variant (no fields), also register as a variable
      if (!variant.hasFields()) {
        final var value = SAFEValue.ofEnum(node.name(), label, new ArrayList<>());
        scope().define(label, value);
      }
    }

    return SAFEValue.ofVoid();
  }

  @Override
  public SAFEValue visitEnumVariant(final EnumVariantNode node) {
    // Enum variants are handled in visitEnumDeclaration
    return SAFEValue.ofVoid();
  }

  @Override
  public SAFEValue visitEnumPattern(final EnumPatternNode node) {
    // Enum patterns are handled in visitCaseExpression
    return SAFEValue.ofVoid();
  }

  @Override
  public SAFEValue visitStringInterpolation(final StringInterpolationNode node) {
    final var result = new StringBuilder();
    for (final ASTNode part : node.parts()) {
      if (part instanceof LiteralNode.StringLiteral literal) {
        result.append(literal.value());
      } else {
        final var value = part.accept(this);
        result.append(value.asString());
      }
    }
    return SAFEValue.ofString(result.toString());
  }

  private SAFEValue closure(final Closure closure, final List<ASTNode> arguments) {
    final var args = new ArrayList<SAFEValue>();
    for (final var arg : arguments) {
      args.add(arg.accept(this));
    }

    if (closure.isNamed()) {
      // Named function reference — call through the captured environment
      final var function = closure.declaration();
      final var scope = closure.environment().child();
      final var params = function.parameters();
      // Fill defaults in function scope so earlier params are visible
      enter(scope);
      try {
        for (int i = 0; i < params.size(); i++) {
          if (i < args.size()) {
            scope.define(params.get(i).name(), args.get(i));
          } else if (params.get(i).hasDefault()) {
            final var value = params.get(i).initial().accept(this);
            scope.define(params.get(i).name(), value);
            args.add(value);
          }
        }
      } finally {
        exit();
      }
      if (params.size() != args.size()) {
        throw new InterpreterException(
            "Function "
                + closure.name()
                + " expects "
                + params.size()
                + " arguments, got "
                + args.size());
      }
      return invoke(function, scope, args, closure.name());
    } else {
      // Lambda — evaluate body in captured scope
      final var lambda = closure.lambda();
      final var scope = closure.environment().child();
      final var params = lambda.parameters();
      if (params.size() != args.size()) {
        throw new InterpreterException(
            "Lambda expects " + params.size() + " arguments, got " + args.size());
      }
      for (int i = 0; i < params.size(); i++) {
        scope.define(params.get(i).name(), args.get(i));
      }
      enter(scope);
      try {
        return lambda.body().accept(this);
      } finally {
        exit();
      }
    }
  }

  private QualifiedVariant qualifiedVariant(final String module, final String name) {
    if (registry == null || !registry.has(module)) {
      return null;
    }
    for (final var entry : registry.enums(module).entrySet()) {
      final var enumeration = entry.getValue();
      if (!enumeration.isPublic()) {
        continue;
      }
      for (final var variant : enumeration.variants()) {
        if (variant.name().equals(name)) {
          return new QualifiedVariant(enumeration.name(), variant);
        }
      }
    }
    return null;
  }

  private SAFEValue dispatch(final FunctionCallNode node) {
    final var module = node.prefix();
    final var name = node.name();

    final var context = modules.get(module);
    if (context == null) {
      throw new InterpreterException("Unknown module: " + module);
    }

    // Check selective import restrictions. Qualified enum variant construction bypasses the
    // filter — the user is explicit by qualifying, and the owning enum's visibility is checked
    // at the variant lookup below.
    final var allowed = selections.get(module);
    if (allowed != null && !allowed.contains(name) && qualifiedVariant(module, name) == null) {
      throw new InterpreterException(
          "Function '"
              + name
              + "' was not included in selective import of module '"
              + module
              + "'");
    }

    final var declaration = context.function(name);
    if (declaration == null) {
      // Qualified enum variant construction: mod:Ok(42). Before erroring, consult the
      // module's exported enums for a matching variant.
      final var match = qualifiedVariant(module, name);
      if (match != null) {
        final List<SAFEValue> variantArgs = new ArrayList<>();
        for (final ASTNode arg : node.arguments()) {
          variantArgs.add(arg.accept(this));
        }
        return SAFEValue.ofEnum(match.enumName(), name, variantArgs);
      }
      throw new InterpreterException(
          "Undefined function '" + name + "' in module '" + module + "'");
    }

    if (!declaration.isPublic()) {
      throw new InterpreterException(
          "Cannot access private function '" + name + "' in module '" + module + "'");
    }

    // Evaluate arguments in current environment
    final List<SAFEValue> args = new ArrayList<>();
    for (final ASTNode arg : node.arguments()) {
      args.add(arg.accept(this));
    }

    // Create function scope as child of the module environment
    final var scope = context.child();
    final var params = declaration.parameters();

    // Fill in default values in scope so earlier params are visible to defaults
    enter(scope);
    try {
      for (int i = 0; i < params.size(); i++) {
        if (i < args.size()) {
          scope.define(params.get(i).name(), args.get(i));
        } else if (params.get(i).hasDefault()) {
          final SAFEValue fallback = params.get(i).initial().accept(this);
          scope.define(params.get(i).name(), fallback);
          args.add(fallback);
        }
      }
    } finally {
      exit();
    }

    if (params.size() != args.size()) {
      throw new InterpreterException(
          "Function "
              + module
              + "."
              + name
              + " expects "
              + params.size()
              + " arguments, got "
              + args.size());
    }

    // Push the module frame so that body/contracts/type lookups resolve
    // against the imported module's namespace, not the caller's. The frame's
    // global field is overridden to the module's environment so that
    // scope()-based queries (type definitions, constants) use module globals.
    final var qualified = module + ":" + name;
    module(scope);
    frames.peek().global = context;
    try {
      return invoke(declaration, scope, args, qualified);
    } finally {
      exit();
    }
  }

  private int safeIndex(final long value) {
    if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
      throw new InterpreterException("Index out of range: " + value);
    }
    return (int) value;
  }

  private boolean isBuiltin(final String name) {
    return BuiltinRegistry.isBuiltin(name);
  }

  private SAFEValue builtin(final String name, final List<ASTNode> arguments) {
    final List<SAFEValue> args = new ArrayList<>();
    for (final ASTNode arg : arguments) {
      args.add(arg.accept(this));
    }

    final var builtin = BuiltinRegistry.get(name);
    if (builtin != null) {
      final var expected = builtin.signature().parameters().size();
      final var minimum = builtin.minimum();
      if (args.size() < minimum || args.size() > expected) {
        throw new InterpreterException(
            name + "() expects " + expected + " argument(s) but got " + args.size());
      }
    }

    final var function = executors.get(name);
    if (function == null) {
      throw new InterpreterException("Unknown built-in function: " + name);
    }
    return function.execute(args);
  }

  /** Enum variant with its owning enum's name, for qualified construction (mod:Ok). */
  private record QualifiedVariant(String enumName, EnumVariantNode variant) {}

  private static final class Frame {
    final int depth;
    Environment environment;
    Environment global;

    Frame(final Environment environment, final Environment global, final int depth) {
      this.environment = environment;
      this.global = global;
      this.depth = depth;
    }
  }
}
