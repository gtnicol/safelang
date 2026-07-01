package io.safelang.compiler.c;

import io.safelang.ModuleRegistry;
import io.safelang.analyzer.ImportResolver;
import io.safelang.ast.*;
import io.safelang.runtime.SAFEValue;
import io.safelang.runtime.StringEscapes;
import java.util.*;
import java.util.stream.Collectors;

/**
 * C Code Generator for the SAFE programming language. Implements ASTVisitor<String> to generate
 * compilable C code from the AST.
 *
 * <p>The generated C code includes a reference to safe_runtime.h (header-only runtime) and flattens
 * module boundaries at compile time using name mangling.
 */
public class CCodeGenerator implements ASTVisitor<String> {

  private final CNameMangler mangler;
  private final CTypeMapper mapper;
  private final CTypeInferer inferer;
  private final CEnumGenerator enums;
  private final CBuiltinResolver builtins;
  private final CCollectionEmitter collections;
  private final CForCompiler loops;
  private final CCaseCompiler cases;
  private final CIndexCompiler indexing;
  private final CCallCompiler calls;
  private final CBoxing boxing;
  private final Set<String> emitted;
  private final Set<String> declared;
  private final Map<String, TypeDeclarationNode> structs;
  private final Map<String, FunctionDeclarationNode> functions;
  private final Set<String> imported = new HashSet<>();
  private final Set<String> modules;
  private final List<String> initializers;
  private final List<String> definitions;
  private final List<String> wrappers;
  private final Map<LambdaNode, String> closures;
  private final Map<LambdaNode, List<String>> captures;
  private final Map<String, EnumDeclarationNode> enumerations;
  private final Set<String> recursive;
  private final io.safelang.compiler.refcount.RefcountPolicy rc;
  private final Deque<Frame> frames = new ArrayDeque<>();
  // Recursive value→string helpers generated on demand (one per compound type).
  private final Map<String, String> stringifiers = new HashMap<>();
  private final List<String> stringifierDecls = new ArrayList<>();
  private final List<String> stringifierDefs = new ArrayList<>();
  private StringBuilder output;
  private int indent;
  private ModuleRegistry registry;
  private int counter;
  private Map<String, String> aliases;

  // Builtin-arg ownership convention (see gatedResolve). A FRESH heap value passed straight to a
  // borrowing builtin (`sort(keys(m))`) leaks the throwaway, because builtins are C functions
  // outside the Step-5 param discipline. We auto-release such an arg after the call — but only in a
  // provably-safe double-gated case: the arg's PRODUCER returns an *untyped* list (structure-only
  // disposal; elements are arena copies or owned elsewhere, never freed by the release) AND the
  // CONSUMER only borrows the arg (never stores/consumes it). Both sets are deliberately small; an
  // arg that fails either gate is left as-is (a leak, never a use-after-free). `values` is excluded
  // (its `_ptr` variant returns a typed, retaining list — releasing it can dangle a borrowed
  // alias).
  private static final Set<String> DISPOSABLE_PRODUCERS =
      Set.of("keys", "sort", "slice", "reverse");
  private static final Set<String> BORROW_CONSUMERS =
      Set.of(
          "sort",
          "size",
          "contains",
          "reverse",
          "slice",
          "unique",
          "count",
          "any",
          "all",
          "sum",
          "join",
          "minimum",
          "maximum");
  private final Map<ASTNode, String> emitOverrides = new IdentityHashMap<>();
  // AOT capability set, fixed at build time. Default grants everything (trusted local build); a
  // restricted build (--deny/--allow) refuses to emit a builtin whose capability is not granted.
  private io.safelang.runtime.Capabilities capabilities = io.safelang.runtime.Capabilities.all();

  public CCodeGenerator() {
    this.output = new StringBuilder();
    this.indent = 0;
    this.mangler = new CNameMangler();
    this.emitted = new HashSet<>();
    this.declared = new HashSet<>();
    this.structs = new HashMap<>();
    this.modules = new HashSet<>();
    this.initializers = new ArrayList<>();
    this.functions = new HashMap<>();
    this.counter = 0;
    this.definitions = new ArrayList<>();
    this.wrappers = new ArrayList<>();
    this.closures = new IdentityHashMap<>();
    this.captures = new IdentityHashMap<>();
    this.enumerations = new HashMap<>();
    this.recursive = new HashSet<>();
    this.rc = new io.safelang.compiler.refcount.RefcountPolicy(recursive, structs);
    this.mapper = new CTypeMapper(recursive, enumerations);
    this.aliases = Map.of();
    this.inferer = new CTypeInferer(new InferAdapter());
    this.enums = new CEnumGenerator(new EnumAdapter());
    this.builtins = new CBuiltinResolver(new BuiltinAdapter());
    this.collections = new CCollectionEmitter(new CollectionAdapter());
    this.loops = new CForCompiler(new ForAdapter());
    this.cases = new CCaseCompiler(new CaseAdapter());
    this.indexing = new CIndexCompiler(new IndexAdapter());
    this.calls = new CCallCompiler(new CallAdapter());
    this.boxing = new CBoxing(new BoxingAdapter());
  }

  private boolean scoped() {
    return !frames.isEmpty() && frames.peek().scoped;
  }

  private FunctionDeclarationNode currentFunction() {
    return frames.isEmpty() ? null : frames.peek().function;
  }

  private String currentFunctionCName() {
    return frames.isEmpty() ? null : frames.peek().name;
  }

  private Map<String, String> variables() {
    // Unlike scoped()/currentFunction()/currentModule() — which all tolerate
    // an empty frame stack and return null/false — every call site that
    // reaches this method writes to the result via .put(...), so a missing
    // frame would NPE. Fail loudly with a clear message instead.
    if (frames.isEmpty()) {
      throw new IllegalStateException("CCodeGenerator: variables() called with no active frame");
    }
    return frames.peek().variables;
  }

  private String currentModule() {
    return frames.isEmpty() ? null : frames.peek().module;
  }

  public void setRegistry(final ModuleRegistry registry) {
    this.registry = registry;
  }

  public void setCapabilities(final io.safelang.runtime.Capabilities capabilities) {
    if (capabilities != null) {
      this.capabilities = capabilities;
    }
  }

  /** Refuse, at compile time, to emit a builtin whose host capability the build did not grant. */
  private String gatedResolve(final String name, final List<ASTNode> arguments) {
    final var capability = io.safelang.runtime.BuiltinRegistry.capability(name);
    if (capability != null && !capabilities.granted(capability)) {
      throw new io.safelang.SAFEException(
          "builtin '"
              + name
              + "' requires capability "
              + capability
              + ", which this build did not grant; rebuild with --allow "
              + capability.name().toLowerCase());
    }
    if (!io.safelang.runtime.BuiltinRegistry.isBuiltin(name) || !BORROW_CONSUMERS.contains(name)) {
      return builtins.resolve(name, arguments);
    }
    return resolveReleasing(name, arguments);
  }

  /**
   * Resolve a borrow-only builtin call, releasing any FRESH untyped-list argument after the call so
   * the throwaway does not leak. Each disposable arg is bound to a temp (the builtin call is
   * rewritten to reference the temp via {@link #emitOverrides}); the whole call becomes a GCC
   * statement expression that evaluates the builtin once, releases the temps, and yields the
   * result.
   */
  private String resolveReleasing(final String name, final List<ASTNode> arguments) {
    final var releasable = new ArrayList<ASTNode>();
    for (final var arg : arguments) {
      if (disposableListArg(arg)) releasable.add(arg);
    }
    if (releasable.isEmpty()) return builtins.resolve(name, arguments);

    final var inits = new StringBuilder();
    final var temps = new ArrayList<String>();
    for (final var arg : releasable) {
      final var code = arg.accept(this); // emit BEFORE the override so it is the real expression
      final var temp = "__ba_" + counter++ + "__";
      temps.add(temp);
      inits.append("SAFEList* ").append(temp).append(" = ").append(code).append("; ");
      emitOverrides.put(arg, temp);
    }
    final String call;
    try {
      call = builtins.resolve(name, arguments); // re-emits each releasable arg as its temp
    } finally {
      for (final var arg : releasable) emitOverrides.remove(arg);
    }
    if (call == null) return null; // arg types declined the builtin — fall through, temps discarded
    final var releases = new StringBuilder();
    for (final var temp : temps) releases.append("safe_release(").append(temp).append("); ");
    return "({ "
        + inits
        + "__typeof__("
        + call
        + ") __bret__ = ("
        + call
        + "); "
        + releases
        + "__bret__; })";
  }

  /**
   * True when {@code arg} is a call to a builtin that produces a fresh UNTYPED list (structure-only
   * disposal) — safe to release after a borrowing consumer. A user function cannot reach here: a
   * name that {@code isBuiltin} always resolves to the builtin (builtins take call priority).
   */
  private boolean disposableListArg(final ASTNode arg) {
    if (!(arg instanceof FunctionCallNode call)) return false;
    if (!DISPOSABLE_PRODUCERS.contains(call.name())) return false;
    final var type = infer(arg);
    return type != null && type.startsWith("list<");
  }

  private String mangle(final String module, final String name) {
    return mangler.module(module, name);
  }

  private CLambdaCompiler lambdaCompiler() {
    return new CLambdaCompiler(
        new CLambdaContext() {
          @Override
          public String user(final String name) {
            return mangler.user(name);
          }

          @Override
          public String retainStructFields(final String lvalue, final String type) {
            return CCodeGenerator.this.retainStructFields(lvalue, type);
          }

          @Override
          public boolean has(final LambdaNode node) {
            return closures.containsKey(node);
          }

          @Override
          public String name(final LambdaNode node) {
            return closures.get(node);
          }

          @Override
          public void name(final LambdaNode node, final String name) {
            closures.put(node, name);
          }

          @Override
          public List<String> captures(final LambdaNode node) {
            return captures.getOrDefault(node, List.of());
          }

          @Override
          public void captures(final LambdaNode node, final List<String> names) {
            captures.put(node, names);
          }

          @Override
          public String next() {
            return "__lambda_" + counter++;
          }

          @Override
          public Map<String, String> variables() {
            return CCodeGenerator.this.variables();
          }

          @Override
          public String translate(final String type) {
            return CCodeGenerator.this.translate(type);
          }

          @Override
          public boolean struct(final String type) {
            return structs.containsKey(type);
          }

          @Override
          public boolean enumeration(final String type) {
            return enumerations.containsKey(type);
          }

          @Override
          public boolean isHeapRc(final String type) {
            return CCodeGenerator.this.isHeapRc(type);
          }

          @Override
          public String infer(final ASTNode node) {
            return CCodeGenerator.this.infer(node);
          }

          @Override
          public String body(final LambdaNode node) {
            frames.push(
                new Frame(
                    true,
                    currentFunction(),
                    currentFunctionCName(),
                    new HashMap<>(variables()),
                    currentModule()));
            try {
              for (final var param : node.parameters()) {
                if (param.type() != null) {
                  variables().put(param.name(), param.type().fullName());
                }
              }
              return node.body().accept(CCodeGenerator.this);
            } finally {
              frames.pop();
            }
          }

          @Override
          public void define(final String code) {
            definitions.add(code);
          }

          @Override
          public void indent(final StringBuilder builder) {
            CCodeGenerator.this.indent++;
            CCodeGenerator.this.indent(builder);
            CCodeGenerator.this.indent--;
          }

          @Override
          public void pad(final StringBuilder builder) {
            CCodeGenerator.this.indent(builder);
          }
        });
  }

  private CFormatResolver formatResolver() {
    return new CFormatResolver(
        new CFormatContext() {
          @Override
          public String infer(final ASTNode node) {
            return CCodeGenerator.this.infer(node);
          }

          @Override
          public boolean stringlike(final ASTNode node) {
            return CCodeGenerator.this.isStringLike(node);
          }

          @Override
          public Map<String, String> variables() {
            return CCodeGenerator.this.variables();
          }

          @Override
          public FunctionDeclarationNode function(final String name) {
            return functions != null ? functions.get(name) : null;
          }
        });
  }

