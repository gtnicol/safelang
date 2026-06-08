package io.safelang.compiler.bytecode;

import io.safelang.ModuleRegistry;
import io.safelang.ast.*;
import io.safelang.bytecode.*;
import io.safelang.runtime.BuiltinRegistry;
import io.safelang.runtime.SAFEValue;
import java.util.*;
import java.util.LinkedHashMap;

/**
 * Compiles a SAFE AST into bytecode. Implements the ASTVisitor interface to walk the AST and emit
 * bytecode instructions.
 */
public class BytecodeCompiler implements ASTVisitor<Void> {

  private final Deque<Frame> frames = new ArrayDeque<>();
  private final BytecodeStatementCompiler statements = new BytecodeStatementCompiler();
  private final ExpressionCompiler expressions = new ExpressionCompiler();
  private final BytecodeFunctionCompiler functions = new BytecodeFunctionCompiler();
  private final Deque<Boolean> returnFlags = new ArrayDeque<>();
  private Map<String, BytecodeChunk> chunks;
  private BytecodeModule module;
  private ConstantPool pool;
  private Map<String, Integer> indices; // function name -> index in module
  private Map<String, FunctionDeclarationNode> declarations; // function name -> declaration
  private Map<String, int[]> metadata; // function name -> [index, arity, locals]
  private Map<String, Integer> names; // global var name -> constant pool name index
  private ModuleRegistry registry;
  private int counter;
  private int returnSlot = -1;

  public BytecodeCompiler() {}

  private Frame frame() {
    return frames.peek();
  }

  private BytecodeChunk chunk() {
    return frames.peek().chunk;
  }

  private Map<String, Integer> slots() {
    return frames.peek().slots;
  }

  private boolean scoped() {
    return frames.peek().scoped;
  }

  private String module() {
    return frames.peek().module;
  }

  /**
   * For a qualified variant construction {@code prefix:name}, find the name of the enum in the
   * prefixed module that owns a variant with this name, or null if no such enum/variant exists.
   * Used to dispatch {@code mod:Ok(...)} to enum construction before erroring on "Undefined
   * function".
   */
  private String qualifiedVariantEnum(final String prefix, final String name) {
    if (registry == null || !registry.has(prefix)) {
      return null;
    }
    for (final var enumeration : registry.enums(prefix).values()) {
      if (!enumeration.isPublic()) {
        continue;
      }
      for (final var variant : enumeration.variants()) {
        if (variant.name().equals(name)) {
          return enumeration.name();
        }
      }
    }
    return null;
  }

  private String function() {
    return frames.peek().function;
  }

  private int allocate(final String name) {
    final var frame = frames.peek();
    frame.slots.put(name, frame.next);
    return frame.next++;
  }

  private void enterReturnTracking() {
    returnFlags.push(false);
  }

  private void exitReturnTracking() {
    if (!returnFlags.isEmpty()) {
      returnFlags.pop();
    }
  }

  private void markReturnEncountered() {
    if (!returnFlags.isEmpty()) {
      returnFlags.pop();
      returnFlags.push(true);
    }
  }

  private boolean hasReturnStatements() {
    return !returnFlags.isEmpty() && returnFlags.peek();
  }

  private BytecodeCaseCompiler caseCompiler() {
    return new BytecodeCaseCompiler(
        new BytecodeCaseContext() {
          @Override
          public BytecodeChunk chunk() {
            return BytecodeCompiler.this.chunk();
          }

          @Override
          public Map<String, Integer> slots() {
            return BytecodeCompiler.this.slots();
          }

          @Override
          public BytecodeModule module() {
            return BytecodeCompiler.this.module;
          }

          @Override
          public int allocate(final String name) {
            return BytecodeCompiler.this.allocate(name);
          }

          @Override
          public void compile(final ASTNode node) {
            node.accept(BytecodeCompiler.this);
          }
        });
  }

  private BytecodeLambdaCompiler lambdaCompiler() {
    return new BytecodeLambdaCompiler(
        new BytecodeLambdaContext() {
          @Override
          public Map<String, Integer> slots() {
            return BytecodeCompiler.this.slots();
          }

          @Override
          public int count(final List<ASTNode> body) {
            return BytecodeCompiler.this.count(body);
          }

          @Override
          public String next() {
            return "__lambda_" + counter++;
          }

          @Override
          public int add(final String name) {
            return pool.addName(name);
          }

          @Override
          public int reserve() {
            return module.reserve();
          }

          @Override
          public void register(final String name, final int position) {
            indices.put(name, position);
          }

          @Override
          public void push() {
            frames.push(new Frame(new BytecodeChunk(), true, module(), function()));
          }

          @Override
          public void pop() {
            frames.pop();
          }

          @Override
          public BytecodeChunk chunk() {
            return BytecodeCompiler.this.chunk();
          }

          @Override
          public int allocate(final String name) {
            return BytecodeCompiler.this.allocate(name);
          }

          @Override
          public void compile(final ASTNode node) {
            node.accept(BytecodeCompiler.this);
          }

          @Override
          public void define(final int position, final FunctionDefinition definition) {
            module.setFunction(position, definition);
          }
        },
        names.keySet(),
        indices.keySet());
  }

