# SAFE C Backend

This document describes the C code-generation backend (`compiler/c/`). It lowers a SAFE program to a single,
self-contained C source file and — via the host C compiler — to a native executable.

> **CLI invocation.** Examples use a short `safe <subcommand>` form for readability. The actual command is
> `java -jar target/safe-lang-1.0.0-cli.jar <subcommand>`; alias `safe` to that in your shell if you want the
> short form to work literally.

## Commands

| Command                   | Input       | Output          | Description                                         |
|---------------------------|-------------|-----------------|-----------------------------------------------------|
| `safe compile file.safe`  | Source code | `file.c`        | Emit self-contained C source                        |
| `safe build file.safe`    | Source code | native binary   | Emit C, then invoke the host C compiler             |
| `safe test --native tests/` | Test files | stdout         | Run the SAFE test suite through the C backend       |

```bash
# Emit C source only
safe compile examples/fibonacci.safe          # writes fibonacci.c

# Emit C and compile to a native binary
safe build examples/fibonacci.safe
./fibonacci

# Run the native test suite through the C backend
safe test --native tests/
```

`build` writes the `.c` file, places the runtime headers beside it (see below), and compiles. The generated C
`#include`s `safe_runtime.h`, which the compiler resolves relative to the source file's directory.

---

## Pipeline

```
Source (.safe) → Lexer → Parser → AST
                                    │
                                    ▼
                            CCodeGenerator         (AST → self-contained C source)
                                    │
                                    ▼
                              .c source file        (#include "safe_runtime.h")
                                    │
                                    ▼
                             CBuildDriver           (spawns gcc/clang)
                                    │
                                    ▼
                            Native executable
```

CLI wiring: `SafeMain` (`compile`/`build`) → `SafeRuntime.compile` / `SafeRuntime.build` → `CCompiler` (codegen) and
`CBuildDriver` (native build).

---

## Design

### One self-contained C file

`CCodeGenerator` (`ASTVisitor<String>`) emits the whole program — all user functions, every reached module function,
type and enum definitions, globals, and `main` — into a **single** `.c` file. Imported module declarations are flattened
into that one translation unit at compile time; there are no separate compilation units. The only external dependency is
the header-only runtime, `safe_runtime.h` (and `safe_refcount.h`), which `SafeMain.extractRuntime` writes next to the
generated source.

### The C runtime: a tagged `SAFEValue` model

Heap values carry an 8-byte `SAFEHeader` (reference count, kind, size class, and a `meta` word used for element kind or a
heap-field bitmap). SAFE types map onto C types as follows:

| SAFE type      | C type                                  |
|----------------|-----------------------------------------|
| `int`          | `int64_t`                               |
| `uint`         | `uint64_t`                              |
| `float`        | `double`                                |
| `string`       | `char*`                                 |
| `boolean`      | `bool`                                  |
| `list<T>`      | `SAFEList*`                             |
| `map<K,V>`     | `SAFEMap*`                              |
| `set<T>`       | `SAFESet*`                              |
| tuple          | `SAFETuple` (stack value)               |
| `fn(...)`      | `SAFEClosure*` (heap-boxed for capture) |
| union `T\|U`   | `int64_t` (or `double` if `float` present) |

`SAFEList`/`SAFEMap`/`SAFESet` are runtime structs; maps preserve insertion order. The type mapping is done by
`CTypeMapper`; element/return types are inferred by `CTypeInferer`.

### Reference counting, not GC

The runtime (`safe_refcount.h`) uses deterministic reference counting: a fresh allocation starts at `refs = 1`;
assignments retain the new value and release the old; typed containers retain heap elements on insert and release them on
disposal. String literals are marked immortal (retain/release are no-ops). When a count reaches zero, `safe_dispose`
releases children and frees the block. There is no garbage collector.

### Host-compiler resolution and flags

`CBuildDriver` is the single source of truth for native builds. It resolves the compiler as `safe.cc` system property →
`SAFE_CC` environment variable → `gcc`, so you can point it at `clang`, `cc`, or a cross-compiler. The invocation is:

```
<compiler> -O2 -o <binary> <source.c> -lm
```

stdout and stderr are merged and captured; a non-zero exit raises an error that includes the compiler output. Both
`safe build` and the `--native` test runner route through this one method so their behavior cannot drift.

---

## Components