  /** Generate complete C code from a program AST. */
  public String generate(final ProgramNode program) {
    output = new StringBuilder();
    indent = 0;
    emitted.clear();
    declared.clear();
    structs.clear();
    initializers.clear();
    functions.clear();
    counter = 0;
    definitions.clear();
    wrappers.clear();
    closures.clear();
    captures.clear();
    enumerations.clear();
    recursive.clear();
    modules.clear();
    imported.clear();
    aliases = Map.of();
    frames.clear();
    frames.push(new Frame(false, null, null, new HashMap<>(), null));

    // Collect type, enum, and function declarations for mapping
    for (final ASTNode declaration : program.declarations()) {
      switch (declaration) {
        case TypeDeclarationNode struct -> structs.put(struct.name(), struct);
        case EnumDeclarationNode definition -> enumerations.put(definition.name(), definition);
        case FunctionDeclarationNode function -> functions.put(function.name(), function);
        default -> {}
      }
    }

    // Generate include for runtime header. The feature placeholder is replaced at the end with
    // #define markers (SAFE_ENABLE_HTTP / SAFE_ENABLE_TLS) gating the optional libcurl/OpenSSL
    // sections of safe_runtime.h, derived from a scan of the emitted body.
    line(FEATURE_DEFINES_MARKER);
    line("#include \"safe_runtime.h\"");
    line("");

    // Narrow warning suppressions for generated code. These are the two
    // categories the codegen currently produces intentionally:
    //   -Wint-conversion / -Wincompatible-pointer-types — lambda bodies and
    //   Option/Result variant constructors pass heap-boxed scalars through a
    //   generic void* shape. Fixing at the source requires a tagged lambda
    //   ABI and parameterised variant constructors (tracked as codegen debt,
    //   not blocking).
    // Emitted as a pragma block here rather than a CLI flag so any C
    // compiler invoked on these outputs (incl. third-party inspection) sees
    // the justification and the scope stays local to generated code.
    line("#if defined(__clang__)");
    line("#pragma clang diagnostic ignored \"-Wint-conversion\"");
    line("#pragma clang diagnostic ignored \"-Wincompatible-pointer-types\"");
    line("#elif defined(__GNUC__)");
    line("#pragma GCC diagnostic ignored \"-Wint-conversion\"");
    line("#pragma GCC diagnostic ignored \"-Wincompatible-pointer-types\"");
    line("#endif");
    line("");

    // System globals (matching BuiltinRegistry.variables). VERSION is a language constant;
    // MAX_* are fixed compile-time limits. OS/ARCH/OS_VERSION/PLATFORM are host state — emit
    // macros that expand to runtime helpers defined in safe_runtime.h so a binary reflects the
    // machine it runs on, matching interpreter/bytecode behavior.
    line("const char* VERSION = \"1.0\";");
    line("const int64_t MAX_LIST_SIZE = " + SAFEValue.MAX_LIST_SIZE + ";");
    line("const int64_t MAX_TUPLE_SIZE = " + SAFEValue.MAX_TUPLE_SIZE + ";");
    variables().put("VERSION", "string");
    variables().put("MAX_LIST_SIZE", "int");
    variables().put("MAX_TUPLE_SIZE", "int");
    // Host-dependent globals require ENVIRONMENT — only emit them when granted, so a restricted
    // build refuses to expose host state (a reference then fails to compile, matching the AOT
    // builtin gate). VERSION/MAX_* are deterministic constants and stay ungated.
    if (capabilities.granted(io.safelang.runtime.Capability.ENVIRONMENT)) {
      line("#define OS safe_os()");
      line("#define ARCH safe_arch()");
      line("#define OS_VERSION safe_osversion()");
      line("#define PLATFORM safe_platform()");
      variables().put("PLATFORM", "string");
      variables().put("OS", "string");
      variables().put("ARCH", "string");
      variables().put("OS_VERSION", "string");
    }
    line("");

    // Phase 0: Flatten imported modules
    if (registry != null) {
      // Build selective import filter: module name -> set of allowed symbols (null means import
      // all). Non-selective imports grant full access; selective imports compose additively.
      final var selective = ImportResolver.fold(program.imports());

      for (final var name : registry.modules()) {
        modules.add(name);
        final var module = registry.program(name);
        final var allowed = selective.get(name);

        // Register module types and enums
        for (final var declaration : module.declarations()) {
          if (declaration instanceof TypeDeclarationNode type) {
            if (type.isPublic()) {
              // Always compile struct types — they may be needed by builtin return values
              structs.put(type.name(), type);
              line(declaration.accept(this));
              line("");
            }
          } else if (declaration instanceof EnumDeclarationNode definition) {
            // Always compile enum types (public + private) — needed by module internals
            enumerations.put(definition.name(), definition);
            line(declaration.accept(this));
            line("");
          }
        }

        // Forward declare all module functions
        // Private helpers always pass through — only filter public functions
        for (final var declaration : module.declarations()) {
          if (declaration instanceof FunctionDeclarationNode function) {
            if (allowed != null && function.isPublic() && !allowed.contains(function.name()))
              continue;
            final var mangled = mangle(name, function.name());
            forward(function, mangled);
            emitted.add(mangled);
            functions.put(mangled, function);
            functions.put(name + ":" + function.name(), function);
          }
        }

        // Pre-register and declare module constants before function bodies
        for (final var declaration : module.declarations()) {
          if (declaration instanceof VariableDeclarationNode variable) {
            if (allowed != null && !allowed.contains(variable.name())) continue;
            final var mangled = mangle(name, variable.name());
            variables().put(mangled, variable.type().fullName());
            final var type = translate(variable.type().fullName());
            line(type + " " + mangled + ";");
            if (variable.hasInitializer()) {
              initializers.add(mangled + " = " + variable.initializer().accept(this) + ";");
            }
          }
        }
        for (final var statement : module.statements()) {
          if (statement instanceof VariableDeclarationNode variable) {
            if (allowed != null && !allowed.contains(variable.name())) continue;
            final var mangled = mangle(name, variable.name());
            variables().put(mangled, variable.type().fullName());
            final var type = translate(variable.type().fullName());
            line(type + " " + mangled + ";");
            if (variable.isConstant() && variable.hasInitializer()) {
              initializers.add(mangled + " = " + variable.initializer().accept(this) + ";");
            } else if (!variable.isConstant()) {
              if (variable.hasInitializer()) {
                initializers.add(mangled + " = " + variable.initializer().accept(this) + ";");
              }
            }
          }
        }

        // Declare decreases stacks for module functions
        for (final var declaration : module.declarations()) {
          if (declaration instanceof FunctionDeclarationNode function
              && function.decreases() != null) {
            if (allowed != null && function.isPublic() && !allowed.contains(function.name()))
              continue;
            final var mangled = mangle(name, function.name());
            line("static SAFEDecreasesStack __decreases_stack_" + mangled + " = { .sp = 0 };");
          }
        }

        // Compile module function bodies
        frames.peek().module = name;
        imported.clear();
        for (final var declaration : module.declarations()) {
          if (declaration instanceof EnumDeclarationNode definition) {
            imported.add(definition.name());
          }
        }
        for (final var declaration : module.declarations()) {
          if (declaration instanceof FunctionDeclarationNode function) {
            if (allowed != null && function.isPublic() && !allowed.contains(function.name()))
              continue;
            final var mangled = mangle(name, function.name());
            definition(function, mangled);
            line("");
          }
        }
        frames.peek().module = null;
        imported.clear();

        // (module constants already emitted above, before function bodies)
      }
      if (!modules.isEmpty()) {
        line("");
      }
    }

    // User-defined type and enum definitions (before forward declarations so types are known)
    for (final ASTNode declaration : program.declarations()) {
      if (declaration instanceof TypeDeclarationNode) {
        line(declaration.accept(this));
        line("");
      } else if (declaration instanceof EnumDeclarationNode) {
        line(declaration.accept(this));
        line("");
      }
    }

    // Forward declarations for user functions
    forwards(program);

    // Hoist program-level const/var declarations to file scope so functions can reference them
    final var initializations = new ArrayList<String>();
    for (final ASTNode declaration : program.declarations()) {
      if (declaration instanceof VariableDeclarationNode variable) {
        final var type = translate(variable.type() != null ? variable.type().fullName() : "int");
        final var name = variable.name();
        final var mangled = mangle(name);
        variables().put(name, variable.type() != null ? variable.type().fullName() : "int");
        line(type + " " + mangled + ";");
        if (variable.hasInitializer()) {
          initializations.add(mangled + " = " + variable.initializer().accept(this) + ";");
        }
      }
    }

    // Marker for lambda/wrapper definitions (spliced in after codegen)
    final var marker = "/* __LAMBDA_DEFS__ */";
    line(marker);

    // Function definitions
    for (final ASTNode declaration : program.declarations()) {
      if (declaration instanceof FunctionDeclarationNode) {
        line(declaration.accept(this));
        line("");
      }
    }

    // Check for const declarations in declarations block
    boolean initialized = !initializations.isEmpty();

    // Main function for top-level statements
    if (!program.statements().isEmpty() || !initializers.isEmpty() || initialized) {
      line("int main(int argc, char* argv[]) {");
      indent++;
      line("safe_init_args(argc, argv);");
      line("safe_init_policy_from_env();");

      // Module initialization code (const vars from modules)
      for (final var init : initializers) {
        line(init);
      }

      // Initialize program-level const/var declarations
      for (final var init : initializations) {
        line(init);
      }

      for (final ASTNode statement : program.statements()) {
        final var code = statement.accept(this);
        if (!code.trim().isEmpty()) {
          line(code);
        }
      }
      line("safe_collect_cycles();");
      line("safe_arena_free();");
      line("return 0;");
      indent--;
      line("}");
    }

    // Splice lambda, wrapper, and stringifier definitions at the marker. Stringifier forward
    // declarations come first so the generated helpers may reference one another (and recurse).
    final var defs = new StringBuilder();
    for (final var decl : stringifierDecls) {
      defs.append(decl).append("\n");
    }
    if (!stringifierDecls.isEmpty()) {
      defs.append("\n");
    }
    for (final var wrapper : wrappers) {
      defs.append(wrapper).append("\n\n");
    }
    for (final var lambda : definitions) {
      defs.append(lambda).append("\n\n");
    }
    for (final var stringifier : stringifierDefs) {
      defs.append(stringifier).append("\n\n");
    }
    final var spliced = output.toString().replace(marker + "\n", defs.toString());
    final var features = new StringBuilder();
    if (spliced.contains("safe_http_request(")) {
      features.append("#define SAFE_ENABLE_HTTP\n");
    }
    // Conservative: if either TLS serve trampoline is emitted, enable the OpenSSL section. Programs
    // wanting a libcurl-only / plaintext-only native build can import http selectively.
    if (spliced.contains("safe__http_serve_tls")
        || spliced.contains("safe__http_serve_until_tls")) {
      features.append("#define SAFE_ENABLE_TLS\n");
    }
    return spliced.replace(FEATURE_DEFINES_MARKER + "\n", features.toString());
  }

  private static final String FEATURE_DEFINES_MARKER = "/*__SAFE_FEATURE_DEFINES__*/";

  private void forward(final FunctionDeclarationNode function, final String name) {
    final var returns = translate(function.returns().fullName());
    final var builder = new StringBuilder();
    builder.append(returns).append(" ").append(name).append("(");

    final var params = function.parameters();
    for (int i = 0; i < params.size(); i++) {
      if (i > 0) builder.append(", ");
      final var param = params.get(i);
      builder.append(translate(param.type().fullName())).append(" ").append(mangle(param.name()));
    }
    builder.append(");");
    line(builder.toString());
  }

  private void definition(final FunctionDeclarationNode function, final String name) {
    line(body(function, name));
  }

  private String body(final FunctionDeclarationNode function, final String name) {
    final var builder = new StringBuilder();
    final var returns = translate(function.returns().fullName());
    builder.append(returns).append(" ").append(name).append("(");

    final var params = function.parameters();
    for (int i = 0; i < params.size(); i++) {
      if (i > 0) builder.append(", ");
      final var param = params.get(i);
      builder.append(translate(param.type().fullName())).append(" ").append(mangle(param.name()));
    }
    builder.append(") {\n");

    indent++;
    frames.push(new Frame(true, function, name, new HashMap<>(variables()), currentModule()));
    try {
      // Track parameter types
      for (final var param : params) {
        variables().put(param.name(), param.type().fullName());
      }

      // Recursion depth guard
      indent(builder);
      builder.append("safe_check_recursion(\"").append(name).append("\");\n");

      if (function.hasRequires()) {
        indent(builder);
        builder.append("if (!(");
        builder.append(function.requires().accept(this));
        builder.append(")) { fprintf(stderr, \"Precondition failed\\n\"); exit(1); }\n");
      }

      // Decreases clause runtime check (stack-based for reentrant safety)
      if (function.hasDecreases()) {
        indent(builder);
        builder.append("int64_t __decreases_curr = ");
        builder.append(function.decreases().accept(this));
        builder.append(";\n");
        indent(builder);
        builder
            .append(
                "if (__decreases_curr < 0) { fprintf(stderr, \"Decreases measure must be non-negative for: ")
            .append(name)
            .append("\\n\"); exit(1); }\n");
        indent(builder);
        builder
            .append("if (__decreases_stack_")
            .append(name)
            .append(".sp > 0 && __decreases_curr >= __decreases_stack_")
            .append(name)
            .append(".values[__decreases_stack_")
            .append(name)
            .append(".sp - 1]) { fprintf(stderr, \"Decreases clause not satisfied for: ")
            .append(name)
            .append("\\n\"); exit(1); }\n");
        indent(builder);
        builder
            .append("safe_check_decreases_push(&__decreases_stack_")
            .append(name)
            .append(", __decreases_curr, \"")
            .append(name)
            .append("\");\n");
      }

      // NaN guard for float parameters (matches interpreter rejectNaN behavior)
      for (final var param : params) {
        final var type = param.type().fullName();
        if ("float".equals(type)
            || (param.type().isUnion()
                && param.type().members() != null
                && param.type().members().stream().anyMatch(m -> "float".equals(m.name())))) {
          indent(builder);
          builder
              .append("if (isnan((double)")
              .append(mangle(param.name()))
              .append(")) { fprintf(stderr, \"NaN is not allowed as an argument to function '")
              .append(name)
              .append("'\\n\"); exit(1); }\n");
        }
      }

      // Declare result variable for ensures contract
      if (function.hasEnsures()) {
        if (!"void".equals(function.returns().fullName())) {
          indent(builder);
          builder.append(translate(function.returns().fullName())).append(" result;\n");
          variables().put("result", function.returns().fullName());
        } else {
          // Void functions: declare a sentinel so ensures referencing result compiles
          indent(builder);
          builder.append("int64_t result = 0;\n");
          variables().put("result", "int");
        }
      }

      for (final ASTNode statement : function.body()) {
        final var code = statement.accept(this);
        if (!code.trim().isEmpty()) {
          indent(builder);
          builder.append(code).append("\n");
        }
      }

      // Phase 7b-5: release heap-RC locals declared at function body top
      // level, before the fall-through return path. Locals declared
      // inside nested for/while loops are already released by those
      // scope-exit emitters.
      for (final var stmt : function.body()) {
        if (stmt instanceof VariableDeclarationNode decl) {
          final var dtype = decl.type().fullName();
          if (isHeapRc(dtype)) {
            indent(builder);
            builder.append("safe_release(").append(mangle(decl.name())).append(");\n");
          } else {
            releaseTupleElements(builder, mangle(decl.name()), dtype);
          }
        }
      }

      // Phase 7b-1: release heap fields of struct parameters at function
      // exit (fall-through path). Paired with caller-side retain at the
      // call site — balances C's implicit struct-copy on arg pass.
      emitParamStructFieldReleases(builder, params);

      // Decrement recursion depth on fallthrough
      indent(builder);
      builder.append("__safe_recursion_depth--;\n");

      if (function.hasEnsures()) {
        indent(builder);
        builder.append("if (!(");
        builder.append(function.ensures().accept(this));
        builder.append(")) { fprintf(stderr, \"Postcondition failed\\n\"); exit(1); }\n");
      }

      // Pop decreases stack after ensures (no explicit return), keeping the measure
      // active through the postcondition exactly as the interpreter does.
      if (function.hasDecreases()) {
        indent(builder);
        builder.append("__decreases_stack_").append(name).append(".sp--;\n");
      }
    } finally {
      frames.pop();
      indent--;
    }

    builder.append("}");
    return builder.toString();
  }

