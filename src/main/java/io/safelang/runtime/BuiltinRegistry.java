package io.safelang.runtime;

import io.safelang.ast.*;
import java.util.*;

public final class BuiltinRegistry {

  private static final Map<String, Builtin> BY_NAME = new LinkedHashMap<>();
  private static final Map<Integer, Builtin> BY_ID = new LinkedHashMap<>();
  private static final Map<String, SAFEValue> SYSTEM_VARIABLES = new LinkedHashMap<>();

  static {
    final var T = variable("T");
    final var K = variable("K");
    final var V = variable("V");

    // io (3) — all impure (I/O)
    observable(0, "print", "io", List.of(param("x", variable("T"))), type("void"));
    observable(1, "println", "io", List.of(param("x", variable("T"))), type("void"));
    nondeterministic(41, "input", "io", List.of(param("prompt", type("string"))), type("string"));

    // math (11)
    mathFloat(12, "sqrt");
    register(
        13,
        "pow",
        "math",
        List.of(param("base", type("float")), param("exponent", type("float"))),
        type("float"));
    register(
        14,
        "abs",
        "math",
        List.of(param("x", union(type("int"), type("float")))),
        union(type("int"), type("float")));
    register(
        15,
        "min",
        "math",
        List.of(
            param("a", union(type("int"), type("float"))),
            param("b", union(type("int"), type("float")))),
        union(type("int"), type("float")));
    register(
        16,
        "max",
        "math",
        List.of(
            param("a", union(type("int"), type("float"))),
            param("b", union(type("int"), type("float")))),
        union(type("int"), type("float")));
    mathToInt(17, "floor");
    mathToInt(18, "ceil");
    mathToInt(19, "round");
    mathFloat(20, "log");
    mathFloat(21, "sin");
    mathFloat(22, "cos");

    // strings (12)
    register(
        23,
        "substring",
        "strings",
        List.of(param("s", type("string")), param("start", type("int")), param("end", type("int"))),
        type("string"));
    register(
        24,
        "indexOf",
        "strings",
        List.of(param("s", type("string")), param("target", type("string"))),
        type("int"));
    register(
        25,
        "charAt",
        "strings",
        List.of(param("s", type("string")), param("index", type("int"))),
        type("string"));
    register(
        26,
        "split",
        "strings",
        List.of(param("s", type("string")), param("delimiter", type("string"))),
        generic("list", type("string")));
    stringUnary(27, "trim");
    stringUnary(28, "upper");
    stringUnary(29, "lower");
    register(
        30,
        "replace",
        "strings",
        List.of(
            param("s", type("string")),
            param("target", type("string")),
            param("replacement", type("string"))),
        type("string"));
    stringPredicate(31, "starts");
    stringPredicate(32, "ends");
    register(
        33,
        "join",
        "strings",
        List.of(
            param("items", generic("list", type("string"))), param("separator", type("string"))),
        type("string"));
    register(
        34,
        "chars",
        "strings",
        List.of(param("s", type("string"))),
        generic("list", type("string")));

    // file (14) — all impure (filesystem I/O)
    nondeterministic(35, "read", "file", List.of(param("path", type("string"))), type("string"));
    nondeterministic(
        36,
        "write",
        "file",
        List.of(param("path", type("string")), param("content", type("string"))),
        type("void"));
    nondeterministic(
        37,
        "appendfile",
        "file",
        List.of(param("path", type("string")), param("content", type("string"))),
        type("void"));
    nondeterministic(38, "exists", "file", List.of(param("path", type("string"))), type("boolean"));
    nondeterministic(39, "delete", "file", List.of(param("path", type("string"))), type("boolean"));
    nondeterministic(
        40,
        "lines",
        "file",
        List.of(param("path", type("string"))),
        generic("list", type("string")));
    nondeterministic(
        50,
        "fileopen",
        "file",
        List.of(param("path", type("string")), param("mode", type("string"))),
        type("OpenResult"));
    nondeterministic(51, "fileclose", "file", List.of(param("handle", type("int"))), type("void"));
    nondeterministic(
        52, "fileread", "file", List.of(param("handle", type("int"))), type("ReadResult"));
    nondeterministic(
        53,
        "filewrite",
        "file",
        List.of(param("handle", type("int")), param("content", type("string"))),
        type("WriteResult"));
    nondeterministic(
        54, "filereadlines", "file", List.of(param("handle", type("int"))), type("LinesResult"));
    nondeterministic(
        55, "filevalid", "file", List.of(param("handle", type("int"))), type("boolean"));
    nondeterministic(
        56, "fileload", "file", List.of(param("path", type("string"))), type("ReadResult"));
    nondeterministic(
        57,
        "filesave",
        "file",
        List.of(param("path", type("string")), param("content", type("string"))),
        type("WriteResult"));

    // collections (9)
    register(
        7,
        "append",
        "collections",
        List.of(param("items", generic("list", variable("T"))), param("element", variable("T"))),
        generic("list", variable("T")));
    register(
        8,
        "keys",
        "collections",
        List.of(param("m", generic("map", variable("K"), variable("V")))),
        generic("list", variable("K")));
    register(
        9,
        "values",
        "collections",
        List.of(param("m", generic("map", variable("K"), variable("V")))),
        generic("list", variable("V")));
    register(
        10,
        "contains",
        "collections",
        List.of(
            param(
                "collection",
                union(
                    generic("map", variable("K"), variable("V")),
                    generic("list", variable("T")),
                    generic("set", variable("T")))),
            param("key", variable("K"))),
        type("boolean"));
    register(
        11,
        "size",
        "collections",
        List.of(
            param(
                "collection",
                union(
                    generic("list", variable("T")),
                    type("string"),
                    generic("map", variable("K"), variable("V")),
                    generic("set", variable("T"))))),
        type("int"));
    register(
        46,
        "remove",
        "collections",
        List.of(param("items", generic("list", variable("T"))), param("index", type("int"))),
        generic("list", variable("T")));
    register(
        47,
        "slice",
        "collections",
        List.of(
            param("items", generic("list", variable("T"))),
            param("start", type("int")),
            param("end", type("int"))),
        generic("list", variable("T")));
    register(
        48,
        "reverse",
        "collections",
        List.of(param("items", generic("list", variable("T")))),
        generic("list", variable("T")));
    register(
        49,
        "sort",
        "collections",
        List.of(param("items", generic("list", variable("T")))),
        generic("list", variable("T")));

    // std (10)
    register(4, "str", "std", List.of(param("x", variable("T"))), type("string"));
    register(
        2,
        "len",
        "std",
        List.of(
            param(
                "collection",
                union(
                    generic("list", variable("T")),
                    type("string"),
                    generic("map", variable("K"), variable("V")),
                    generic("set", variable("T")),
                    type("bytes")))),
        type("int"));
    register(
        3,
        "range",
        "std",
        List.of(param("start", type("int")), param("end", type("int"))),
        generic("list", type("int")),
        1);
    register(45, "typeof", "std", List.of(param("x", variable("T"))), type("string"));
    nondeterministic(44, "time", "std", List.of(), type("int"));
    register(43, "args", "std", List.of(), generic("list", type("string")));
    nondeterministic(42, "exit", "std", List.of(param("code", type("int"))), type("void"));
    register(5, "int", "std", List.of(param("x", variable("T"))), type("int"));
    register(6, "float", "std", List.of(param("x", variable("T"))), type("float"));

    // Aliases
    alias("integer", 5);
    alias("decimal", 6);

    // sets (5)
    register(
        58,
        "add",
        "collections",
        List.of(
            param("collection", generic("set", variable("T"))), param("element", variable("T"))),
        generic("set", variable("T")));
    register(
        59,
        "union",
        "collections",
        List.of(
            param("a", generic("set", variable("T"))), param("b", generic("set", variable("T")))),
        generic("set", variable("T")));
    register(
        60,
        "intersect",
        "collections",
        List.of(
            param("a", generic("set", variable("T"))), param("b", generic("set", variable("T")))),
        generic("set", variable("T")));
    register(
        61,
        "difference",
        "collections",
        List.of(
            param("a", generic("set", variable("T"))), param("b", generic("set", variable("T")))),
        generic("set", variable("T")));

    // math trig/exp (7) — B1
    mathFloat(62, "tan");
    mathFloat(63, "asin");
    mathFloat(64, "acos");
    mathFloat(65, "atan");
    register(
        66,
        "atan2",
        "math",
        List.of(param("y", type("float")), param("x", type("float"))),
        type("float"));
    mathFloat(67, "exp");
    mathFloat(68, "log10");

    // random (3) — B2 — impure (non-deterministic)
    nondeterministic(69, "rand", "math", List.of(), type("float"));
    nondeterministic(
        70,
        "randint",
        "math",
        List.of(param("low", type("int")), param("high", type("int"))),
        type("int"));
    nondeterministic(71, "seed", "math", List.of(param("n", type("int"))), type("void"));

    // regex (3) — B4
    register(
        72,
        "matches",
        "strings",
        List.of(param("s", type("string")), param("pattern", type("string"))),
        type("boolean"));
    register(
        73,
        "findall",
        "strings",
        List.of(param("s", type("string")), param("pattern", type("string"))),
        generic("list", type("string")));
    register(
        74,
        "replaceall",
        "strings",
        List.of(
            param("s", type("string")),
            param("pattern", type("string")),
            param("replacement", type("string"))),
        type("string"));

    // directory ops (4) — B5 — impure (filesystem I/O)
    nondeterministic(
        75,
        "listdir",
        "file",
        List.of(param("path", type("string"))),
        generic("list", type("string")));
    nondeterministic(76, "mkdir", "file", List.of(param("path", type("string"))), type("boolean"));
    nondeterministic(77, "rmdir", "file", List.of(param("path", type("string"))), type("boolean"));
    nondeterministic(78, "isdir", "file", List.of(param("path", type("string"))), type("boolean"));

    // binary bytes ops (12) — IDs 79-90
    register(79, "balloc", "binary", List.of(param("size", type("int"))), type("bytes"));
    register(
        80,
        "bget",
        "binary",
        List.of(param("b", type("bytes")), param("index", type("int"))),
        type("int"));
    register(
        81,
        "bset",
        "binary",
        List.of(
            param("b", type("bytes")), param("index", type("int")), param("value", type("int"))),
        type("bytes"));
    register(
        82,
        "bslice",
        "binary",
        List.of(param("b", type("bytes")), param("start", type("int")), param("end", type("int"))),
        type("bytes"));
    register(
        83,
        "bconcat",
        "binary",
        List.of(param("a", type("bytes")), param("b", type("bytes"))),
        type("bytes"));
    register(84, "bencode", "binary", List.of(param("s", type("string"))), type("bytes"));
    register(85, "bdecode", "binary", List.of(param("b", type("bytes"))), type("string"));
    register(
        86,
        "bpack",
        "binary",
        List.of(param("value", type("int")), param("width", type("int"))),
        type("bytes"));
    register(
        87,
        "bunpack",
        "binary",
        List.of(
            param("b", type("bytes")), param("offset", type("int")), param("width", type("int"))),
        type("int"));
    register(
        88,
        "bpatch",
        "binary",
        List.of(
            param("b", type("bytes")), param("offset", type("int")), param("data", type("bytes"))),
        type("bytes"));
    register(
        89,
        "bcompare",
        "binary",
        List.of(param("a", type("bytes")), param("b", type("bytes"))),
        type("int"));
    register(90, "bhex", "binary", List.of(param("b", type("bytes"))), type("string"));

    // binary file I/O (7) — IDs 91-97 — all impure
    nondeterministic(
        91,
        "bopen",
        "binary",
        List.of(param("path", type("string")), param("mode", type("string"))),
        type("int"));
    nondeterministic(92, "bclose", "binary", List.of(param("handle", type("int"))), type("void"));
    nondeterministic(
        93,
        "bread",
        "binary",
        List.of(param("handle", type("int")), param("count", type("int"))),
        type("bytes"));
    nondeterministic(
        94,
        "bwrite",
        "binary",
        List.of(param("handle", type("int")), param("data", type("bytes"))),
        type("int"));
    nondeterministic(
        95,
        "bseek",
        "binary",
        List.of(param("handle", type("int")), param("offset", type("int"))),
        type("void"));
    nondeterministic(96, "bsize", "binary", List.of(param("path", type("string"))), type("int"));
    nondeterministic(97, "bflush", "binary", List.of(param("handle", type("int"))), type("void"));

    // hash (3) — IDs 98-100
    register(98, "fnv", "hash", List.of(param("b", type("bytes"))), type("int"));
    register(99, "crc", "hash", List.of(param("b", type("bytes"))), type("int"));
    register(100, "murmur", "hash", List.of(param("b", type("bytes"))), type("int"));

    // env (1) — ID 101
    nondeterministic(101, "getenv", "env", List.of(param("name", type("string"))), type("string"));

    // System variables
    variable("MAX_LIST_SIZE", SAFEValue.ofInt(SAFEValue.MAX_LIST_SIZE));
    variable("MAX_TUPLE_SIZE", SAFEValue.ofInt(SAFEValue.MAX_TUPLE_SIZE));
    variable("VERSION", SAFEValue.ofString("1.0"));
    variable("PLATFORM", SAFEValue.ofString(System.getProperty("os.name")));
    variable("OS", SAFEValue.ofString(System.getProperty("os.name")));
    variable("ARCH", SAFEValue.ofString(System.getProperty("os.arch")));
    variable("OS_VERSION", SAFEValue.ofString(System.getProperty("os.version")));
  }