  private BytecodeImportCompiler importCompiler() {
    return new BytecodeImportCompiler(
        registry,
        new BytecodeImportContext() {
          @Override
          public void type(final TypeDeclarationNode node) {
            BytecodeCompiler.this.type(node);
          }

          @Override
          public void enumeration(final EnumDeclarationNode node) {
            BytecodeCompiler.this.enumeration(node);
          }

          @Override
          public void register(final FunctionDeclarationNode node, final String name) {
            BytecodeCompiler.this.register(node, name);
          }

          @Override
          public void compile(
              final FunctionDeclarationNode node, final String name, final String module) {
            BytecodeCompiler.this.compile(node, name, module);
          }

          @Override
          public void push(final String module) {
            frames.push(new Frame(new BytecodeChunk(), false, module, null));
          }

          @Override
          public void pop() {
            frames.pop();
          }

          @Override
          public void compile(final ASTNode node) {
            node.accept(BytecodeCompiler.this);
          }

          @Override
          public BytecodeChunk chunk() {
            return BytecodeCompiler.this.chunk();
          }

          @Override
          public int add(final String name) {
            return pool.addName(name);
          }

          @Override
          public void name(final String name, final int index) {
            names.put(name, index);
          }

          @Override
          public void global(final String name, final int index, final boolean constant) {
            module.add(new BytecodeModule.GlobalVarInfo(name, index, constant));
          }

          @Override
          public boolean registered(final String name) {
            return indices.containsKey(name);
          }

          @Override
          public void append(final String module) {
            if (!chunks.containsKey(module)) {
              chunks.put(module, new BytecodeChunk());
            }
            chunks.get(module).append(chunk().bytes());
          }
        });
  }

  public void setRegistry(final ModuleRegistry registry) {
    this.registry = registry;
  }

  /** Compile a program AST into a BytecodeModule. */
  public BytecodeModule compile(final ProgramNode program) {
    this.module = new BytecodeModule();
    this.pool = module.pool();
    this.chunks = new LinkedHashMap<>();
    this.counter = 0;
    this.frames.clear();
    indices = new HashMap<>();
    declarations = new HashMap<>();
    metadata = new HashMap<>();
    names = new HashMap<>();

    // Phase 0: Merge imported module declarations with name-mangling
    importCompiler().compile(program);

    // Phase 1: Register all declarations (types, enums, functions)
    for (final var declaration : program.declarations()) {
      switch (declaration) {
        case TypeDeclarationNode type -> type(type);
        case EnumDeclarationNode enumeration -> enumeration(enumeration);
        case FunctionDeclarationNode function -> register(function);
        default -> {}
      }
    }

    // Phase 2: Compile all function bodies
    for (final var declaration : program.declarations()) {
      if (declaration instanceof FunctionDeclarationNode) {
        compile((FunctionDeclarationNode) declaration);
      }
    }

    // Phase 3: Compile top-level statements (main bytecode)
    frames.push(new Frame(new BytecodeChunk(), false, null, null));

    // Prepend module init chunks (global variable initializations)
    for (final var block : chunks.values()) {
      chunk().append(block.bytes());
    }

    // Compile const declarations from the declarations block
    for (final var declaration : program.declarations()) {
      if (declaration instanceof VariableDeclarationNode) {
        declaration.accept(this);
      }
    }

    for (final var statement : program.statements()) {
      statement.accept(this);
    }

    chunk().emitOpcode(OpCode.HALT);
    module.setMain(chunk().bytes());
    module.setLocals(Math.max(frame().next, 1));
    frames.pop();

    return module;
  }

  private void type(final TypeDeclarationNode node) {
    final var index = pool.addName(node.name());
    final var fields = new ArrayList<TypeDefinition.FieldInfo>();
    for (final var field : node.fields()) {
      final var slot = pool.addName(field.name());
      final var tag = TypeDefinition.typeTagFromName(field.type().name());
      fields.add(new TypeDefinition.FieldInfo(field.name(), slot, tag));
    }
    module.add(new TypeDefinition(node.name(), index, fields));
  }

  private void enumeration(final EnumDeclarationNode node) {
    final var index = pool.addName(node.name());
    final var variants = new ArrayList<EnumInfo.VariantInfo>();
    for (final var variant : node.variants()) {
      final var entry = pool.addName(variant.name());
      final var tags = new ArrayList<Integer>();
      if (variant.hasFields()) {
        for (final var ft : variant.fields()) {
          tags.add(TypeDefinition.typeTagFromName(ft.name()));
        }
      }
      variants.add(new EnumInfo.VariantInfo(variant.name(), entry, tags));
    }
    module.add(new EnumInfo(node.name(), index, variants));
  }

  private void register(final FunctionDeclarationNode node) {
    final var index = pool.addName(node.name());
    final var position = module.reserve();
    indices.put(node.name(), position);
    declarations.put(node.name(), node);

    final var arity = node.parameters().size();
    var locals = arity + count(node.body());
    if (node.hasEnsures()) {
      locals++; // 'result' slot used by ensures contract
    }
    metadata.put(node.name(), new int[] {index, arity, locals});
  }

  private int count(final List<ASTNode> statements) {
    int total = 0;
    for (final var statement : statements) {
      switch (statement) {
        case VariableDeclarationNode variable -> {
          total++;
          total += count(variable.initializer());
        }
        case DestructureNode destructure -> total += destructure.names().size();
        case ForStatementNode forStatement -> {
          total++; // loop variable
          total++; // iterator slot
          total += count(forStatement.body());
        }
        case WhileStatementNode whileStatement -> {
          total++; // counter slot
          total += count(whileStatement.body());
        }
        case ReturnNode returnNode -> {
          if (returnNode.hasExpression()) {
            total += count(returnNode.expression());
          }
        }
        case ExpressionStatementNode expression -> total += count(expression.expression());
        case AssignmentNode assignment -> total += count(assignment.value());
        case IndexAssignmentNode indexAssignment -> total += count(indexAssignment.value());
        default -> {}
      }
    }
    return total;
  }