  private String mangle(final String name) {
    return mangler.user(name);
  }

  /** Mangle a user-defined function name; always prefixed (collision-proof against libc). */
  private String mangleFunction(final String name) {
    return mangler.function(name);
  }

  /**
   * Is the SAFE type one of the heap-allocated, refcounted value kinds that carries a SAFEHeader?
   * Scalars, strings (no header yet), and user struct/enum value types return false. Phase 3 uses
   * this to decide where retain/release calls need to be emitted.
   */
  boolean isHeapRc(final String type) {
    return rc.isHeap(type);
  }

  /**
   * Map a SAFE type name to the SAFE_KIND_* constant used in SAFEHeader.kind / .meta. Returns 0 for
   * scalar/value types, indicating "no refcount". Used by codegen to emit typed container
   * constructors so the runtime can retain-on-insert and release-on-dispose.
   */
  String safeKindOf(final String type) {
    return rc.kindOf(type);
  }

  /**
   * Emit release statements for a scope-ending local of the given name/type. Handles: - heap-RC
   * types: single safe_release on the body pointer. - user struct types with heap-RC fields:
   * per-field safe_release. Safe when paired with retain-on-struct-pass (callers retain struct
   * arg's heap fields before calling; function-exit releases params).
   */
  String releaseForLocal(final String localName, final String type) {
    if (type == null) return "";
    final var mangled = mangle(localName);
    if (isHeapRc(type)) {
      return "safe_release(" + mangled + ");\n";
    }
    final var struct = structs.get(type);
    if (struct == null) return "";
    final var out = new StringBuilder();
    for (final var field : struct.fields()) {
      if (isHeapRc(field.type().fullName())) {
        out.append("safe_release(")
            .append(mangled)
            .append(".")
            .append(mangle(field.name()))
            .append(");\n");
      }
    }
    return out.toString();
  }

  /**
   * Return a C expression that evaluates to the same value as {@code argCode} but, when the arg is
   * an aliased (variable-reference) struct with heap fields, retains each heap field before the
   * call. Uses a GCC statement expression to thread a temporary for the retain. Fresh args
   * (function call results, struct literals) pass through unchanged — ownership of their fresh refs
   * transfers into the callee's parameter.
   */
  /**
   * Emit release statements for heap-RC fields of struct-typed parameters. Called at function body
   * exit (fall-through) and before each return, mirroring the caller-side retain from {@link
   * #wrapStructArgForCall}.
   */
  private void emitParamStructFieldReleases(
      final StringBuilder builder, final List<ParameterNode> params) {
    emitParamReleases(builder, params, null);
  }

  /**
   * Release every heap parameter at a function exit (Step 5): heap FIELDS of struct params and the
   * whole pointer of non-struct heap params (bytes/list/map/set/fn). Skips {@code skipName} — the
   * variable being returned — so an identity passthrough (`return p;`) does not free the value it
   * yields. Paired with the caller-side retain of BORROWED heap args in {@link
   * #wrapStructArgForCall}.
   */
  private void emitParamReleases(
      final StringBuilder builder, final List<ParameterNode> params, final String skipName) {
    for (final var param : params) {
      if (skipName != null && skipName.equals(param.name())) continue;
      final var type = param.type().fullName();
      final var struct = structs.get(type);
      if (struct != null) {
        for (final var field : struct.fields()) {
          if (isHeapRc(field.type().fullName())) {
            indent(builder);
            builder
                .append("safe_release(")
                .append(mangle(param.name()))
                .append(".")
                .append(mangle(field.name()))
                .append(");\n");
          }
        }
      } else if (isHeapRc(type)) {
        indent(builder);
        builder.append("safe_release(").append(mangle(param.name())).append(");\n");
      }
    }
  }

  /**
   * Emit release statements for heap-RC body-level locals declared directly in the current
   * function's body (skipping any matching the returned variable name, which is being transferred
   * to the caller).
   */
  private void emitCurrentFunctionBodyLocalReleases(
      final StringBuilder builder, final String skipName) {
    if (currentFunction() == null) return;
    for (final var stmt : currentFunction().body()) {
      if (stmt instanceof VariableDeclarationNode decl) {
        if (skipName != null && skipName.equals(decl.name())) continue;
        final var dtype = decl.type().fullName();
        if (isHeapRc(dtype)) {
          indent(builder);
          builder.append("safe_release(").append(mangle(decl.name())).append(");\n");
        } else {
          releaseTupleElements(builder, mangle(decl.name()), dtype);
        }
      }
    }
  }

  /**
   * A tuple is an inline value struct ({@link SAFETuple}); release its heap-RC elements at scope
   * exit (mirroring {@code safe_dispose_tuple}). This balances the {@code safe_retain} a function
   * emits when packing heap elements into a returned tuple — without it a tuple local (e.g. {@code
   * (bytes, list<...>) loaded = f()}) leaks its element references. No-op for non-tuple types.
   */
  private void releaseTupleElements(
      final StringBuilder builder, final String mangled, final String type) {
    if (type == null || !type.startsWith("tuple<") || !type.endsWith(">")) return;
    final var elements = split(type.substring(6, type.length() - 1));
    for (int i = 0; i < elements.size(); i++) {
      if (isHeapRc(elements.get(i).trim())) {
        indent(builder);
        builder
            .append("safe_release(")
            .append(mangled)
            .append(".elements[")
            .append(i)
            .append("].ptr_val);\n");
      }
    }
  }

  /**
   * Emit {@code safe_retain(lvalue.fieldN); } for each heap-RC field of the struct. Returns empty
   * string if {@code type} isn't a struct or has no heap-RC fields.
   */
  String retainStructFields(final String lvalue, final String type) {
    if (type == null) return "";
    final var struct = structs.get(type);
    if (struct == null) return "";
    final var out = new StringBuilder();
    for (final var field : struct.fields()) {
      if (isHeapRc(field.type().fullName())) {
        out.append("safe_retain(")
            .append(lvalue)
            .append(".")
            .append(mangle(field.name()))
            .append("); ");
      }
    }
    return out.toString();
  }

  String wrapStructArgForCall(final String argCode, final ASTNode argNode) {
    if (argNode == null) return argCode;
    final var type = infer(argNode);
    if (type == null) return argCode;
    final var struct = structs.get(type);
    if (struct == null) {
      // Step 5 — non-struct heap arg (bytes/list/map/set/fn). The callee releases every heap param
      // at
      // exit, so BORROWED (aliased) args must be retained here to stay balanced; a FRESH producer
      // TRANSFERS its +1 into that release (no retain). The returned-param skip keeps an escaping
      // arg
      // alive, and a stored arg's own store-retain survives the param-release.
      if (isHeapRc(type) && !isFreshRhs(argNode)) {
        return "({ "
            + translate(type)
            + " __rc_arg__ = "
            + argCode
            + "; safe_retain(__rc_arg__); __rc_arg__; })";
      }
      return argCode;
    }
    final var retains = new StringBuilder();
    for (final var field : struct.fields()) {
      if (isHeapRc(field.type().fullName())) {
        retains.append("safe_retain(__rc_arg__.").append(mangle(field.name())).append("); ");
      }
    }
    if (retains.length() == 0) return argCode;
    // A struct LITERAL whose heap fields are all ALIASED (`flush(LSM { memtable: table, .. })`)
    // already
    // retained each field in visitObjectCreation (`.f = safe_retain(x)`), so the literal owns a
    // counted
    // ref the callee's param-release consumes. Wrapping it again here is a second, unbalanced
    // retain
    // that leaks the field — for lsm this pins the whole memtable every flush. Skip the wrap for
    // that
    // case: the field-init retain balances the callee release, and refs>=2 during the call still
    // forces
    // any append fast path onto the copy path, so there is no in-place-share use-after-free.
    //
    // The over-retain is KEPT for every other struct arg: an aliased struct VARIABLE (the retain
    // balances the callee release; the variable keeps its own ref) and — critically — a FRESH
    // FunctionCallNode struct (Phase 5.3: `enqueue(queue:create())`, where a refs==1 list field
    // mutated
    // in place by the append fast path would be freed by the callee's param-release, dangling the
    // returned struct) and a literal with any FRESH heap field.
    if (argNode instanceof ObjectCreationNode literal && allHeapFieldsAliased(literal)) {
      return argCode;
    }
    return "({ " + type + " __rc_arg__ = " + argCode + "; " + retains + "__rc_arg__; })";
  }

  /**
   * True if every heap-RC field initializer of a struct literal is ALIASED (borrowed), so
   * visitObjectCreation retained it and the literal already owns a counted ref. A FRESH heap field
   * (refs==1, transferred not retained) returns false — wrapping such a literal is still needed.
   */
  private boolean allHeapFieldsAliased(final ObjectCreationNode literal) {
    for (final var assignment : literal.fields()) {
      if (isHeapRc(infer(assignment.value())) && isFreshRhs(assignment.value())) {
        return false;
      }
    }
    return true;
  }

  private void forwards(final ProgramNode program) {
    for (final ASTNode declaration : program.declarations()) {
      if (declaration instanceof FunctionDeclarationNode function) {
        final var mangled = mangleFunction(function.name());
        forward(function, mangled);
        emitted.add(function.name());
        functions.put(function.name(), function);
      }
    }
    if (!emitted.isEmpty()) {
      line("");
    }
  }

  private String translate(final String type) {
    return mapper.translate(type);
  }

  @Override
  public String visitProgram(final ProgramNode node) {
    throw new UnsupportedOperationException(
        "visitProgram should not be called directly; use generate() instead");
  }

  @Override
  public String visitImport(final ImportNode node) {
    // Module imports are handled by Phase 0 flattening
    return "";
  }

  @Override
  public String visitType(final TypeNode node) {
    return translate(node.fullName());
  }

  @Override
  public String visitTypeDeclaration(final TypeDeclarationNode node) {
    final var builder = new StringBuilder();
    builder.append("typedef struct {\n");

    indent++;
    for (final FieldDeclarationNode field : node.fields()) {
      indent(builder);
      builder
          .append(translate(field.type().fullName()))
          .append(" ")
          .append(mangle(field.name()))
          .append(";\n");
    }
    indent--;

    builder.append("} ").append(node.name()).append(";");
    declared.add(node.name());
    return builder.toString();
  }

  @Override
  public String visitTupleLiteral(final TupleLiteralNode node) {
    return collections.tuple(node);
  }

  @Override
  public String visitSetLiteral(final SetLiteralNode node) {
    return collections.set(node);
  }

  private String wrap(final String code, final String type) {
    return boxing.wrap(code, type);
  }

  private String unwrap(final String code, final String type) {
    return boxing.unwrap(code, type);
  }

  @Override
  public String visitLambda(final LambdaNode node) {
    return lambdaCompiler().closure(node);
  }

  @Override
  public String visitDestructure(final DestructureNode node) {
    final var builder = new StringBuilder();

    // Evaluate the initializer into a temp tuple variable
    builder.append("SAFETuple __tup__ = ").append(node.initializer().accept(this)).append(";\n");

    // Extract element types from the tuple type (explicit or inferred)
    var full = node.type() != null ? node.type().fullName() : null;
    if (full == null) {
      full = infer(node.initializer());
    }
    List<String> types = null;
    if (full != null && full.startsWith("tuple<") && full.endsWith(">")) {
      final var inner = full.substring(6, full.length() - 1);
      types = split(inner);
    }

    // Declare each variable with the extracted tuple element
    for (int i = 0; i < node.names().size(); i++) {
      final var name = node.names().get(i);
      final var element = types != null && i < types.size() ? types.get(i).trim() : "int";
      final var mapped = translate(element);

      // Track variable type for later inference
      variables().put(name, element);

      if (node.isConstant()) {
        builder.append("const ");
      }
      builder
          .append(mapped)
          .append(" ")
          .append(mangle(name))
          .append(" = ")
          .append(unwrap("__tup__.elements[" + i + "]", element))
          .append(";\n");
    }

    // Remove trailing newline — caller adds semicolon/newline
    final var result = builder.toString();
    return result.endsWith("\n") ? result.substring(0, result.length() - 1) : result;
  }