  private BuiltinRegistry() {}

  public static Builtin get(final String name) {
    return BY_NAME.get(name);
  }

  public static Builtin get(final int id) {
    return BY_ID.get(id);
  }

  public static boolean isBuiltin(final String name) {
    return BY_NAME.containsKey(name);
  }

  public static int id(final String name) {
    final var builtin = BY_NAME.get(name);
    return builtin != null ? builtin.id() : -1;
  }

  public static String name(final int id) {
    final var builtin = BY_ID.get(id);
    return builtin != null ? builtin.name() : "unknown_builtin_" + id;
  }

  public static int arity(final int id) {
    final var builtin = BY_ID.get(id);
    return builtin != null ? builtin.signature().parameters().size() : -1;
  }

  public static int minimum(final int id) {
    final var builtin = BY_ID.get(id);
    return builtin != null ? builtin.minimum() : -1;
  }

  public static String module(final String name) {
    final var builtin = BY_NAME.get(name);
    return builtin != null ? builtin.module() : "unknown";
  }

  /** Return the return type name of a builtin, or null if not found. */
  public static String returns(final String name) {
    final var builtin = BY_NAME.get(name);
    if (builtin == null) return null;
    final var signature = builtin.signature();
    return signature.returns() != null ? signature.returns().name() : "void";
  }