  private int count(final ASTNode node) {
    if (node == null) return 0;
    return switch (node) {
      case CaseExpressionNode caseNode -> {
        int total = 0;
        for (final var branch : caseNode.branches()) {
          if (branch.pattern() instanceof EnumPatternNode enumPattern) {
            if (enumPattern.hasBindings()) {
              total += enumPattern.bindings().size();
            }
          }
          total += count(branch.result());
          if (branch.hasGuard()) total += count(branch.guard());
        }
        if (caseNode.hasFallback()) {
          total += count(caseNode.fallback());
        }
        yield total;
      }
      case DoExpressionNode block -> {
        int total = 0;
        for (final var statement : block.statements()) {
          if (statement instanceof VariableDeclarationNode variable) {
            total++;
            total += count(variable.initializer());
          } else if (statement instanceof ForStatementNode forLoop) {
            total += 2; // iterator + loop variable slots
            total += count(forLoop.iterable());
            total += count(forLoop.body());
          } else if (statement instanceof WhileStatementNode whileLoop) {
            total += 1; // counter slot
            total += count(whileLoop.condition());
            total += count(whileLoop.body());
          } else if (statement instanceof DestructureNode destructure) {
            total += destructure.names().size();
          } else if (statement instanceof ExpressionStatementNode expression) {
            total += count(expression.expression());
          }
        }
        total += count(block.expression());
        yield total;
      }
      case IfExpressionNode ifNode -> {
        int total = count(ifNode.then());
        if (ifNode.hasOtherwise()) total += count(ifNode.otherwise());
        yield total;
      }
      case BinaryExpressionNode binary -> count(binary.left()) + count(binary.right());
      case UnaryExpressionNode unary -> count(unary.operand());
      case FunctionCallNode call -> {
        int total = 0;
        for (final var arg : call.arguments()) {
          total += count(arg);
        }
        yield total;
      }
      default -> 0;
    };
  }

  private void compile(final FunctionDeclarationNode node) {
    final var position = indices.get(node.name());
    final var meta = metadata.get(node.name());
    functions.compile(node, node.name(), null, position, meta);
  }

  private void register(final FunctionDeclarationNode node, final String mangled) {
    final var index = pool.addName(mangled);
    final var position = module.reserve();
    indices.put(mangled, position);
    declarations.put(mangled, node);

    final var arity = node.parameters().size();
    var locals = arity + count(node.body());
    if (node.hasEnsures()) {
      locals++; // 'result' slot used by ensures contract
    }

    metadata.put(mangled, new int[] {index, arity, locals});
  }

  private void compile(final FunctionDeclarationNode node, final String mangled, final String mod) {
    final var position = indices.get(mangled);
    final var meta = metadata.get(mangled);
    functions.compile(node, mangled, mod, position, meta);
  }

  private void defaults(final FunctionDeclarationNode node) {
    for (final var param : node.parameters()) {
      if (param.hasDefault()) {
        final var slot = slots().get(param.name());
        chunk().emitOpShort(OpCode.LOAD_LOCAL, slot);
        chunk().emitOpcode(OpCode.PUSH_VOID);
        chunk().emitOpcode(OpCode.CMP_EQ);
        final var skip = chunk().emitJumpPlaceholder(OpCode.JUMP_FALSE);
        param.initial().accept(this);
        chunk().emitOpShort(OpCode.STORE_LOCAL, slot);
        final var end = chunk().size();
        chunk().patch(skip, (end - (skip + 2)) & 0xFFFF);
      }
    }
  }

  @Override
  public Void visitProgram(final ProgramNode node) {
    // Program compilation is handled by compile() method
    return null;
  }

  @Override
  public Void visitImport(final ImportNode node) {
    // Imports are handled during compile() Phase 0 via registry
    // Selective import filtering is applied there
    return null;
  }

  @Override
  public Void visitType(final TypeNode node) {
    return null;
  }

  @Override
  public Void visitTypeDeclaration(final TypeDeclarationNode node) {
    return null;
  }

  @Override
  public Void visitTupleLiteral(final TupleLiteralNode node) {
    return expressions.visitTupleLiteral(node);
  }

  @Override
  public Void visitSetLiteral(final SetLiteralNode node) {
    return expressions.visitSetLiteral(node);
  }

  @Override
  public Void visitLambda(final LambdaNode node) {
    return expressions.visitLambda(node);
  }

  @Override
  public Void visitDoExpression(final DoExpressionNode node) {
    return statements.doExpression(node);
  }

  private void scope() {
    final var current = frames.peek();
    frames.push(
        new Frame(
            current.chunk,
            new HashMap<>(current.slots),
            current.next,
            current.scoped,
            current.module,
            current.function));
  }

  @Override
  public Void visitRange(final RangeNode node) {
    return expressions.visitRange(node);
  }

  @Override
  public Void visitTypeAlias(final TypeAliasNode node) {
    // Type aliases are resolved at compile time — no bytecode emitted
    return null;
  }

  @Override
  public Void visitFieldDeclaration(final FieldDeclarationNode node) {
    return null;
  }

  @Override
  public Void visitFunctionDeclaration(final FunctionDeclarationNode node) {
    return null;
  }

  @Override
  public Void visitParameter(final ParameterNode node) {
    return null;
  }

  @Override
  public Void visitVariableDeclaration(final VariableDeclarationNode node) {
    return statements.variableDeclaration(node);
  }

  @Override
  public Void visitDestructure(final DestructureNode node) {
    return statements.destructure(node);
  }

  @Override
  public Void visitAssignment(final AssignmentNode node) {
    return statements.assignment(node);
  }

  @Override
  public Void visitForStatement(final ForStatementNode node) {
    return statements.forStatement(node);
  }

  @Override
  public Void visitWhileStatement(final WhileStatementNode node) {
    return statements.whileStatement(node);
  }

  @Override
  public Void visitReturn(final ReturnNode node) {
    markReturnEncountered();
    if (node.hasExpression()) {
      // Detect self-tail-recursion: return f(args) where f is the current function
      if (function() != null && node.expression() instanceof FunctionCallNode call) {
        final var target = call.name();
        // Skip TCO if the call target is actually a builtin (module wrappers call builtins, not
        // themselves)
        final var builtin = module() != null && BuiltinRegistry.isBuiltin(target);
        // Check direct name match or module-mangled match for intra-module calls
        final var mangled = (module() != null) ? module() + "$" + target : target;
        if (!builtin && (target.equals(function()) || mangled.equals(function()))) {
          final var index = indices.get(function());
          if (index != null) {
            // Compile all arguments
            for (final var arg : call.arguments()) {
              arg.accept(this);
            }
            chunk().emitOpcode(OpCode.TAIL_CALL);
            chunk().emitShort(pool.addName(function()));
            chunk().emitByte(call.arguments().size());
            return null;
          }
        }
      }
      node.expression().accept(this);
      if (returnSlot >= 0) {
        chunk().emitOpShort(OpCode.STORE_LOCAL, returnSlot);
        chunk().emitOpShort(OpCode.LOAD_LOCAL, returnSlot);
      }
    } else {
      chunk().emitOpcode(OpCode.PUSH_VOID);
    }
    chunk().emitOpcode(OpCode.RETURN);
    return null;
  }