  @Override
  public String visitDoExpression(final DoExpressionNode node) {
    // Use GCC statement expression: ({ stmts; expr; })
    final var builder = new StringBuilder("({\n");
    indent++;
    for (final var statement : node.statements()) {
      indent(builder);
      builder.append(statement.accept(this)).append("\n");
    }
    indent(builder);
    builder.append(node.expression().accept(this)).append(";\n");
    indent--;
    indent(builder);
    builder.append("})");
    return builder.toString();
  }

  @Override
  public String visitRange(final RangeNode node) {
    final var start = node.start().accept(this);
    final var end = node.end().accept(this);

    if (node.hasStep()) {
      final var step = node.step().accept(this);
      return "safe_range_step(" + start + ", " + end + ", " + step + ")";
    }

    return "safe_range_inclusive(" + start + ", " + end + ")";
  }

  @Override
  public String visitTypeAlias(final TypeAliasNode node) {
    return "typedef " + translate(node.target().fullName()) + " " + node.name() + ";";
  }

  @Override
  public String visitFieldDeclaration(final FieldDeclarationNode node) {
    return translate(node.type().fullName()) + " " + mangle(node.name());
  }

  @Override
  public String visitFunctionDeclaration(final FunctionDeclarationNode node) {
    final var mangled = mangleFunction(node.name());
    if (node.hasDecreases()) {
      line("static SAFEDecreasesStack __decreases_stack_" + mangled + " = { .sp = 0 };");
    }
    return body(node, mangled);
  }

  @Override
  public String visitParameter(final ParameterNode node) {
    return translate(node.type().fullName()) + " " + mangle(node.name());
  }

  @Override
  public String visitVariableDeclaration(final VariableDeclarationNode node) {
    final var builder = new StringBuilder();
    final var full = node.type().fullName();
    final var type = translate(full);

    // Track variable type for later use (map/list access)
    variables().put(node.name(), full);

    if (node.isConstant()) {
      builder.append("const ");
    }

    builder.append(type).append(" ").append(mangle(node.name()));

    if (node.hasInitializer()) {
      // Phase 6: if the initializer is an empty container literal and the
      // declared type is known, skip the literal's untyped emission and
      // construct a typed container directly — otherwise retain-on-insert
      // can't fire for later appends.
      final var init = node.initializer();
      if (init instanceof MapLiteralNode mapLit
          && mapLit.entries().isEmpty()
          && full.startsWith("map<")) {
        final var kkind = safeKindOf(keyed(full));
        final var vkind = safeKindOf(valued(full));
        builder.append(" = ");
        if ("0".equals(kkind) && "0".equals(vkind)) {
          builder.append("safe_map_new()");
        } else {
          builder
              .append("safe_map_new_typed(")
              .append(kkind)
              .append(", ")
              .append(vkind)
              .append(")");
        }
      } else if (init instanceof ListLiteralNode listLit
          && listLit.elements().isEmpty()
          && full.startsWith("list<")) {
        final var ekind = safeKindOf(inner(full));
        builder.append(" = ");
        if ("0".equals(ekind)) {
          builder.append("safe_list_new()");
        } else {
          builder.append("safe_list_new_typed(").append(ekind).append(")");
        }
      } else if (init instanceof SetLiteralNode setLit
          && setLit.elements().isEmpty()
          && full.startsWith("set<")) {
        final var ekind = safeKindOf(inner(full));
        builder.append(" = ");
        if ("0".equals(ekind)) {
          builder.append("safe_set_new()");
        } else {
          builder.append("safe_set_new_typed(").append(ekind).append(")");
        }
      } else {
        // Phase 7b-5: heap-RC declaration with aliased RHS retains, paired
        // with function/loop scope-release. For fresh RHS (function call /
        // literal), ownership transfers (refs=1 from allocation).
        final var code = init.accept(this);
        if (isHeapRc(full) && !isFreshRhs(init)) {
          builder.append(" = (").append(type).append(")safe_retain(").append(code).append(")");
        } else {
          builder.append(" = ").append(code);
        }
      }
    } else {
      builder.append(" = ");
      switch (full) {
        case "int":
        case "uint":
          builder.append("0");
          break;
        case "float":
          builder.append("0.0");
          break;
        case "string":
          builder.append("\"\"");
          break;
        case "boolean":
          builder.append("false");
          break;
        default:
          if (full.startsWith("map<")) {
            // Phase 6: type-directed typed-map for empty defaults so
            // retain-on-insert fires from the start.
            final var kkind = safeKindOf(keyed(full));
            final var vkind = safeKindOf(valued(full));
            if ("0".equals(kkind) && "0".equals(vkind)) {
              builder.append("safe_map_new()");
            } else {
              builder
                  .append("safe_map_new_typed(")
                  .append(kkind)
                  .append(", ")
                  .append(vkind)
                  .append(")");
            }
          } else if (full.startsWith("list<")) {
            final var ekind = safeKindOf(inner(full));
            if ("0".equals(ekind)) {
              builder.append("safe_list_new()");
            } else {
              builder.append("safe_list_new_typed(").append(ekind).append(")");
            }
          } else if (full.startsWith("tuple")) {
            builder.append("(SAFETuple){.count=0}");
          } else if (full.startsWith("set")) {
            final var ekind = safeKindOf(inner(full));
            if ("0".equals(ekind)) {
              builder.append("safe_set_new()");
            } else {
              builder.append("safe_set_new_typed(").append(ekind).append(")");
            }
          } else if (full.startsWith("fn")) {
            // Closures are always boxed — NULL is the uninitialized value.
            builder.append("NULL");
          } else {
            builder.append("(").append(type).append("){}");
          }
      }
    }
    builder.append(";");
    return builder.toString();
  }

  @Override
  public String visitAssignment(final AssignmentNode node) {
    final var builder = new StringBuilder();
    final var target = new StringBuilder();
    for (int i = 0; i < node.parts().size(); i++) {
      if (i > 0) target.append(".");
      target.append(mangle(node.parts().get(i)));
    }

    // Phase 3/7: refcount-correct reassignment of a heap-typed LOCAL.
    //   Fresh RHS (function call / fresh ternary): transfer ownership —
    //     evaluate into tmp, release old (if != tmp), assign tmp.
    //   Aliased RHS (variable reference, etc.): retain rhs, release old,
    //     assign — so scope-release at either end doesn't leave dangling.
    //   User-struct LHS (e.g. Page) with heap fields: release each old
    //     field before overwriting; the new struct's fields are fresh/
    //     already-retained from the constructor.
    if (node.parts().size() == 1) {
      final var name = node.parts().getFirst();
      final var type = variables().get(name);
      // Phase 7b-3: struct LHS with heap fields. Release old LHS heap
      // fields before overwrite, with per-field pointer guard so a
      // self-assign (e.g. `fresh = fresh` produced by a ternary that
      // sometimes returns the existing value) doesn't free data the RHS
      // still points at.
      if (type != null && !isHeapRc(type) && structs.containsKey(type)) {
        // When the RHS provably CONSTRUCTS its struct (owns its fields — e.g. `db = lsm:put(db,..)`
        // returning a fresh Lsm), the old field's ref MUST be released unconditionally: the new
        // struct
        // carries the same field pointer *retained*, so the `!=` guard would skip the drop and leak
        // the
        // old ref every reassignment (the container never disposes). For a passthrough that may
        // return
        // a BORROWED field, keep the pointer guard so shared data is never freed while still live.
        final var owns = reassignOwnsFields(node.value());
        final var release = new StringBuilder();
        final var struct = structs.get(type);
        for (final var field : struct.fields()) {
          if (isHeapRc(field.type().fullName())) {
            final var f = mangle(field.name());
            if (owns) {
              release.append("safe_release(").append(target).append(".").append(f).append("); ");
            } else {
              release
                  .append("if (__rc_new__.")
                  .append(f)
                  .append(" != ")
                  .append(target)
                  .append(".")
                  .append(f)
                  .append(") safe_release(")
                  .append(target)
                  .append(".")
                  .append(f)
                  .append("); ");
            }
          }
        }
        if (release.length() > 0) {
          return "{ "
              + type
              + " __rc_new__ = "
              + node.value().accept(this)
              + "; "
              + release
              + target
              + " = __rc_new__; }";
        }
      }
      if (type != null && isHeapRc(type)) {
        final var ctype = translate(type);
        if (isFreshRhs(node.value())) {
          return "{ "
              + ctype
              + " __rc_new__ = "
              + node.value().accept(this)
              + "; if (__rc_new__ != "
              + target
              + ") safe_release("
              + target
              + "); "
              + target
              + " = __rc_new__; }";
        }
        // Aliased path: retain rhs (may equal lhs) → release old lhs →
        // assign. No pointer guard: when rhs == lhs the retain+release
        // cancel, leaving refcount unchanged. With a guard, the retain
        // wouldn't be matched by a release → silent leak per assignment.
        return "{ "
            + ctype
            + " __rc_new__ = ("
            + ctype
            + ")safe_retain("
            + node.value().accept(this)
            + "); safe_release("
            + target
            + "); "
            + target
            + " = __rc_new__; }";
      }
    }

    builder.append(target).append(" = ").append(node.value().accept(this)).append(";");
    return builder.toString();
  }

  /**
   * Is the expression a "fresh owning" producer — one whose result is a refs==1 allocation that the
   * caller takes ownership of without a retain? Covers direct function calls and ternaries whose
   * arms are all fresh. Variable references are NOT fresh (aliasing requires a retain).
   */
  private boolean isFreshRhs(final ASTNode node) {
    // A MAP index read (`m[k]`) is an OWNED producer: safe_map_get_ptr retains its result
    // (mirroring
    // retain-on-insert), so binding it must NOT add a second retain — otherwise the value sits at
    // refs>=2 and never frees. A LIST/tuple/string index read returns a BORROWED slot, so it stays
    // non-fresh (needs the codegen retain to own). Container type is resolved as in CIndexCompiler.
    if (node instanceof IndexAccessNode access) {
      final String containerType;
      if (access.container() instanceof VariableReferenceNode ref && ref.parts().size() == 1) {
        containerType = variables().get(ref.parts().getFirst());
      } else {
        containerType = infer(access.container());
      }
      return containerType != null && containerType.startsWith("map<");
    }
    return rc.isFreshProducer(node);
  }

  /**
   * Whether reassigning {@code x = value} yields a struct whose heap fields are OWNED — so
   * releasing the old LHS field is both safe and necessary. True when the RHS CONSTRUCTS its result
   * (a struct literal, or a call whose every return constructs — transitively), so the new struct
   * holds its own +1 on each field and the old field's ref can be dropped. False for an
   * identity-passthrough that returns a BORROWED field (the callee's param-release already consumed
   * the caller's wrap-retain); there the pointer guard must stay or the shared field is freed while
   * still live. Conservative: anything not provably constructing returns false (a leak, never a
   * use-after-free).
   */
  private boolean reassignOwnsFields(final ASTNode value) {
    return constructsStruct(value, null, new java.util.HashSet<>());
  }

  private boolean constructsStruct(
      final ASTNode expr, final String module, final java.util.Set<String> visiting) {
    if (expr instanceof ObjectCreationNode) {
      return true;
    }
    if (expr instanceof IfExpressionNode cond) {
      return constructsStruct(cond.then(), module, visiting)
          && cond.otherwise() != null
          && constructsStruct(cond.otherwise(), module, visiting);
    }
    if (expr instanceof FunctionCallNode call) {
      // An intra-module call is UNPREFIXED in the AST (`flush(..)` inside the lsm module), but
      // module
      // functions are registered as `lsm:flush`; carry the enclosing module so the lookup resolves.
      final var prefix = call.hasPrefix() ? call.prefix() : module;
      final var key = prefix != null ? prefix + ":" + call.name() : call.name();
      if (!visiting.add(key)) {
        return false; // recursion cycle — conservative
      }
      var declaration = functions.get(key);
      if (declaration == null) {
        declaration = functions.get(call.name());
      }
      final var owns =
          declaration != null && allReturnsConstruct(declaration.body(), prefix, visiting);
      visiting.remove(key);
      return owns;
    }
    return false; // variable / field / index / literal-scalar — borrowed or not a struct
    // constructor
  }

  private boolean allReturnsConstruct(
      final java.util.List<ASTNode> body,
      final String module,
      final java.util.Set<String> visiting) {
    final var returns = new java.util.ArrayList<ReturnNode>();
    collectReturns(body, returns);
    if (returns.isEmpty()) {
      return false; // no explicit return we can prove — conservative
    }
    for (final var statement : returns) {
      if (!statement.hasExpression()
          || !constructsStruct(statement.expression(), module, visiting)) {
        return false;
      }
    }
    return true;
  }

  private void collectReturns(
      final java.util.List<ASTNode> nodes, final java.util.List<ReturnNode> out) {
    for (final var node : nodes) {
      collectReturns(node, out);
    }
  }