  public static FunctionDeclarationNode signature(final String name) {
    final var builtin = BY_NAME.get(name);
    return builtin != null ? builtin.signature() : null;
  }

  public static Collection<Builtin> all() {
    return Collections.unmodifiableCollection(BY_ID.values());
  }

  public static void variable(final String name, final SAFEValue value) {
    SYSTEM_VARIABLES.put(name, value);
  }

  public static Map<String, SAFEValue> variables() {
    return Collections.unmodifiableMap(SYSTEM_VARIABLES);
  }

  private static TypeNode type(final String name) {
    return new TypeNode(0, 0, name);
  }

  private static TypeNode generic(final String name, final TypeNode... parameters) {
    return TypeNode.withParameters(0, 0, name, List.of(parameters));
  }

  private static TypeNode variable(final String name) {
    return TypeNode.withVariable(0, 0, name);
  }

  private static TypeNode union(final TypeNode... parts) {
    return TypeNode.withMembers(0, 0, List.of(parts));
  }

  private static ParameterNode param(final String name, final TypeNode type) {
    return new ParameterNode(0, 0, type, name);
  }

  /**
   * True only for fully pure builtins (no side effects, no external state). Used by code paths that
   * need exactly the deterministic-without-effects subset.
   */
  public static boolean isPure(final String name) {
    final var builtin = BY_NAME.get(name);
    return builtin == null || builtin.purity() == Builtin.Purity.PURE;
  }