  @Override
  public Void visitExpressionStatement(final ExpressionStatementNode node) {
    node.expression().accept(this);
    // Expression result is unused; discard it
    chunk().emitOpcode(OpCode.POP);
    return null;
  }

  @Override
  public Void visitBinaryExpression(final BinaryExpressionNode node) {
    return expressions.visitBinaryExpression(node);
  }

  @Override
  public Void visitUnaryExpression(final UnaryExpressionNode node) {
    return expressions.visitUnaryExpression(node);
  }

  @Override
  public Void visitIfExpression(final IfExpressionNode node) {
    return expressions.visitIfExpression(node);
  }

  @Override
  public Void visitCaseExpression(final CaseExpressionNode node) {
    return expressions.visitCaseExpression(node);
  }

  @Override
  public Void visitCaseBranch(final CaseBranchNode node) {
    // Handled by visitCaseExpression
    return null;
  }

  @Override
  public Void visitFunctionCall(final FunctionCallNode node) {
    return expressions.visitFunctionCall(node);
  }

  @Override
  public Void visitVariableReference(final VariableReferenceNode node) {
    return expressions.visitVariableReference(node);
  }

  @Override
  public Void visitFieldAccess(final FieldAccessNode node) {
    return expressions.visitFieldAccess(node);
  }

  @Override
  public Void visitObjectCreation(final ObjectCreationNode node) {
    return expressions.visitObjectCreation(node);
  }

  @Override
  public Void visitFieldAssignment(final FieldAssignmentNode node) {
    // Handled by visitObjectCreation
    return null;
  }

  @Override
  public Void visitLiteral(final LiteralNode node) {
    return expressions.visitLiteral(node);
  }

  @Override
  public Void visitListLiteral(final ListLiteralNode node) {
    return expressions.visitListLiteral(node);
  }

  @Override
  public Void visitMapLiteral(final MapLiteralNode node) {
    return expressions.visitMapLiteral(node);
  }

  @Override
  public Void visitMapEntry(final MapEntryNode node) {
    // Handled by visitMapLiteral
    return null;
  }

  @Override
  public Void visitAssert(final AssertNode node) {
    node.condition().accept(this);
    if (node.hasMessage()) {
      final var msg = node.message();
      if (msg instanceof LiteralNode.StringLiteral string) {
        chunk().emitOpShort(OpCode.ASSERT, pool.addString(string.value()));
      } else {
        msg.accept(this);
        chunk().emitOpcode(OpCode.ASSERT_EXPR);
      }
    } else {
      chunk().emitOpShort(OpCode.ASSERT, pool.addString("Assertion failed"));
    }
    return null;
  }

  @Override
  public Void visitIndexAccess(final IndexAccessNode node) {
    return expressions.visitIndexAccess(node);
  }

  @Override
  public Void visitIndexAssignment(final IndexAssignmentNode node) {
    return expressions.visitIndexAssignment(node);
  }

  @Override
  public Void visitEnumDeclaration(final EnumDeclarationNode node) {
    // Already handled during registration
    return null;
  }

  @Override
  public Void visitEnumVariant(final EnumVariantNode node) {
    return null;
  }

  @Override
  public Void visitEnumPattern(final EnumPatternNode node) {
    // Handled by visitCaseExpression
    return null;
  }

  @Override
  public Void visitStringInterpolation(final StringInterpolationNode node) {
    return expressions.visitStringInterpolation(node);
  }

  private static final class Frame {
    final BytecodeChunk chunk;
    final Map<String, Integer> slots;
    final boolean scoped;
    final String module;
    final String function;
    int next;

    Frame(
        final BytecodeChunk chunk,
        final boolean scoped,
        final String module,
        final String function) {
      this.chunk = chunk;
      this.slots = new HashMap<>();
      this.next = 0;
      this.scoped = scoped;
      this.module = module;
      this.function = function;
    }

    Frame(
        final BytecodeChunk chunk,
        final Map<String, Integer> slots,
        final int next,
        final boolean scoped,
        final String module,
        final String function) {
      this.chunk = chunk;
      this.slots = slots;
      this.next = next;
      this.scoped = scoped;
      this.module = module;
      this.function = function;
    }
  }

  private final class BytecodeFunctionCompiler {

    void compile(
        final FunctionDeclarationNode node,
        final String name,
        final String moduleName,
        final int position,
        final int[] meta) {
      enterReturnTracking();
      frames.push(new Frame(new BytecodeChunk(), true, moduleName, name));
      try {
        for (final var param : node.parameters()) {
          allocate(param.name());
        }
        returnSlot = allocate("__return_value");

        defaults(node);
        for (final var statement : node.body()) {
          statement.accept(BytecodeCompiler.this);
        }
        final var returned = hasReturnStatements();
        if (!returned) {
          chunk().emitOpcode(OpCode.PUSH_VOID);
        } else if (returnSlot >= 0) {
          chunk().emitOpShort(OpCode.LOAD_LOCAL, returnSlot);
        }
        chunk().emitOpcode(OpCode.RETURN);

        final var body = chunk().bytes();
        final byte[] requires = contractBytes(node.requires(), moduleName, name, false);
        final byte[] ensures = contractBytes(node.ensures(), moduleName, name, true);
        final byte[] decreases = contractBytes(node.decreases(), moduleName, name, false);

        final var locals = meta[2] + 1;
        final var info =
            new FunctionDefinition(
                name, meta[0], meta[1], locals, body, requires, ensures, decreases);
        module.setFunction(position, info);
      } finally {
        frames.pop();
        exitReturnTracking();
        returnSlot = -1;
      }
    }