  private void collectReturns(final ASTNode node, final java.util.List<ReturnNode> out) {
    if (node instanceof ReturnNode statement) {
      out.add(statement);
    } else if (node instanceof ForStatementNode loop) {
      collectReturns(loop.body(), out);
    } else if (node instanceof WhileStatementNode loop) {
      collectReturns(loop.body(), out);
    } else if (node instanceof DoExpressionNode block) {
      collectReturns(block.statements(), out);
      collectReturns(block.expression(), out);
    } else if (node instanceof ExpressionStatementNode statement) {
      collectReturns(statement.expression(), out);
    } else if (node instanceof IfExpressionNode cond) {
      collectReturns(cond.then(), out);
      if (cond.otherwise() != null) {
        collectReturns(cond.otherwise(), out);
      }
    } else if (node instanceof CaseExpressionNode cases) {
      for (final var branch : cases.branches()) {
        collectReturns(branch.result(), out);
      }
      if (cases.fallback() != null) {
        collectReturns(cases.fallback(), out);
      }
    }
  }

  @Override
  public String visitForStatement(final ForStatementNode node) {
    return loops.compile(node);
  }

  @Override
  public String visitWhileStatement(final WhileStatementNode node) {
    final var builder = new StringBuilder();
    final var limit = node.bound().accept(this);
    final var condition = node.condition().accept(this);

    builder.append("{\n");
    indent++;
    indent(builder);
    builder.append("int64_t __bound__ = ").append(limit).append(";\n");
    indent(builder);
    builder.append(
        "if (__bound__ < 0) { fprintf(stderr, \"While bound must be non-negative, got %lld\\n\", __bound__); exit(1); }\n");
    indent(builder);
    builder.append(
        "if (__bound__ > "
            + SAFEValue.MAX_WHILE_BOUND
            + "LL) { fprintf(stderr, \"While loop bound %lld exceeds the maximum of "
            + SAFEValue.MAX_WHILE_BOUND
            + "\\n\", __bound__); exit(1); }\n");
    indent(builder);
    builder
        .append("for (int64_t __i__ = 0; __i__ < __bound__ && (")
        .append(condition)
        .append("); __i__++) {\n");
    indent++;

    // Phase 7: track heap-typed locals declared directly in the while body
    // and release them at end of each iteration (same discipline as for-loop).
    final var bodyDecls = new ArrayList<String>();
    for (final var stmt : node.body()) {
      if (stmt instanceof VariableDeclarationNode decl) {
        bodyDecls.add(decl.name());
      }
    }
    final var snapshot = new HashSet<>(variables().keySet());
    for (final var statement : node.body()) {
      final var code = statement.accept(this);
      if (!code.trim().isEmpty()) {
        indent(builder);
        builder.append(code).append("\n");
      }
    }
    for (final String name : bodyDecls) {
      if (snapshot.contains(name)) continue;
      final String type = variables().get(name);
      if (type == null) continue;
      final var release = releaseForLocal(name, type);
      if (release.isEmpty()) continue;
      for (final var rline : release.split("\n")) {
        if (rline.isEmpty()) continue;
        indent(builder);
        builder.append(rline).append("\n");
      }
    }
    variables().keySet().removeIf(k -> !snapshot.contains(k));

    indent--;
    indent(builder);
    builder.append("}\n");
    indent--;
    indent(builder);
    builder.append("}");

    return builder.toString();
  }

  @Override
  public String visitReturn(final ReturnNode node) {
    final var builder = new StringBuilder();
    final var decreasing =
        currentFunction() != null && Objects.requireNonNull(currentFunction()).hasDecreases();
    final var ensuring =
        currentFunction() != null && Objects.requireNonNull(currentFunction()).hasEnsures();
    final var tracked = currentFunctionCName() != null;

    final var returnedVar = returnedVariableName(node);
    if (ensuring) {
      if (!"void".equals(Objects.requireNonNull(currentFunction()).returns().fullName())) {
        builder.append("result = ");
        if (node.hasExpression()) {
          builder.append(node.expression().accept(this));
        }
        builder.append(";\n");
        if (tracked) {
          indent(builder);
          builder.append("__safe_recursion_depth--;\n");
        }
        indent(builder);
        builder.append("if (!(");
        builder.append(Objects.requireNonNull(currentFunction()).ensures().accept(this));
        builder.append(")) { fprintf(stderr, \"Postcondition failed\\n\"); exit(1); }\n");
        if (decreasing) {
          indent(builder);
          builder.append("__decreases_stack_").append(currentFunctionCName()).append(".sp--;\n");
        }
        emitCurrentFunctionBodyLocalReleases(builder, returnedVar);
        emitParamReleases(builder, currentFunction().parameters(), returnedVar);
        indent(builder);
        builder.append("return result;");
        return builder.toString();
      }

      if (tracked) {
        indent(builder);
        builder.append("__safe_recursion_depth--;\n");
      }
      indent(builder);
      builder.append("if (!(");
      builder.append(Objects.requireNonNull(currentFunction()).ensures().accept(this));
      builder.append(")) { fprintf(stderr, \"Postcondition failed\\n\"); exit(1); }\n");
      if (decreasing) {
        indent(builder);
        builder.append("__decreases_stack_").append(currentFunctionCName()).append(".sp--;\n");
      }
      emitCurrentFunctionBodyLocalReleases(builder, returnedVar);
      emitParamReleases(builder, currentFunction().parameters(), returnedVar);
      indent(builder);
      builder.append("return;");
      return builder.toString();
    }

    if (decreasing || tracked) {
      if (node.hasExpression()) {
        final var type = translate(Objects.requireNonNull(currentFunction()).returns().fullName());
        builder
            .append(type)
            .append(" __result__ = ")
            .append(node.expression().accept(this))
            .append(";\n");
        if (decreasing) {
          indent(builder);
          builder.append("__decreases_stack_").append(currentFunctionCName()).append(".sp--;\n");
        }
        if (tracked) {
          indent(builder);
          builder.append("__safe_recursion_depth--;\n");
        }
        emitCurrentFunctionBodyLocalReleases(builder, returnedVar);
        emitParamReleases(builder, currentFunction().parameters(), returnedVar);
        indent(builder);
        builder.append("return __result__;");
        return builder.toString();
      }
      if (decreasing) {
        builder.append("__decreases_stack_").append(currentFunctionCName()).append(".sp--;\n");
        indent(builder);
      }
      if (tracked) {
        builder.append("__safe_recursion_depth--;\n");
        indent(builder);
      }
      emitCurrentFunctionBodyLocalReleases(builder, returnedVar);
      emitParamReleases(builder, currentFunction().parameters(), returnedVar);
      builder.append("return;");
      return builder.toString();
    }

    if (currentFunction() != null) {
      emitCurrentFunctionBodyLocalReleases(builder, returnedVar);
      emitParamReleases(builder, currentFunction().parameters(), returnedVar);
      indent(builder);
    }
    builder.append("return");
    if (node.hasExpression()) {
      builder.append(" ").append(node.expression().accept(this));
    }
    builder.append(";");
    return builder.toString();
  }

  /**
   * Return the name of the local being returned, if the return expression is a single variable
   * reference (so we can skip releasing it).
   */
  private String returnedVariableName(final ReturnNode node) {
    if (!node.hasExpression()) return null;
    if (node.expression() instanceof VariableReferenceNode ref
        && ref.parts().size() == 1
        && !ref.hasPrefix()) {
      return ref.parts().getFirst();
    }
    return null;
  }

  @Override
  public String visitExpressionStatement(final ExpressionStatementNode node) {
    return node.expression().accept(this) + ";";
  }

  @Override
  public String visitBinaryExpression(final BinaryExpressionNode node) {
    final var left = node.left().accept(this);
    final var right = node.right().accept(this);
    final var op = node.operator();

    switch (op) {
      case "+" -> {
        if (isStringLike(node.left()) || isStringLike(node.right())) {
          return "safe_string_concat(" + left + ", " + right + ")";
        }
      }
      case "in" -> {
        // Determine RHS type
        final var rhs = infer(node.right());
        if (rhs != null && rhs.startsWith("set<")) {
          final var element = infer(node.left());
          return "safe_set_contains(" + right + ", " + wrap(left, element) + ")";
        }
        if (rhs != null && rhs.startsWith("map<")) {
          final var key = keyed(rhs);
          if (isIntegerKeyed(key)) {
            return "(safe_map_find_ikey(" + right + ", " + left + ") != NULL)";
          }
          if (isFloatKeyed(key)) {
            return "(safe_map_find_fkey(" + right + ", " + left + ") != NULL)";
          }
          return "(safe_map_find(" + right + ", " + left + ") != NULL)";
        }
        if ("string".equals(rhs)) {
          return "(strstr(" + right + ", " + left + ") != NULL)";
        }
        // List: dispatch based on element type
        final var element = infer(node.left());
        if ("string".equals(element)) {
          return "safe_list_contains_str(" + right + ", " + left + ")";
        }
        if ("float".equals(element)) {
          return "safe_list_contains_float(" + right + ", " + left + ")";
        }
        return "safe_list_contains_int(" + right + ", " + left + ")";
      }
      case "==", "!=" -> {
        if (isStringLike(node.left()) || isStringLike(node.right())) {
          if (op.equals("==")) {
            return "(strcmp(" + left + ", " + right + ") == 0)";
          } else {
            return "(strcmp(" + left + ", " + right + ") != 0)";
          }
        }
      }
      default -> {
        // Other operators handled by the fall-through code below.
      }
    }

    // Uint checked subtraction — match interpreter/VM semantics (reject negative result)
    if ("-".equals(op)) {
      if ("uint".equals(infer(node.left())) || "uint".equals(infer(node.right()))) {
        return "safe_uint_sub(" + left + ", " + right + ")";
      }
    }

    // Checked division and modulo
    if ("/".equals(op) || "%".equals(op)) {
      final var lhs = infer(node.left());
      final var rhs = infer(node.right());
      if ("float".equals(lhs) || "float".equals(rhs)) {
        return "/".equals(op)
            ? "safe_float_div(" + left + ", " + right + ")"
            : "safe_float_mod(" + left + ", " + right + ")";
      } else if ("uint".equals(lhs) || "uint".equals(rhs)) {
        return "/".equals(op)
            ? "safe_uint_div(" + left + ", " + right + ")"
            : "safe_uint_mod(" + left + ", " + right + ")";
      } else {
        return "/".equals(op)
            ? "safe_int_div(" + left + ", " + right + ")"
            : "safe_int_mod(" + left + ", " + right + ")";
      }
    }

    // Bitwise operators — cast to int64_t to match SAFE int semantics
    return switch (op) {
      case "&" -> "((int64_t)(" + left + " & " + right + "))";
      case "|" -> "((int64_t)(" + left + " | " + right + "))";
      case "^" -> "((int64_t)(" + left + " ^ " + right + "))";
      case "<<" -> "((int64_t)(" + left + " << " + right + "))";
      case ">>" -> "((int64_t)(" + left + " >> " + right + "))";
      default -> "(" + left + " " + op + " " + right + ")";
    };
  }

  @Override
  public String visitUnaryExpression(final UnaryExpressionNode node) {
    final var operand = node.operand().accept(this);
    final var op = node.operator();

    // Bitwise NOT — cast to int64_t to match SAFE int semantics
    if ("~".equals(op)) {
      return "((int64_t)(~" + operand + "))";
    }

    return "(" + op + operand + ")";
  }

  @Override
  public String visitIfExpression(final IfExpressionNode node) {
    final var condition = node.condition().accept(this);
    final var then = node.then().accept(this);

    if (node.hasOtherwise()) {
      final var otherwise = node.otherwise().accept(this);
      return "((" + condition + ") ? (" + then + ") : (" + otherwise + "))";
    }
    final var stringified = stringify(node.then(), then);
    return "((" + condition + ") ? (" + stringified + ") : (\"void\"))";
  }

  private String stringify(final ASTNode node, final String code) {
    final var type = infer(node);
    if ("string".equals(type)) return code;
    if ("float".equals(type)) return "safe_string_val_float(" + code + ")";
    if ("boolean".equals(type)) return "safe_string_val_bool(" + code + ")";
    if ("int".equals(type) || "uint".equals(type)) return "safe_string_val(" + code + ")";
    if ("void".equals(type)) return "\"void\"";
    if (type != null && (isPointerType(type) || enumerations.containsKey(type))) {
      return "({ (void)(" + code + "); \"void\"; })";
    }
    return "safe_string_val(" + code + ")";
  }

  @Override
  public String visitCaseExpression(final CaseExpressionNode node) {
    return cases.compile(node);
  }

  @Override
  public String visitCaseBranch(final CaseBranchNode node) {
    return "";
  }

  @Override
  public String visitFunctionCall(final FunctionCallNode node) {
    return calls.compile(node);
  }

  private String resolve(final String name, final List<ASTNode> arguments) {
    return gatedResolve(name, arguments);
  }