  /**
   * True for builtins that strict mode admits — {@link Builtin.Purity#PURE} <em>or</em> {@link
   * Builtin.Purity#OBSERVABLE}. The latter covers {@code print}/{@code println}, which produce
   * visible output but are deterministic functions of their arguments. The strict-mode purity
   * checker (see {@code PurityChecker}) consults this method instead of {@link #isPure} so that
   * hello-world programs can run under {@code --strict} without forcing the user to opt out via
   * {@code --permissive}.
   */
  public static boolean isStrictAllowed(final String name) {
    final var builtin = BY_NAME.get(name);
    return builtin == null || builtin.purity() != Builtin.Purity.NONDETERMINISTIC;
  }

  private static void mathFloat(final int id, final String name) {
    register(id, name, "math", List.of(param("x", type("float"))), type("float"));
  }

  private static void mathToInt(final int id, final String name) {
    register(id, name, "math", List.of(param("x", type("float"))), type("int"));
  }

  private static void stringUnary(final int id, final String name) {
    register(id, name, "strings", List.of(param("s", type("string"))), type("string"));
  }

  private static void stringPredicate(final int id, final String name) {
    register(
        id,
        name,
        "strings",
        List.of(param("s", type("string")), param("t", type("string"))),
        type("boolean"));
  }