    private byte[] contractBytes(
        final ASTNode contract, final String moduleName, final String name, final boolean ensure) {
      if (contract == null) {
        return null;
      }
      final var chunk = new BytecodeChunk();
      frames.push(
          new Frame(
              chunk,
              ensure ? new HashMap<>(slots()) : slots(),
              frame().next,
              true,
              moduleName,
              name));
      try {
        if (ensure) {
          allocate("result");
        }
        contract.accept(BytecodeCompiler.this);
      } finally {
        frames.pop();
      }
      return chunk.bytes();
    }
  }

  private final class BytecodeStatementCompiler {

    Void doExpression(final DoExpressionNode node) {
      scope();
      try {
        for (final var statement : node.statements()) {
          statement.accept(BytecodeCompiler.this);
        }
        node.expression().accept(BytecodeCompiler.this);
      } finally {
        frames.pop();
      }
      return null;
    }

    Void variableDeclaration(final VariableDeclarationNode node) {
      final var name = node.name();
      if (node.hasInitializer()) {
        node.initializer().accept(BytecodeCompiler.this);
      } else {
        chunk().emitOpcode(OpCode.PUSH_VOID);
      }
      if (scoped()) {
        final var slot = allocate(name);
        chunk().emitOpShort(OpCode.STORE_LOCAL, slot);
      } else {
        final var index = pool.addName(name);
        names.put(name, index);
        module.add(new BytecodeModule.GlobalVarInfo(name, index, node.isConstant()));
        chunk().emitOpShort(OpCode.STORE_GLOBAL, index);
      }
      return null;
    }

    Void destructure(final DestructureNode node) {
      node.initializer().accept(BytecodeCompiler.this);
      final var bindings = node.names();
      for (int i = 0; i < bindings.size(); i++) {
        chunk().emitOpcode(OpCode.DUP);
        final var constant = pool.addInt(i);
        chunk().emitOpShort(OpCode.CONST_INT, constant);
        chunk().emitOpcode(OpCode.GET_INDEX);
        if (scoped()) {
          final var slot = allocate(bindings.get(i));
          chunk().emitOpShort(OpCode.STORE_LOCAL, slot);
        } else {
          final var index = pool.addName(bindings.get(i));
          names.put(bindings.get(i), index);
          module.add(new BytecodeModule.GlobalVarInfo(bindings.get(i), index, node.isConstant()));
          chunk().emitOpShort(OpCode.STORE_GLOBAL, index);
        }
      }
      chunk().emitOpcode(OpCode.POP);
      return null;
    }

    Void assignment(final AssignmentNode node) {
      final var parts = node.parts();
      if (parts.size() == 1) {
        final var name = parts.get(0);
        node.value().accept(BytecodeCompiler.this);
        final var slot = slots().get(name);
        if (slot != null) {
          chunk().emitOpShort(OpCode.STORE_LOCAL, slot);
        } else {
          final var index = pool.addName(name);
          chunk().emitOpShort(OpCode.STORE_GLOBAL, index);
        }
      } else {
        final var name = parts.get(0);
        final var slot = slots().get(name);
        if (slot != null) {
          chunk().emitOpShort(OpCode.LOAD_LOCAL, slot);
        } else {
          final var index = pool.addName(name);
          chunk().emitOpShort(OpCode.LOAD_GLOBAL, index);
        }
        for (int i = 1; i < parts.size() - 1; i++) {
          chunk().emitOpShort(OpCode.GET_FIELD, pool.addName(parts.get(i)));
        }
        node.value().accept(BytecodeCompiler.this);
        chunk().emitOpShort(OpCode.SET_FIELD, pool.addName(parts.get(parts.size() - 1)));
        chunk().emitOpcode(OpCode.POP);
      }
      return null;
    }

    Void forStatement(final ForStatementNode node) {
      node.iterable().accept(BytecodeCompiler.this);
      final var saved = new HashSet<>(slots().keySet());
      final var iterator = frame().next++;
      final var slot = allocate(node.variable());
      chunk().emitOpShort(OpCode.ITER_INIT, iterator);
      final var start = chunk().position();
      final var patch = chunk().position() + 3;
      chunk().emitOpcode(OpCode.ITER_NEXT);
      chunk().emitShort(slot);
      chunk().emitShort(0);
      for (final var statement : node.body()) {
        statement.accept(BytecodeCompiler.this);
      }
      final var jump = start - (chunk().position() + 3);
      chunk().emitOpShort(OpCode.JUMP, jump & 0xFFFF);
      final var offset = chunk().position() - (start + 5);
      chunk().patch(patch, offset & 0xFFFF);
      slots().keySet().retainAll(saved);
      return null;
    }

    Void whileStatement(final WhileStatementNode node) {
      final var saved = new HashSet<>(slots().keySet());
      node.bound().accept(BytecodeCompiler.this);
      chunk().emitOpcode(OpCode.DUP);
      chunk().emitOpShort(OpCode.CONST_INT, pool.addInt(0L));
      chunk().emitOpcode(OpCode.CMP_GE);
      chunk().emitOpShort(OpCode.ASSERT, pool.addString("While loop bound must be non-negative"));
      final var counter = frame().next++;
      chunk().emitOpShort(OpCode.STORE_LOCAL, counter);
      final var start = chunk().position();
      chunk().emitOpShort(OpCode.LOAD_LOCAL, counter);
      chunk().emitOpShort(OpCode.CONST_INT, pool.addInt(0L));
      chunk().emitOpcode(OpCode.CMP_LE);
      final var patch1 = chunk().position() + 1;
      chunk().emitOpShort(OpCode.JUMP_TRUE, 0);
      node.condition().accept(BytecodeCompiler.this);
      final var patch2 = chunk().position() + 1;
      chunk().emitOpShort(OpCode.JUMP_FALSE, 0);
      for (final var statement : node.body()) {
        statement.accept(BytecodeCompiler.this);
      }
      chunk().emitOpShort(OpCode.LOAD_LOCAL, counter);
      chunk().emitOpShort(OpCode.CONST_INT, pool.addInt(1L));
      chunk().emitOpcode(OpCode.SUB);
      chunk().emitOpShort(OpCode.STORE_LOCAL, counter);
      final var jump = start - (chunk().position() + 3);
      chunk().emitOpShort(OpCode.JUMP, jump & 0xFFFF);
      final var offset1 = chunk().position() - (patch1 + 2);
      chunk().patch(patch1, offset1 & 0xFFFF);
      final var offset2 = chunk().position() - (patch2 + 2);
      chunk().patch(patch2, offset2 & 0xFFFF);
      slots().keySet().retainAll(saved);
      return null;
    }
  }