  @Override
  public String visitVariableReference(final VariableReferenceNode node) {
    // Module variable reference via prefix (colon syntax): mod:var
    if (node.hasPrefix() && modules.contains(node.prefix())) {
      return mangle(node.prefix(), node.parts().getFirst());
    }

    final var parts = node.parts();

    // Module variable reference via dot syntax: math.PI -> safe__math_PI
    if (parts.size() >= 2 && modules.contains(parts.getFirst())) {
      final var builder = new StringBuilder(mangle(parts.getFirst(), parts.get(1)));
      for (int i = 2; i < parts.size(); i++) {
        builder.append(".").append(mangle(parts.get(i)));
      }
      return builder.toString();
    }

    // Function reference: name used as value, not called
    if (parts.size() == 1) {
      final var name = parts.getFirst();
      if (functions.containsKey(name) && !variables().containsKey(name)) {
        return reference(name, functions.get(name));
      }
      // No-arg enum variant reference (e.g., None, Red)
      if (!variables().containsKey(name)) {
        for (final var entry : enumerations.entrySet()) {
          for (final var variant : entry.getValue().variants()) {
            if (variant.name().equals(name) && !variant.hasFields()) {
              return entry.getKey() + "_" + name + "_new()";
            }
          }
        }
      }
    }

    // Intra-module constant reference: SIZE -> safe__page_SIZE
    if (currentModule() != null && parts.size() == 1) {
      final var mangled = mangle(currentModule(), parts.getFirst());
      if (variables().containsKey(mangled)) {
        return mangled;
      }
    }

    // Resolve cross-parameter default references to temp variable names
    if (parts.size() == 1 && aliases.containsKey(parts.getFirst())) {
      return aliases.get(parts.getFirst());
    }

    final var builder = new StringBuilder();
    for (int i = 0; i < parts.size(); i++) {
      if (i > 0) builder.append(".");
      builder.append(mangle(parts.get(i)));
    }

    return builder.toString();
  }

  @Override
  public String visitObjectCreation(final ObjectCreationNode node) {
    final var builder = new StringBuilder();
    builder.append("(").append(node.type()).append("){\n");

    indent++;
    final var assignments = node.fields();
    for (int i = 0; i < assignments.size(); i++) {
      if (i > 0) builder.append(",\n");
      final var assign = assignments.get(i);
      indent(builder);
      // An empty map/list literal in a field carries NO element-kind of its own, so emitting it via
      // the generic path yields an UNTYPED container (`safe_map_new()`) that neither retains on
      // insert
      // nor releases on dispose — a value stored into it then over-releases at the callee's param
      // exit
      // (Step 5). Type it from the FIELD's declared type instead, and treat it as fresh (owned, no
      // retain) — mirroring the typed-empty-collection handling in visitVariableDeclaration.
      final var declared = structFieldType(node.type(), assign.field());
      final var typedEmpty = typedEmptyCollection(assign.value(), declared);
      // Phase 7b-2: when a struct literal populates a heap-RC field with an
      // aliased expression (not a fresh function call / allocation), the
      // constructed struct is "owning" a reference that was previously held
      // elsewhere. Retain so the new struct has its own counted reference.
      final var fieldType = declared != null ? declared : infer(assign.value());
      final var fresh = typedEmpty != null || isFreshRhs(assign.value());
      final var code = typedEmpty != null ? typedEmpty : assign.value().accept(this);
      final var wrapped =
          isHeapRc(fieldType) && !fresh
              ? "(" + translate(fieldType) + ")safe_retain(" + code + ")"
              : code;
      builder.append(".").append(mangle(assign.field())).append(" = ").append(wrapped);
    }
    if (!assignments.isEmpty()) {
      builder.append("\n");
    }
    indent--;

    indent(builder);
    builder.append("}");

    return builder.toString();
  }

  @Override
  public String visitFieldAssignment(final FieldAssignmentNode node) {
    return "." + mangle(node.field()) + " = " + node.value().accept(this);
  }

  /** The declared SAFE type of {@code field} in struct {@code structType}, or null. */
  private String structFieldType(final String structType, final String field) {
    final var struct = structs.get(structType);
    if (struct == null) return null;
    for (final var declared : struct.fields()) {
      if (declared.name().equals(field)) return declared.type().fullName();
    }
    return null;
  }

  /**
   * If {@code value} is an EMPTY map/list literal and {@code declared} is the matching container
   * type, return a TYPED constructor (so the container retains on insert / releases on dispose);
   * else null. An empty literal has no element-kind of its own, so only the declared type carries
   * the kinds.
   */
  private String typedEmptyCollection(final ASTNode value, final String declared) {
    if (declared == null) return null;
    if (value instanceof MapLiteralNode map
        && map.entries().isEmpty()
        && declared.startsWith("map<")) {
      final var kkind = safeKindOf(keyed(declared));
      final var vkind = safeKindOf(valued(declared));
      return ("0".equals(kkind) && "0".equals(vkind))
          ? "safe_map_new()"
          : "safe_map_new_typed(" + kkind + ", " + vkind + ")";
    }
    if (value instanceof ListLiteralNode list
        && list.elements().isEmpty()
        && declared.startsWith("list<")) {
      final var ekind = safeKindOf(inner(declared));
      return "0".equals(ekind) ? "safe_list_new()" : "safe_list_new_typed(" + ekind + ")";
    }
    return null;
  }

  @Override
  public String visitLiteral(final LiteralNode node) {
    return switch (node) {
      case LiteralNode.IntLiteral i -> Long.toString(i.value());
      case LiteralNode.UintLiteral u -> Long.toString(u.value());
      case LiteralNode.FloatLiteral f -> Double.toString(f.value());
      case LiteralNode.BoolLiteral b -> b.value() ? "true" : "false";
      case LiteralNode.StringLiteral s -> "\"" + escape(s.value()) + "\"";
    };
  }

  @Override
  public String visitListLiteral(final ListLiteralNode node) {
    return collections.list(node);
  }

  @Override
  public String visitMapLiteral(final MapLiteralNode node) {
    return collections.map(node);
  }

  @Override
  public String visitMapEntry(final MapEntryNode node) {
    return "";
  }

  @Override
  public String visitAssert(final AssertNode node) {
    final var builder = new StringBuilder();
    builder.append("if (!(").append(node.condition().accept(this)).append(")) { ");

    if (node.hasMessage()) {
      builder.append("const char* __msg__ = ").append(node.message().accept(this)).append("; ");
      builder.append("fprintf(stderr, \"Assertion failed: %s\\n\", __msg__); ");
    } else {
      builder.append("fprintf(stderr, \"Assertion failed\\n\"); ");
    }

    builder.append("exit(1); }");
    return builder.toString();
  }

  @Override
  public String visitIndexAccess(final IndexAccessNode node) {
    return indexing.access(node);
  }

  @Override
  public String visitIndexAssignment(final IndexAssignmentNode node) {
    return indexing.assignment(node);
  }

  private String tuple(final String type, final ASTNode index) {
    // tuple<int, string, float> — extract the Nth type parameter
    if (type.startsWith("tuple<") && type.endsWith(">")) {
      final var inner = type.substring(6, type.length() - 1);
      final var parts = split(inner);
      if (index instanceof LiteralNode.IntLiteral literal) {
        final var position = (int) literal.value();
        if (position >= 0 && position < parts.size()) {
          return parts.get(position).trim();
        }
      }
      // Fallback: return first element type
      if (!parts.isEmpty()) return parts.getFirst().trim();
    }
    return "int";
  }

  private List<String> split(final String inner) {
    // Split on commas, respecting angle bracket nesting
    final var result = new ArrayList<String>();
    int depth = 0;
    int start = 0;
    for (int i = 0; i < inner.length(); i++) {
      final var ch = inner.charAt(i);
      if (ch == '<') depth++;
      else if (ch == '>') depth--;
      else if (ch == ',' && depth == 0) {
        result.add(inner.substring(start, i));
        start = i + 1;
      }
    }
    result.add(inner.substring(start));
    return result;
  }

  private String inner(final String type) {
    if (type != null && type.startsWith("list<") && type.endsWith(">")) {
      return type.substring(5, type.length() - 1);
    }
    return "int";
  }

  private String valued(final String type) {
    if (type == null || !type.startsWith("map<") || !type.endsWith(">")) {
      return "int";
    }
    final var inner = type.substring(4, type.length() - 1);
    final var comma = comma(inner);
    if (comma >= 0) {
      return inner.substring(comma + 1).trim();
    }
    return "int";
  }

  private String keyed(final String type) {
    if (type == null || !type.startsWith("map<") || !type.endsWith(">")) {
      return "string";
    }
    final var inner = type.substring(4, type.length() - 1);
    final var comma = comma(inner);
    if (comma >= 0) {
      return inner.substring(0, comma).trim();
    }
    return "string";
  }

  private int comma(final String text) {
    int depth = 0;
    for (int i = 0; i < text.length(); i++) {
      final var c = text.charAt(i);
      if (c == '<') depth++;
      else if (c == '>') depth--;
      else if (c == ',' && depth == 0) return i;
    }
    return -1;
  }

  private boolean isIntegerKeyed(final String key) {
    return "int".equals(key) || "uint".equals(key) || "boolean".equals(key);
  }

  private boolean isFloatKeyed(final String key) {
    return "float".equals(key);
  }

  private String putter(final String key) {
    if (isIntegerKeyed(key)) return "safe_map_ikey_put_";
    if (isFloatKeyed(key)) return "safe_map_fkey_put_";
    return "safe_map_put_";
  }

  private String getter(final String key) {
    if (isIntegerKeyed(key)) return "safe_map_ikey_get_";
    if (isFloatKeyed(key)) return "safe_map_fkey_get_";
    return "safe_map_get_";
  }

  // ---- Recursive value stringification (matches the interpreter's SAFEValue.asString) ----

  /** True for compound types that need a generated recursive stringifier. */
  private boolean stringifiable(final String type) {
    if (type == null) return false;
    return type.startsWith("list<")
        || type.startsWith("tuple<")
        || "tuple".equals(type)
        || type.startsWith("map<")
        || enumerations.containsKey(type)
        || structs.containsKey(type);
  }

  /**
   * A C expression of type {@code char*} rendering {@code expr} (whose SAFE type is {@code type})
   * exactly as the interpreter's {@code SAFEValue.asString}. Compound types delegate to a recursive
   * helper function generated once and spliced into the output.
   */
  private String stringify(final String expr, final String type) {
    if (type == null) return "safe_string_val(" + expr + ")";
    switch (type) {
      case "int":
        return "safe_string_val(" + expr + ")";
      case "uint":
        return "safe_string_val_uint(" + expr + ")";
      case "float":
        return "safe_string_val_float(" + expr + ")";
      case "boolean":
        return "safe_string_val_bool(" + expr + ")";
      case "string":
        return expr;
      case "void":
        return "\"void\"";
      default:
        break;
    }
    if (type.startsWith("list<")) return ensureListStr(type) + "(" + expr + ")";
    if (type.startsWith("tuple<") || "tuple".equals(type))
      return ensureTupleStr(type) + "(" + expr + ")";
    if (type.startsWith("map<")) return ensureMapStr(type) + "(" + expr + ")";
    if (enumerations.containsKey(type)) return ensureEnumStr(type) + "(" + expr + ")";
    if (structs.containsKey(type)) return ensureStructStr(type) + "(" + expr + ")";
    return "safe_string_val(" + expr + ")";
  }

  /** Sanitize a SAFE type string into a valid C identifier suffix. */
  private String typeId(final String type) {
    final var builder = new StringBuilder();
    for (int i = 0; i < type.length(); i++) {
      final var c = type.charAt(i);
      builder.append(Character.isLetterOrDigit(c) ? c : '_');
    }
    return builder.toString();
  }

  /**
   * C expression reading element {@code index} of {@code container} (a SAFEList*) as type {@code
   * t}.
   */
  private String readElement(final String container, final String index, final String t) {
    final var slot = "((void**)" + container + "->data)[" + index + "]";
    if ("string".equals(t)) return "(char*)" + slot;
    if ("float".equals(t)) return "*((double*)" + slot + ")";
    if ("uint".equals(t)) return "*((uint64_t*)" + slot + ")";
    if ("int".equals(t) || "boolean".equals(t)) return "*((int64_t*)" + slot + ")";
    if (t.startsWith("list<") || t.startsWith("map<") || t.startsWith("set<")) {
      return "(" + translate(t) + ")" + slot;
    }
    if (t.startsWith("tuple<") || "tuple".equals(t)) return "(*((SAFETuple*)" + slot + "))";
    if (enumerations.containsKey(t)) {
      return recursive.contains(t) ? "((" + t + "*)" + slot + ")" : "(*((" + t + "*)" + slot + "))";
    }
    if (structs.containsKey(t)) return "(*((" + t + "*)" + slot + "))";
    return "*((int64_t*)" + slot + ")";
  }

  private String ensureListStr(final String type) {
    final var existing = stringifiers.get(type);
    if (existing != null) return existing;
    final var name = "safe_str__" + typeId(type);
    stringifiers.put(type, name);
    stringifierDecls.add("static char* " + name + "(SAFEList* xs);");
    final var elem = inner(type);
    final var body = new StringBuilder();
    body.append("static char* ").append(name).append("(SAFEList* xs) {\n");
    body.append("  if (!xs || xs->length == 0) return safe_arena_strdup(\"[]\");\n");
    body.append("  int64_t __n = xs->length;\n");
    body.append("  const char** __p = (const char**)malloc((size_t)__n * sizeof(char*));\n");
    body.append("  for (int64_t __i = 0; __i < __n; __i++) {\n");
    body.append("    ")
        .append(translate(elem))
        .append(" __e = ")
        .append(readElement("xs", "__i", elem))
        .append(";\n");
    body.append("    __p[__i] = ").append(stringify("__e", elem)).append(";\n");
    body.append("  }\n");
    body.append("  char* __r = safe_join(__p, (int)__n, \"[\", \", \", \"]\");\n");
    body.append("  free(__p);\n");
    body.append("  return __r;\n");
    body.append("}");
    stringifierDefs.add(body.toString());
    return name;
  }