  private static void register(
      final int id,
      final String name,
      final String module,
      final List<ParameterNode> parameters,
      final TypeNode returns) {
    register(id, name, module, parameters, returns, parameters.size(), Builtin.Purity.PURE);
  }

  private static void register(
      final int id,
      final String name,
      final String module,
      final List<ParameterNode> parameters,
      final TypeNode returns,
      final int minimum) {
    register(id, name, module, parameters, returns, minimum, Builtin.Purity.PURE);
  }

  /**
   * Register a builtin with an observable side effect (e.g. {@code println} writes to stdout) but
   * deterministic behaviour. Allowed under strict mode.
   */
  private static void observable(
      final int id,
      final String name,
      final String module,
      final List<ParameterNode> parameters,
      final TypeNode returns) {
    register(id, name, module, parameters, returns, parameters.size(), Builtin.Purity.OBSERVABLE);
  }

  /**
   * Register a builtin that is non-deterministic or external-state-dependent ({@code time}, {@code
   * rand}, {@code file:*}, {@code input}, {@code getenv}, {@code exit}, etc.). Rejected under
   * strict mode.
   */
  private static void nondeterministic(
      final int id,
      final String name,
      final String module,
      final List<ParameterNode> parameters,
      final TypeNode returns) {
    register(
        id, name, module, parameters, returns, parameters.size(), Builtin.Purity.NONDETERMINISTIC);
  }

  private static void nondeterministic(
      final int id,
      final String name,
      final String module,
      final List<ParameterNode> parameters,
      final TypeNode returns,
      final int minimum) {
    register(id, name, module, parameters, returns, minimum, Builtin.Purity.NONDETERMINISTIC);
  }

  private static void register(
      final int id,
      final String name,
      final String module,
      final List<ParameterNode> parameters,
      final TypeNode returns,
      final int minimum,
      final Builtin.Purity purity) {
    if (BY_ID.containsKey(id)) {
      throw new IllegalStateException(
          "Duplicate builtin ID " + id + ": " + name + " conflicts with " + BY_ID.get(id).name());
    }
    final var signature = new FunctionDeclarationNode(0, 0, returns, name, parameters, List.of());
    final var builtin = new Builtin(id, name, module, signature, minimum, purity);
    BY_NAME.put(name, builtin);
    BY_ID.put(id, builtin);
  }

  private static void alias(final String alias, final int target) {
    final var original = BY_ID.get(target);
    if (original != null) {
      BY_NAME.put(alias, original);
    }
  }
}