  private final class ExpressionCompiler extends AbstractASTVisitor<Void> {

    @Override
    public Void visitTupleLiteral(final TupleLiteralNode node) {
      if (node.elements().size() > SAFEValue.MAX_TUPLE_SIZE) {
        throw new BytecodeException(
            "Tuple size "
                + node.elements().size()
                + " exceeds maximum of "
                + SAFEValue.MAX_TUPLE_SIZE
                + " at line "
                + node.line());
      }
      for (final var element : node.elements()) {
        element.accept(BytecodeCompiler.this);
      }
      chunk().emitOpcode(OpCode.NEW_TUPLE);
      chunk().emitShort(node.elements().size());
      return null;
    }

    @Override
    public Void visitSetLiteral(final SetLiteralNode node) {
      for (final var element : node.elements()) {
        element.accept(BytecodeCompiler.this);
      }
      chunk().emitOpcode(OpCode.NEW_SET);
      chunk().emitShort(node.elements().size());
      return null;
    }

    @Override
    public Void visitLambda(final LambdaNode node) {
      lambdaCompiler().compile(node);
      return null;
    }

    @Override
    public Void visitRange(final RangeNode node) {
      node.start().accept(BytecodeCompiler.this);
      node.end().accept(BytecodeCompiler.this);
      if (node.hasStep()) {
        node.step().accept(BytecodeCompiler.this);
        chunk().emitOpcode(OpCode.NEW_RANGE_STEP);
      } else {
        chunk().emitOpcode(OpCode.NEW_RANGE);
      }
      return null;
    }

    @Override
    public Void visitListLiteral(final ListLiteralNode node) {
      final var elements = node.elements();
      for (final var elem : elements) {
        elem.accept(BytecodeCompiler.this);
      }
      chunk().emitOpShort(OpCode.NEW_LIST, elements.size());
      return null;
    }

    @Override
    public Void visitMapLiteral(final MapLiteralNode node) {
      final var entries = node.entries();
      for (final var entry : entries) {
        entry.key().accept(BytecodeCompiler.this);
        entry.value().accept(BytecodeCompiler.this);
      }
      chunk().emitOpShort(OpCode.NEW_MAP, entries.size());
      return null;
    }

    @Override
    public Void visitIndexAccess(final IndexAccessNode node) {
      node.container().accept(BytecodeCompiler.this);
      node.index().accept(BytecodeCompiler.this);
      chunk().emitOpcode(OpCode.GET_INDEX);
      return null;
    }

    @Override
    public Void visitIndexAssignment(final IndexAssignmentNode node) {
      node.container().accept(BytecodeCompiler.this);
      final var indices = node.indices();
      for (int i = 0; i < indices.size() - 1; i++) {
        indices.get(i).accept(BytecodeCompiler.this);
        chunk().emitOpcode(OpCode.GET_INDEX);
      }
      indices.get(indices.size() - 1).accept(BytecodeCompiler.this);
      node.value().accept(BytecodeCompiler.this);
      chunk().emitOpcode(OpCode.SET_INDEX);
      return null;
    }

    @Override
    public Void visitStringInterpolation(final StringInterpolationNode node) {
      final var parts = node.parts();
      if (parts.isEmpty()) {
        chunk().emitOpShort(OpCode.CONST_STR, pool.addString(""));
        return null;
      }

      final boolean textual = parts.get(0) instanceof LiteralNode.StringLiteral;

      if (!textual) {
        chunk().emitOpShort(OpCode.CONST_STR, pool.addString(""));
      }

      parts.get(0).accept(BytecodeCompiler.this);

      if (!textual) {
        chunk().emitOpcode(OpCode.ADD);
      }

      for (int i = 1; i < parts.size(); i++) {
        parts.get(i).accept(BytecodeCompiler.this);
        chunk().emitOpcode(OpCode.ADD);
      }

      return null;
    }