  private String ensureTupleStr(final String type) {
    final var existing = stringifiers.get(type);
    if (existing != null) return existing;
    final var name = "safe_str__" + typeId(type);
    stringifiers.put(type, name);
    stringifierDecls.add("static char* " + name + "(SAFETuple t);");
    final var elements =
        type.startsWith("tuple<")
            ? split(type.substring(6, type.length() - 1))
            : new ArrayList<String>();
    final var body = new StringBuilder();
    body.append("static char* ").append(name).append("(SAFETuple t) {\n");
    body.append("  const char* __p[").append(Math.max(elements.size(), 1)).append("];\n");
    for (int i = 0; i < elements.size(); i++) {
      final var etype = elements.get(i).trim();
      body.append("  __p[")
          .append(i)
          .append("] = ")
          .append(stringify(unwrap("t.elements[" + i + "]", etype), etype))
          .append(";\n");
    }
    body.append("  return safe_join(__p, ")
        .append(elements.size())
        .append(", \"(\", \", \", \")\");\n");
    body.append("}");
    stringifierDefs.add(body.toString());
    return name;
  }

  private String ensureMapStr(final String type) {
    final var existing = stringifiers.get(type);
    if (existing != null) return existing;
    final var name = "safe_str__" + typeId(type);
    stringifiers.put(type, name);
    stringifierDecls.add("static char* " + name + "(SAFEMap* m);");
    final var keyType = keyed(type);
    final var valueType = valued(type);
    final var body = new StringBuilder();
    body.append("static char* ").append(name).append("(SAFEMap* m) {\n");
    body.append("  if (!m || m->length == 0) return safe_arena_strdup(\"{}\");\n");
    body.append("  int64_t __n = m->length;\n");
    body.append("  const char** __p = (const char**)malloc((size_t)__n * sizeof(char*));\n");
    body.append("  int64_t __i = 0;\n");
    body.append("  for (SAFEMapEntry* __e = m->head; __e; __e = __e->order_next) {\n");
    body.append("    const char* __kv[3];\n");
    body.append("    __kv[0] = ").append(stringify(mapKey(keyType), keyType)).append(";\n");
    body.append("    __kv[1] = \": \";\n");
    body.append("    __kv[2] = ").append(stringify(mapValue(valueType), valueType)).append(";\n");
    body.append("    __p[__i++] = safe_concat(__kv, 3);\n");
    body.append("  }\n");
    body.append("  char* __r = safe_join(__p, (int)__n, \"{\", \", \", \"}\");\n");
    body.append("  free(__p);\n");
    body.append("  return __r;\n");
    body.append("}");
    stringifierDefs.add(body.toString());
    return name;
  }

  private String mapKey(final String keyType) {
    return switch (keyType) {
      case "string" -> "__e->key.string_key";
      case "float" -> "__e->key.float_key";
      case "boolean" -> "__e->key.bool_key";
      case "uint" -> "(uint64_t)__e->key.int_key";
      default -> "__e->key.int_key";
    };
  }

  private String mapValue(final String valueType) {
    switch (valueType) {
      case "string":
        return "__e->value.string_val";
      case "float":
        return "__e->value.float_val";
      case "boolean":
        return "__e->value.bool_val";
      case "uint":
        return "(uint64_t)__e->value.int_val";
      case "int":
        return "__e->value.int_val";
      default:
        break;
    }
    if (valueType.startsWith("list<")
        || valueType.startsWith("map<")
        || valueType.startsWith("set<")) {
      return "(" + translate(valueType) + ")__e->value.ptr_val";
    }
    if (enumerations.containsKey(valueType)) {
      return recursive.contains(valueType)
          ? "(" + valueType + "*)__e->value.ptr_val"
          : "(*(" + valueType + "*)__e->value.ptr_val)";
    }
    return "__e->value.int_val";
  }

  private String ensureStructStr(final String type) {
    final var existing = stringifiers.get(type);
    if (existing != null) return existing;
    final var name = "safe_str__" + typeId(type);
    stringifiers.put(type, name);
    final var ctype = translate(type);
    final var self = recursive.contains(type) ? "v->" : "v.";
    stringifierDecls.add("static char* " + name + "(" + ctype + " v);");
    final var fields = structs.get(type).fields();
    final var body = new StringBuilder();
    body.append("static char* ").append(name).append("(").append(ctype).append(" v) {\n");
    body.append("  const char* __p[").append(fields.size() * 2 + 2).append("];\n");
    body.append("  int __n = 0;\n");
    body.append("  __p[__n++] = \"").append(type).append(" { \";\n");
    for (int i = 0; i < fields.size(); i++) {
      final var field = fields.get(i);
      final var ftype = field.type().fullName();
      final var label = (i == 0 ? "" : ", ") + field.name() + ": ";
      body.append("  __p[__n++] = \"").append(label).append("\";\n");
      body.append("  __p[__n++] = ")
          .append(stringify(self + mangle(field.name()), ftype))
          .append(";\n");
    }
    body.append("  __p[__n++] = \" }\";\n");
    body.append("  return safe_concat(__p, __n);\n");
    body.append("}");
    stringifierDefs.add(body.toString());
    return name;
  }

  private String ensureEnumStr(final String type) {
    final var existing = stringifiers.get(type);
    if (existing != null) return existing;
    final var name = "safe_str__" + typeId(type);
    stringifiers.put(type, name);
    final var ctype = translate(type);
    final var self = recursive.contains(type) ? "v->" : "v.";
    stringifierDecls.add("static char* " + name + "(" + ctype + " v);");
    final var body = new StringBuilder();
    body.append("static char* ").append(name).append("(").append(ctype).append(" v) {\n");
    body.append("  switch (").append(self).append("tag) {\n");
    for (final var variant : enumerations.get(type).variants()) {
      body.append("    case ").append(type).append("_").append(variant.name()).append(": {\n");
      if (!variant.hasFields()) {
        body.append("      return safe_arena_strdup(\"")
            .append(type)
            .append(".")
            .append(variant.name())
            .append("\");\n");
      } else {
        final var fields = variant.fields();
        body.append("      const char* __p[").append(fields.size() * 2 + 2).append("];\n");
        body.append("      int __n = 0;\n");
        body.append("      __p[__n++] = \"")
            .append(type)
            .append(".")
            .append(variant.name())
            .append("(\";\n");
        for (int i = 0; i < fields.size(); i++) {
          final var ftype = fields.get(i).fullName();
          if (i > 0) body.append("      __p[__n++] = \", \";\n");
          body.append("      __p[__n++] = ")
              .append(stringify(self + "data." + variant.name() + "._" + i, ftype))
              .append(";\n");
        }
        body.append("      __p[__n++] = \")\";\n");
        body.append("      return safe_concat(__p, __n);\n");
      }
      body.append("    }\n");
    }
    body.append("  }\n");
    body.append("  return safe_arena_strdup(\"?\");\n");
    body.append("}");
    stringifierDefs.add(body.toString());
    return name;
  }

  private boolean isPointerType(final String type) {
    return type != null
        && (type.startsWith("list")
            || type.startsWith("map")
            || type.startsWith("set")
            || type.startsWith("tuple")
            || "bytes".equals(type));
  }

  private boolean isFunctionType(final String type) {
    return type != null && (type.startsWith("fn<") || "fn".equals(type));
  }

  private boolean isGenericType(final String type) {
    return type != null && type.startsWith("?");
  }

  @Override
  public String visitFieldAccess(final FieldAccessNode node) {
    return node.receiver().accept(this) + "." + node.field();
  }

  @Override
  public String visitEnumDeclaration(final EnumDeclarationNode node) {
    return enums.generate(node);
  }

  @Override
  public String visitEnumVariant(final EnumVariantNode node) {
    return "";
  }

  @Override
  public String visitEnumPattern(final EnumPatternNode node) {
    return "";
  }

  @Override
  public String visitStringInterpolation(final StringInterpolationNode node) {
    final var builder = new StringBuilder();
    builder.append("({\n");
    indent++;

    indent(builder);
    builder.append("char __interp_buf__[65536];\n");
    indent(builder);
    builder.append("int __interp_offset__ = 0;\n");

    for (final ASTNode part : node.parts()) {
      indent(builder);
      if (part instanceof LiteralNode.StringLiteral lit) {
        final var str = lit.value().replace("\\", "\\\\").replace("\"", "\\\"");
        builder
            .append(
                "__interp_offset__ += snprintf(__interp_buf__ + __interp_offset__, 65536 - __interp_offset__, \"")
            .append(str)
            .append("\");\n");
      } else {
        final var code = part.accept(this);
        final var type = infer(part);
        if ("boolean".equals(type)) {
          builder
              .append(
                  "__interp_offset__ += snprintf(__interp_buf__ + __interp_offset__, 65536 - __interp_offset__, \"%s\", (")
              .append(code)
              .append(") ? \"true\" : \"false\");\n");
        } else if ("float".equals(type)) {
          builder
              .append(
                  "__interp_offset__ += snprintf(__interp_buf__ + __interp_offset__, 65536 - __interp_offset__, \"%s\", safe_string_val_float(")
              .append(code)
              .append("));\n");
        } else if (stringifiable(type)) {
          builder
              .append(
                  "__interp_offset__ += snprintf(__interp_buf__ + __interp_offset__, 65536 - __interp_offset__, \"%s\", ")
              .append(stringify(code, type))
              .append(");\n");
        } else {
          final var fmt = format(part);
          builder
              .append(
                  "__interp_offset__ += snprintf(__interp_buf__ + __interp_offset__, 65536 - __interp_offset__, \"")
              .append(fmt)
              .append("\", ")
              .append(code)
              .append(");\n");
        }
      }
    }

    indent(builder);
    builder.append("safe_arena_strdup(__interp_buf__);\n");

    indent--;
    indent(builder);
    builder.append("})");

    return builder.toString();
  }

  // Helper methods

  private String infer(final ASTNode node) {
    return inferer.infer(node);
  }

  private boolean isStringLike(final ASTNode node) {
    return inferer.isStringLike(node);
  }

  private String reference(final String name, final FunctionDeclarationNode declaration) {
    final var wrapper = "__ref_" + name;
    // Only generate wrapper once
    if (wrappers.stream().noneMatch(w -> w.contains(wrapper + "("))) {
      final var ctype = translate(declaration.returns().fullName());
      final var builder = new StringBuilder();
      builder.append("static ").append(ctype).append(" ").append(wrapper).append("(");
      final var params = declaration.parameters();
      for (int i = 0; i < params.size(); i++) {
        if (i > 0) builder.append(", ");
        builder
            .append(translate(params.get(i).type().fullName()))
            .append(" __p")
            .append(i)
            .append("__");
      }
      if (!params.isEmpty()) builder.append(", ");
      builder.append("void* __ctx) {\n");
      builder.append("    return ").append(mangleFunction(name)).append("(");
      for (int i = 0; i < params.size(); i++) {
        if (i > 0) builder.append(", ");
        builder.append("__p").append(i).append("__");
      }
      builder.append(");\n}");
      wrappers.add(builder.toString());
    }
    // Box the function-reference closure so it matches the SAFEClosure*
    // type that `fn<...>` now translates to (bug 006).
    return "safe_closure_box(safe_closure_new((void*)"
        + wrapper
        + ", NULL, "
        + declaration.parameters().size()
        + "))";
  }

  private List<String> params(final String type) {
    // fn<int, int, int> -> ["int", "int", "int"]
    if (type.startsWith("fn<") && type.endsWith(">")) {
      return split(type.substring(3, type.length() - 1)).stream()
          .map(String::trim)
          .collect(Collectors.toList());
    }
    return List.of();
  }

  private boolean isBooleanExpression(final ASTNode node) {
    if (node instanceof LiteralNode.BoolLiteral) {
      return true;
    }
    if (node instanceof VariableReferenceNode ref && ref.parts().size() == 1) {
      return "boolean".equals(variables().get(ref.parts().getFirst()));
    }
    if (node instanceof BinaryExpressionNode bin) {
      final var op = bin.operator();
      return "==".equals(op)
          || "!=".equals(op)
          || "<".equals(op)
          || "<=".equals(op)
          || ">".equals(op)
          || ">=".equals(op)
          || "&&".equals(op)
          || "||".equals(op)
          || "in".equals(op);
    }
    if (node instanceof UnaryExpressionNode unary) {
      return "!".equals(unary.operator());
    }
    if (node instanceof FunctionCallNode call) {
      final var name = call.name();
      return "starts".equals(name)
          || "ends".equals(name)
          || "matches".equals(name)
          || "isdir".equals(name)
          || "mkdir".equals(name)
          || "rmdir".equals(name)
          || "contains".equals(name)
          || "empty".equals(name)
          || "blank".equals(name)
          || "prime".equals(name);
    }
    return false;
  }

  private String escape(final String s) {
    return StringEscapes.cString(s);
  }

  private String format(final ASTNode node) {
    return formatResolver().format(node);
  }

  private String specifier(final String type) {
    return formatResolver().specifier(type);
  }

  private void line(final String line) {
    indent(output);
    output.append(line).append("\n");
  }

  private void raw(final String text) {
    output.append(text);
    if (!text.isEmpty() && !text.endsWith("\n")) {
      output.append("\n");
    }
  }

  private void indent(final StringBuilder builder) {
    builder.append("    ".repeat(Math.max(0, indent)));
  }

  private static final class Frame {
    final boolean scoped;
    final FunctionDeclarationNode function;
    final String name;
    final Map<String, String> variables;
    String module;