| File                  | Responsibility                                                                         |
|-----------------------|----------------------------------------------------------------------------------------|
| `CCodeGenerator`      | The compiler: `ASTVisitor<String>` lowering the whole program to one C file.            |
| `CCompiler`           | `SafeCompiler` adapter: drives codegen, writes the `.c` file, extracts runtime headers. |
| `CBuildDriver`        | Resolves the host compiler and invokes it; single source of truth for native builds.    |
| `CTypeMapper`         | Maps SAFE types to C types.                                                             |
| `CTypeInferer`        | Infers expression/return types for code generation.                                    |
| `CEnumGenerator`      | Emits enum tag/union types and per-variant constructors.                                |
| `CBuiltinResolver`    | Translates builtins to runtime calls (`print`, `len`, `str`, string/collection/math, file I/O). |
| `CFormatResolver`     | Chooses `printf` format specifiers from inferred types.                                  |
| `CCollectionEmitter`  | Emits list/map/set literal construction with element boxing.                            |
| `CForCompiler` / `CCaseCompiler` / `CIndexCompiler` / `CCallCompiler` / `CLambdaCompiler` | Lower for-loops, case expressions, indexing, calls, and lambdas. |
| `CBoxing`             | Boxes/unboxes scalars for storage in generic containers.                                |
| `CNameMangler`        | Mangles module + function names into C identifiers.                                     |
| `safe_runtime.h` / `safe_refcount.h` | Header-only C runtime (values, collections, reference counting). |

---

## Modules and builtins

Imported modules are flattened into the single output file. Module functions are forward-declared and emitted on demand
when referenced; module types, enums, and constants are compiled in as needed. Selective imports filter which symbols are
pulled in. Names are mangled (module + symbol) by `CNameMangler` to avoid collisions.

### Collection element storage

List elements live in a `void*` slot, so value-typed elements are boxed. `CCollectionEmitter` heap-copies structs
(`SAFE_KIND_OBJECT`), non-recursive enums (`SAFE_KIND_ENUM`), and tuples (`SAFE_KIND_TUPLE`) into a pointer; recursive
enums are already pointers and stored directly; scalars are boxed into `int64_t*`/`double*`; strings, lists, maps, sets,
and closures are stored as their pointer. The readers — `CIndexCompiler` (indexing and assignment) and `CForCompiler`
(iteration) — mirror that layout, so `list<Point>`, `list<(int, string)>`, `list<Shape>`, and nested
`list<list<Point>>` construct, index, iterate, assign, and print correctly.

Builtins are translated to direct runtime calls by `CBuiltinResolver` — for example `print`/`println` become `printf`
with a format specifier chosen by `CFormatResolver`; `len`, `str`, `range`, the string and collection operations, the
math functions, and file I/O each map to a `safe_*` runtime function.

### Value stringification

`print`, `println`, `str`, and string interpolation render every value exactly as the interpreter's
`SAFEValue.asString` does. Scalars use `safe_string_val*` (floats via `safe_double_to_string`, which reproduces Java's
`Double.toString` — shortest round-tripping decimal, always with a fractional digit, scientific form outside
`[1e-3, 1e7)`). Scalar lists and ranges go through `safe_list_to_string`. Compound values — structs, enums, tuples,
maps, and nested lists — are rendered by **recursive stringifier functions generated on demand**: the code generator
emits one `safe_str__<type>` per compound type (the runtime cannot recover struct/enum layouts itself), each recursing
through its element/field types and joining the pieces with `safe_join`/`safe_concat`. Output matches the interpreter:
`Point { x: 3, y: 4 }`, `Shape.Circle(2.5)`, `(1, a)`, `{a: 1, b: 2}`, `[[1, 2], [3, 4]]`.

---

## Contracts and `decreases`

Contracts compile to inline checks that abort the process on violation (a SAFE trap is terminal):

- `requires` is emitted after argument binding, before the body: `if (!(cond)) { fprintf(stderr, "Precondition failed\n"); exit(1); }`.
- `ensures` is emitted on every return path, after the body computes the result.
- `decreases(expr)` evaluates the measure into an `int64_t`, checks it is non-negative and **strictly less** than the
  previous measure on a per-function decreases stack, and pushes it; the entry is popped on every return path. This
  mirrors the interpreter, VM, and JVM backends.

A recursion guard (`safe_check_recursion`) additionally protects against native stack overflow.

---

## Known limitations

These are inherent to the C backend:

- **Sets are not stringified:** a printed `set<T>` still shows a pointer value (sets are otherwise outside the common
  print path).
- **C keyword conflicts:** SAFE function names that match C keywords (e.g. `double`) cause C compilation errors.
- **Termination checker modulo rule:** a bare `n % k` is rejected as a decreasing argument, though
  `(decreasing_expr) % k` is accepted.

---

## Tests

- **`src/test/java/io/safelang/CCodeGenTests.java`** generates C, compiles it with the host compiler (guarded by a
  `gcc`-availability check), runs the binary, and asserts the output matches the interpreter byte-for-byte — covering
  hello-world, loops, arithmetic, pattern matching, while loops, collections, multi-module imports, and contracts.
- The full SAFE native suite (`tests/`) runs through this backend with `safe test --native tests/`; `TestRunner.runNative`
  generates the C, extracts the headers, builds via `CBuildDriver`, runs the binary, and parses its `[PASS]`/`[FAIL]`
  output.