    @Override
    public Void visitBinaryExpression(final BinaryExpressionNode node) {
      final var op = node.operator();

      if ("||".equals(op)) {
        node.left().accept(BytecodeCompiler.this);
        chunk().emitOpcode(OpCode.DUP);
        final var jump = chunk().emitJumpPlaceholder(OpCode.JUMP_TRUE);
        chunk().emitOpcode(OpCode.POP);
        node.right().accept(BytecodeCompiler.this);
        final var end = chunk().position();
        chunk().patch(jump, (end - (jump + 2)) & 0xFFFF);
        return null;
      }

      if ("&&".equals(op)) {
        node.left().accept(BytecodeCompiler.this);
        chunk().emitOpcode(OpCode.DUP);
        final var jump = chunk().emitJumpPlaceholder(OpCode.JUMP_FALSE);
        chunk().emitOpcode(OpCode.POP);
        node.right().accept(BytecodeCompiler.this);
        final var end = chunk().position();
        chunk().patch(jump, (end - (jump + 2)) & 0xFFFF);
        return null;
      }

      node.left().accept(BytecodeCompiler.this);
      node.right().accept(BytecodeCompiler.this);

      switch (op) {
        case "+" -> chunk().emitOpcode(OpCode.ADD);
        case "-" -> chunk().emitOpcode(OpCode.SUB);
        case "*" -> chunk().emitOpcode(OpCode.MUL);
        case "/" -> chunk().emitOpcode(OpCode.DIV);
        case "%" -> chunk().emitOpcode(OpCode.MOD);
        case "==" -> chunk().emitOpcode(OpCode.CMP_EQ);
        case "!=" -> chunk().emitOpcode(OpCode.CMP_NE);
        case "<" -> chunk().emitOpcode(OpCode.CMP_LT);
        case "<=" -> chunk().emitOpcode(OpCode.CMP_LE);
        case ">" -> chunk().emitOpcode(OpCode.CMP_GT);
        case ">=" -> chunk().emitOpcode(OpCode.CMP_GE);
        case "in" -> chunk().emitOpcode(OpCode.IN_CHECK);
        case "&" -> chunk().emitOpcode(OpCode.BIT_AND);
        case "|" -> chunk().emitOpcode(OpCode.BIT_OR);
        case "^" -> chunk().emitOpcode(OpCode.BIT_XOR);
        case "<<" -> chunk().emitOpcode(OpCode.BIT_SHL);
        case ">>" -> chunk().emitOpcode(OpCode.BIT_SHR);
        default -> throw new BytecodeException("Unknown binary operator: " + op);
      }

      return null;
    }

    @Override
    public Void visitUnaryExpression(final UnaryExpressionNode node) {
      node.operand().accept(BytecodeCompiler.this);
      return switch (node.operator()) {
        case "-" -> {
          chunk().emitOpcode(OpCode.NEG);
          yield null;
        }
        case "!" -> {
          chunk().emitOpcode(OpCode.NOT);
          yield null;
        }
        case "~" -> {
          chunk().emitOpcode(OpCode.BIT_NOT);
          yield null;
        }
        default -> throw new BytecodeException("Unknown unary operator: " + node.operator());
      };
    }

    @Override
    public Void visitIfExpression(final IfExpressionNode node) {
      node.condition().accept(BytecodeCompiler.this);
      final var branch = chunk().emitJumpPlaceholder(OpCode.JUMP_FALSE);
      node.then().accept(BytecodeCompiler.this);
      final var skip = chunk().emitJumpPlaceholder(OpCode.JUMP);
      final var start = chunk().position();
      chunk().patch(branch, (start - (branch + 2)) & 0xFFFF);
      if (node.hasOtherwise()) {
        node.otherwise().accept(BytecodeCompiler.this);
      } else {
        chunk().emitOpcode(OpCode.PUSH_VOID);
      }
      final var end = chunk().position();
      chunk().patch(skip, (end - (skip + 2)) & 0xFFFF);
      return null;
    }

    @Override
    public Void visitCaseExpression(final CaseExpressionNode node) {
      caseCompiler().compile(node);
      return null;
    }

    @Override
    public Void visitFunctionCall(final FunctionCallNode node) {
      final var name = node.name();
      final var args = node.arguments();

      if (node.hasPrefix()) {
        final var prefix = node.prefix();
        if (module() == null || !module().equals(prefix)) {
          final var target = registry != null ? registry.function(prefix, name) : null;
          if (target == null || !target.isPublic()) {
            // Qualified enum variant construction (mod:Ok(...)). Before erroring, check if
            // the prefixed module exports a matching variant.
            final var qualifiedEnum = qualifiedVariantEnum(prefix, name);
            if (qualifiedEnum != null) {
              final var type = module.enumeration(qualifiedEnum);
              final var info = module.enumeration(type);
              final var tag = info.getVariantIndex(name);
              for (final var arg : args) {
                arg.accept(BytecodeCompiler.this);
              }
              chunk().emitOpcode(OpCode.NEW_ENUM);
              chunk().emitShort(type);
              chunk().emitShort(tag);
              chunk().emitByte(args.size());
              return null;
            }
            // Qualified module-owned builtin with no SAFE trampoline (e.g. std:range):
            // emit a builtin CALL, mirroring the unqualified builtin path below.
            if (BuiltinRegistry.isBuiltin(name) && BuiltinRegistry.module(name).equals(prefix)) {
              for (final var arg : args) {
                arg.accept(BytecodeCompiler.this);
              }
              final var builtinIndex = pool.addName(name);
              chunk().emitOpcode(OpCode.CALL);
              chunk().emitShort(builtinIndex);
              chunk().emitByte(args.size());
              return null;
            }
            throw new BytecodeException("Undefined or private function: " + prefix + "." + name);
          }
        }
        final var mangled = prefix + "$" + name;
        final var match = indices.get(mangled);
        if (match != null) {
          for (final var arg : args) {
            arg.accept(BytecodeCompiler.this);
          }
          final var declaration = declarations.get(mangled);
          var argc = args.size();
          if (declaration != null) {
            final var params = declaration.parameters();
            for (int i = args.size(); i < params.size(); i++) {
              if (params.get(i).hasDefault()) {
                chunk().emitOpcode(OpCode.PUSH_VOID);
                argc++;
              }
            }
          }
          final var index = pool.addName(mangled);
          chunk().emitOpcode(OpCode.CALL);
          chunk().emitShort(index);
          chunk().emitByte(argc);
          return null;
        }
        throw new BytecodeException("Undefined function: " + prefix + "." + name);
      }

      if (BuiltinRegistry.isBuiltin(name)) {
        if (module() == null) {
          final var found = indices.get(name);
          if (found == null) {
            throw new BytecodeException(
                "Built-in '"
                    + name
                    + "' requires import. Use: import "
                    + BuiltinRegistry.module(name)
                    + ";");
          }
        } else {
          for (final var arg : args) {
            arg.accept(BytecodeCompiler.this);
          }
          final var index = pool.addName(name);
          chunk().emitOpcode(OpCode.CALL);
          chunk().emitShort(index);
          chunk().emitByte(args.size());
          return null;
        }
      }

      final var info = module.variant(name);
      if (info != null) {
        final var type = module.enumeration(info.name());
        final var tag = info.getVariantIndex(name);
        for (final var arg : args) {
          arg.accept(BytecodeCompiler.this);
        }
        chunk().emitOpcode(OpCode.NEW_ENUM);
        chunk().emitShort(type);
        chunk().emitShort(tag);
        chunk().emitByte(args.size());
        return null;
      }

      var resolved = name;
      Integer match = indices.get(name);
      if (match == null && module() != null) {
        resolved = module() + "$" + name;
        match = indices.get(resolved);
      }
      if (match == null) {
        final var slot = slots().get(name);
        if (slot != null) {
          chunk().emitOpShort(OpCode.LOAD_LOCAL, slot);
          for (final var arg : args) {
            arg.accept(BytecodeCompiler.this);
          }
          chunk().emitOpcode(OpCode.CALL_VALUE);
          chunk().emitByte(args.size());
          return null;
        }
        final var global = names.get(name);
        if (global != null) {
          final var index = pool.addName(name);
          chunk().emitOpShort(OpCode.LOAD_GLOBAL, index);
          for (final var arg : args) {
            arg.accept(BytecodeCompiler.this);
          }
          chunk().emitOpcode(OpCode.CALL_VALUE);
          chunk().emitByte(args.size());
          return null;
        }
        throw new BytecodeException("Undefined function: " + name);
      }

      for (final var arg : args) {
        arg.accept(BytecodeCompiler.this);
      }

      final var declaration = declarations.get(resolved);
      final var index = pool.addName(resolved);
      if (declaration != null) {
        final var params = declaration.parameters();
        for (int i = args.size(); i < params.size(); i++) {
          if (params.get(i).hasDefault()) {
            chunk().emitOpcode(OpCode.PUSH_VOID);
          }
        }
        chunk().emitOpcode(OpCode.CALL);
        chunk().emitShort(index);
        chunk().emitByte(params.size());
      } else {
        chunk().emitOpcode(OpCode.CALL);
        chunk().emitShort(index);
        chunk().emitByte(args.size());
      }
      return null;
    }