    Frame(
        final boolean scoped,
        final FunctionDeclarationNode function,
        final String name,
        final Map<String, String> variables,
        final String module) {
      this.scoped = scoped;
      this.function = function;
      this.name = name;
      this.variables = variables;
      this.module = module;
    }
  }

  /** Adapter exposing the visitor's read-only state to {@link CTypeInferer}. */
  private final class InferAdapter implements CInferContext {
    @Override
    public Map<String, String> variables() {
      return CCodeGenerator.this.variables();
    }

    @Override
    public Map<String, TypeDeclarationNode> structs() {
      return structs;
    }

    @Override
    public Map<String, EnumDeclarationNode> enumerations() {
      return enumerations;
    }

    @Override
    public Map<String, FunctionDeclarationNode> functions() {
      return functions;
    }

    @Override
    public Set<String> modules() {
      return modules;
    }

    @Override
    public ModuleRegistry registry() {
      return registry;
    }

    @Override
    public String currentModule() {
      return CCodeGenerator.this.currentModule();
    }

    @Override
    public String mangle(final String module, final String name) {
      return CCodeGenerator.this.mangle(module, name);
    }

    @Override
    public String valued(final String type) {
      return CCodeGenerator.this.valued(type);
    }

    @Override
    public String keyed(final String type) {
      return CCodeGenerator.this.keyed(type);
    }

    @Override
    public String inner(final String type) {
      return CCodeGenerator.this.inner(type);
    }

    @Override
    public String tuple(final String type, final ASTNode index) {
      return CCodeGenerator.this.tuple(type, index);
    }

    @Override
    public List<String> params(final String fnType) {
      return CCodeGenerator.this.params(fnType);
    }
  }

  /**
   * Adapter exposing the visitor's recursive-enum set + type translator to {@link CEnumGenerator}.
   */
  private final class EnumAdapter implements CEnumContext {
    @Override
    public void markRecursive(final String name) {
      recursive.add(name);
    }

    @Override
    public Set<String> recursive() {
      return recursive;
    }

    @Override
    public String translate(final String type) {
      return CCodeGenerator.this.translate(type);
    }

    @Override
    public boolean isHeapRc(final String type) {
      return CCodeGenerator.this.isHeapRc(type);
    }
  }

  /**
   * Adapter for {@link CCallCompiler}: function-call dispatch needs the widest context surface —
   * function/enum registries, scope, mangling, builtin resolver fallback, and the
   * chained-default-argument alias map.
   */
  private final class CallAdapter implements CCallContext {
    @Override
    public String emit(final ASTNode node) {
      return node.accept(CCodeGenerator.this);
    }

    @Override
    public String translate(final String type) {
      return CCodeGenerator.this.translate(type);
    }

    @Override
    public String infer(final ASTNode node) {
      return CCodeGenerator.this.infer(node);
    }

    @Override
    public boolean isFreshHeap(final ASTNode node) {
      return isHeapRc(CCodeGenerator.this.infer(node)) && isFreshRhs(node);
    }

    @Override
    public List<String> params(final String fnType) {
      return CCodeGenerator.this.params(fnType);
    }

    @Override
    public Map<String, String> variables() {
      return CCodeGenerator.this.variables();
    }

    @Override
    public Map<String, FunctionDeclarationNode> functions() {
      return functions;
    }

    @Override
    public Map<String, EnumDeclarationNode> enumerations() {
      return enumerations;
    }

    @Override
    public Set<String> imported() {
      return imported;
    }

    @Override
    public Set<String> modules() {
      return modules;
    }

    @Override
    public Set<String> emitted() {
      return emitted;
    }

    @Override
    public ModuleRegistry registry() {
      return registry;
    }

    @Override
    public String currentModule() {
      return CCodeGenerator.this.currentModule();
    }

    @Override
    public String mangle(final String module, final String name) {
      return CCodeGenerator.this.mangle(module, name);
    }

    @Override
    public String mangle(final String name) {
      return CCodeGenerator.this.mangleFunction(name);
    }

    @Override
    public String resolveBuiltin(final String name, final List<ASTNode> arguments) {
      return gatedResolve(name, arguments);
    }

    @Override
    public String wrapStructArgForCall(final String argCode, final ASTNode argNode) {
      return CCodeGenerator.this.wrapStructArgForCall(argCode, argNode);
    }

    @Override
    public Map<String, String> aliases() {
      return aliases;
    }

    @Override
    public void aliases(final Map<String, String> active) {
      aliases = active;
    }
  }

  /**
   * Adapter for {@link CIndexCompiler}: index access/assignment needs map/list type-string
   * utilities + the current scope's variable map.
   */
  private final class IndexAdapter implements CIndexContext {
    @Override
    public String emit(final ASTNode node) {
      return node.accept(CCodeGenerator.this);
    }

    @Override
    public boolean isHeapRc(final String type) {
      return CCodeGenerator.this.isHeapRc(type);
    }

    @Override
    public String infer(final ASTNode node) {
      return CCodeGenerator.this.infer(node);
    }

    @Override
    public String translate(final String type) {
      return CCodeGenerator.this.translate(type);
    }

    @Override
    public String unwrap(final String code, final String type) {
      return CCodeGenerator.this.unwrap(code, type);
    }

    @Override
    public String inner(final String type) {
      return CCodeGenerator.this.inner(type);
    }

    @Override
    public String valued(final String type) {
      return CCodeGenerator.this.valued(type);
    }

    @Override
    public String keyed(final String type) {
      return CCodeGenerator.this.keyed(type);
    }

    @Override
    public String tuple(final String type, final ASTNode index) {
      return CCodeGenerator.this.tuple(type, index);
    }

    @Override
    public String putter(final String key) {
      return CCodeGenerator.this.putter(key);
    }

    @Override
    public String getter(final String key) {
      return CCodeGenerator.this.getter(key);
    }

    @Override
    public boolean isPointerType(final String type) {
      return CCodeGenerator.this.isPointerType(type);
    }

    @Override
    public boolean isStruct(final String type) {
      return structs.containsKey(type);
    }

    @Override
    public Map<String, String> variables() {
      return CCodeGenerator.this.variables();
    }

    @Override
    public Map<String, EnumDeclarationNode> enumerations() {
      return enumerations;
    }

    @Override
    public Set<String> recursive() {
      return recursive;
    }
  }

  /**
   * Adapter for {@link CCaseCompiler}: pattern matching needs to bind variables into the current
   * scope and look up enum metadata.
   */
  private final class CaseAdapter implements CCaseContext {
    @Override
    public String emit(final ASTNode node) {
      return node.accept(CCodeGenerator.this);
    }

    @Override
    public String user(final String name) {
      return mangler.user(name);
    }

    @Override
    public String infer(final ASTNode node) {
      return CCodeGenerator.this.infer(node);
    }

    @Override
    public String translate(final String type) {
      return CCodeGenerator.this.translate(type);
    }

    @Override
    public Map<String, String> variables() {
      return CCodeGenerator.this.variables();
    }

    @Override
    public Map<String, EnumDeclarationNode> enumerations() {
      return enumerations;
    }

    @Override
    public Set<String> recursive() {
      return recursive;
    }
  }

  /**
   * Adapter for {@link CForCompiler}: for-loop emission needs scope binding + the same
   * emit/infer/predicates as the collection emitter.
   */
  private final class ForAdapter implements CForContext {
    @Override
    public String emit(final ASTNode node) {
      return node.accept(CCodeGenerator.this);
    }

    @Override
    public String user(final String name) {
      return mangler.user(name);
    }

    @Override
    public boolean isHeapRc(final String type) {
      return CCodeGenerator.this.isHeapRc(type);
    }

    @Override
    public boolean inTopLevel() {
      return CCodeGenerator.this.currentModule() == null;
    }

    @Override
    public String releaseForLocal(final String name, final String type) {
      return CCodeGenerator.this.releaseForLocal(name, type);
    }

    @Override
    public String infer(final ASTNode node) {
      return CCodeGenerator.this.infer(node);
    }

    @Override
    public String translate(final String type) {
      return CCodeGenerator.this.translate(type);
    }

    @Override
    public String keyed(final String type) {
      return CCodeGenerator.this.keyed(type);
    }

    @Override
    public boolean isPointerType(final String type) {
      return CCodeGenerator.this.isPointerType(type);
    }

    @Override
    public boolean isFunctionType(final String type) {
      return CCodeGenerator.this.isFunctionType(type);
    }

    @Override
    public boolean isStruct(final String type) {
      return structs.containsKey(type);
    }

    @Override
    public Set<String> recursive() {
      return recursive;
    }

    @Override
    public Map<String, EnumDeclarationNode> enumerations() {
      return enumerations;
    }

    @Override
    public Map<String, String> variables() {
      return CCodeGenerator.this.variables();
    }

    @Override
    public void indent(final StringBuilder builder) {
      CCodeGenerator.this.indent(builder);
    }

    @Override
    public void indentInc() {
      indent++;
    }

    @Override
    public void indentDec() {
      indent--;
    }
  }

  /**
   * Adapter for {@link CCollectionEmitter}: collection literal emission needs the same type-aware
   * emit/infer/wrap/predicates plus the visitor's mutable indent counter.
   */
  private final class CollectionAdapter implements CCollectionContext {
    @Override
    public String emit(final ASTNode node) {
      return node.accept(CCodeGenerator.this);
    }

    @Override
    public String infer(final ASTNode node) {
      return CCodeGenerator.this.infer(node);
    }

    @Override
    public String wrap(final String code, final String type) {
      return CCodeGenerator.this.wrap(code, type);
    }

    @Override
    public String translate(final String type) {
      return CCodeGenerator.this.translate(type);
    }

    @Override
    public String keyed(final String type) {
      return CCodeGenerator.this.keyed(type);
    }

    @Override
    public String valued(final String type) {
      return CCodeGenerator.this.valued(type);
    }

    @Override
    public String inner(final String type) {
      return CCodeGenerator.this.inner(type);
    }

    @Override
    public String safeKindOf(final String type) {
      return CCodeGenerator.this.safeKindOf(type);
    }

    @Override
    public String putter(final String key) {
      return CCodeGenerator.this.putter(key);
    }

    @Override
    public boolean isPointerType(final String type) {
      return CCodeGenerator.this.isPointerType(type);
    }

    @Override
    public boolean isFunctionType(final String type) {
      return CCodeGenerator.this.isFunctionType(type);
    }

    @Override
    public boolean isStruct(final String type) {
      return structs.containsKey(type);
    }

    @Override
    public boolean isRecursive(final String type) {
      return recursive.contains(type);
    }

    @Override
    public Map<String, EnumDeclarationNode> enumerations() {
      return enumerations;
    }

    @Override
    public void indent(final StringBuilder builder) {
      CCodeGenerator.this.indent(builder);
    }

    @Override
    public void indentInc() {
      indent++;
    }

    @Override
    public void indentDec() {
      indent--;
    }
  }

  /**
   * Adapter for {@link CBuiltinResolver}: gives the builtin dispatch helper read-only access to
   * type inference, recursive emit, value wrapping, and the format helpers.
   */
  private final class BuiltinAdapter implements CBuiltinContext {
    @Override
    public String emit(final ASTNode node) {
      final var override = emitOverrides.get(node);
      if (override != null) return override;
      return node.accept(CCodeGenerator.this);
    }

    @Override
    public String infer(final ASTNode node) {
      return CCodeGenerator.this.infer(node);
    }

    @Override
    public boolean isFreshHeap(final ASTNode node) {
      return isHeapRc(CCodeGenerator.this.infer(node)) && isFreshRhs(node);
    }

    @Override
    public String wrap(final String code, final String type) {
      return CCodeGenerator.this.wrap(code, type);
    }

    @Override
    public String valued(final String type) {
      return CCodeGenerator.this.valued(type);
    }

    @Override
    public String keyed(final String type) {
      return CCodeGenerator.this.keyed(type);
    }

    @Override
    public boolean isPointerType(final String type) {
      return CCodeGenerator.this.isPointerType(type);
    }

    @Override
    public boolean isIntegerKeyed(final String key) {
      return CCodeGenerator.this.isIntegerKeyed(key);
    }

    @Override
    public boolean isFloatKeyed(final String key) {
      return CCodeGenerator.this.isFloatKeyed(key);
    }

    @Override
    public boolean isGenericType(final String type) {
      return CCodeGenerator.this.isGenericType(type);
    }

    @Override
    public String safeKindOf(final String type) {
      return CCodeGenerator.this.safeKindOf(type);
    }

    @Override
    public Map<String, EnumDeclarationNode> enumerations() {
      return enumerations;
    }

    @Override
    public String format(final ASTNode node) {
      return CCodeGenerator.this.format(node);
    }

    @Override
    public boolean isBooleanExpression(final ASTNode node) {
      return CCodeGenerator.this.isBooleanExpression(node);
    }

    @Override
    public String escape(final String text) {
      return CCodeGenerator.this.escape(text);
    }

    @Override
    public boolean stringifiable(final String type) {
      return CCodeGenerator.this.stringifiable(type);
    }

    @Override
    public String stringify(final String code, final String type) {
      return CCodeGenerator.this.stringify(code, type);
    }
  }

  /** Adapter exposing CCodeGenerator's translate/struct state to {@link CBoxing}. */
  private final class BoxingAdapter implements CBoxingContext {
    @Override
    public String translate(final String type) {
      return CCodeGenerator.this.translate(type);
    }

    @Override
    public Map<String, TypeDeclarationNode> structs() {
      return structs;
    }
  }
}