    @Override
    public Void visitVariableReference(final VariableReferenceNode node) {
      final var parts = node.parts();
      final var first = parts.get(0);

      if (node.hasPrefix() && registry != null && registry.has(node.prefix())) {
        final var mangled = node.prefix() + "$" + parts.get(0);
        final var index = pool.addName(mangled);
        chunk().emitOpShort(OpCode.LOAD_GLOBAL, index);
        for (int i = 1; i < parts.size(); i++) {
          chunk().emitOpShort(OpCode.GET_FIELD, pool.addName(parts.get(i)));
        }
        return null;
      }

      if (parts.size() >= 2 && registry != null && registry.has(first)) {
        final var mangled = first + "$" + parts.get(1);
        final var index = pool.addName(mangled);
        chunk().emitOpShort(OpCode.LOAD_GLOBAL, index);
        for (int i = 2; i < parts.size(); i++) {
          chunk().emitOpShort(OpCode.GET_FIELD, pool.addName(parts.get(i)));
        }
        return null;
      }

      final var slot = slots().get(first);
      if (slot != null) {
        chunk().emitOpShort(OpCode.LOAD_LOCAL, slot);
      } else if (parts.size() == 1 && !names.containsKey(first) && indices.containsKey(first)) {
        chunk().emitOpcode(OpCode.CLOSURE);
        chunk().emitShort(indices.get(first));
        chunk().emitByte(0);
      } else if (parts.size() == 1
          && !names.containsKey(first)
          && module() != null
          && indices.containsKey(module() + "$" + first)) {
        chunk().emitOpcode(OpCode.CLOSURE);
        chunk().emitShort(indices.get(module() + "$" + first));
        chunk().emitByte(0);
      } else {
        String resolved = first;
        if (module() != null && names.containsKey(module() + "$" + first)) {
          resolved = module() + "$" + first;
        }
        final var index = pool.addName(resolved);
        chunk().emitOpShort(OpCode.LOAD_GLOBAL, index);
      }

      for (int i = 1; i < parts.size(); i++) {
        chunk().emitOpShort(OpCode.GET_FIELD, pool.addName(parts.get(i)));
      }

      return null;
    }

    @Override
    public Void visitFieldAccess(final FieldAccessNode node) {
      node.receiver().accept(BytecodeCompiler.this);
      chunk().emitOpShort(OpCode.GET_FIELD, pool.addName(node.field()));
      return null;
    }

    @Override
    public Void visitObjectCreation(final ObjectCreationNode node) {
      final var type = node.type();
      final var index = module.type(type);
      if (index == -1) {
        throw new BytecodeException("Undefined type: " + type);
      }
      for (final var fa : node.fields()) {
        chunk().emitOpShort(OpCode.CONST_STR, pool.addString(fa.field()));
        fa.value().accept(BytecodeCompiler.this);
      }
      chunk().emitOpcode(OpCode.NEW_OBJECT);
      chunk().emitShort(index);
      chunk().emitByte(node.fields().size());
      return null;
    }

    @Override
    public Void visitLiteral(final LiteralNode node) {
      switch (node) {
        case LiteralNode.IntLiteral i ->
            chunk().emitOpShort(OpCode.CONST_INT, pool.addInt(i.value()));
        case LiteralNode.UintLiteral u ->
            chunk().emitOpShort(OpCode.CONST_UINT, pool.addInt(u.value()));
        case LiteralNode.FloatLiteral f ->
            chunk().emitOpShort(OpCode.CONST_FLOAT, pool.addFloat(f.value()));
        case LiteralNode.StringLiteral s ->
            chunk().emitOpShort(OpCode.CONST_STR, pool.addString(s.value()));
        case LiteralNode.BoolLiteral b ->
            chunk().emitOpcode(b.value() ? OpCode.PUSH_TRUE : OpCode.PUSH_FALSE);
      }
      return null;
    }
  }
}
