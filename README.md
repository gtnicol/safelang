# SAFE

**SAFE** (Simple Atomic Finite Expressions) is a small programming language designed to be practical for everyday
tasks while being amenable to formal verification and **guaranteed to terminate**. Every program SAFE accepts either
finishes or traps at runtime — it never silently loops.

SAFE ships with a complete toolchain: lexer, parser, tree-walking interpreter, bytecode compiler and stack-based VM,
C code generator (native binaries via `gcc`), a WebAssembly backend, assembler, and decompiler.

```safe
program hello;
import io;

io:println("Hello, World!");
```

## Why SAFE?

SAFE is not Turing-complete by design. Recursion and iteration are constrained so that termination is either proven
at compile time or enforced at runtime. This makes SAFE a good fit for:

- configuration logic and policy engines
- data transformations and ETL
- verified computations and sandboxed user scripts
- anywhere you need confidence that code always produces a result

### Termination model

SAFE uses a **two-tier** system:

1. **Static** — the `TerminationChecker` proves termination for structural recursion on recursive enums and
   numeric decrease on `int`/`uint` parameters. Programs that pass have no runtime overhead.
2. **Runtime** — for functions the static checker cannot prove terminating, the user declares an explicit
   `decreases(expr)` clause. Every call evaluates the measure; the runtime traps if it does not strictly decrease.
   All four backends (interpreter, VM, C, WebAssembly) enforce `decreases` identically.

Bounded `while` loops (`while (cond) bound (n) { ... }`) require a compile-time iteration cap, and `for ... in` loops
iterate over finite collections or ranges. There is no unbounded `while(true)`.

## Build

Requires Java 21+ and Maven.

```bash
mvn clean package -DskipTests
```

The build produces `target/safe-lang-1.0-SNAPSHOT.jar`.

## Running programs

```bash
# Interpret
java -jar target/safe-lang-1.0-SNAPSHOT.jar run examples/hello.safe

# Bytecode pipeline
java -jar target/safe-lang-1.0-SNAPSHOT.jar bytecode examples/fibonacci.safe
java -jar target/safe-lang-1.0-SNAPSHOT.jar vm examples/fibonacci.safeb

# Native build via C
java -jar target/safe-lang-1.0-SNAPSHOT.jar build examples/fibonacci.safe
./examples/fibonacci

# Compile to WebAssembly
java -jar target/safe-lang-1.0-SNAPSHOT.jar wasm examples/fibonacci.safe
wasmtime examples/fibonacci.wasm          # or any WASI-capable runtime

# Assembly round-trip
java -jar target/safe-lang-1.0-SNAPSHOT.jar disassemble examples/fibonacci.safeb
java -jar target/safe-lang-1.0-SNAPSHOT.jar assemble file.safea

# Debugging aids
java -jar target/safe-lang-1.0-SNAPSHOT.jar tokens examples/hello.safe
java -jar target/safe-lang-1.0-SNAPSHOT.jar ast examples/hello.safe

# SAFE-native test runner
java -jar target/safe-lang-1.0-SNAPSHOT.jar test tests/
java -jar target/safe-lang-1.0-SNAPSHOT.jar test --native tests/
```

Useful flags: `--strict` / `--deterministic` (purity checking), and `--native` / `--bytecode` / `--wasm`
(select the backend for the test runner).

### WebAssembly backend

The compiled WASM runtime `src/main/resources/safe_wasm_builtins.wasm` is committed, so normal builds do **not**
require clang or a WASI sysroot. Recompile it only when modifying `src/main/resources/safe_wasm_builtins.c`:

```bash
mvn compile -Dbuild.wasm=true     # activates the build-wasm-builtins profile
```

The profile invokes `scripts/compile_builtins.sh`, which expects clang and a WASI sysroot (e.g.
`/opt/homebrew/share/wasi-sysroot` from Homebrew's `llvm` on macOS). Emitted `.wasm` modules target WASI and run
under any compliant runtime (`wasmtime`, `wasmer`, Node with `--experimental-wasi-unstable-preview1`, etc.).

## Testing

```bash
mvn test                         # full Java test suite
mvn test -Dtest=BuiltinTests     # a single class
```

The Java suite covers the lexer, parser, interpreter, bytecode compiler and VM, semantic analysis, builtins,
language and stdlib extensions, the JSR 223 scripting engine, binary/hash/bytes, on-disk key-value stores, and the
test runner.

The `tests/` directory holds SAFE-native tests written against the `test` stdlib module; they run through all three
execution backends.

## Repository layout

```
src/main/java/io/safelang/ Compiler and runtime (Java 21)
  parser/                  ANTLR grammar wrapper, AST builder
  ast/                     AST nodes (sealed interface, records)
  interpreter/             Tree-walking interpreter
  compiler/                C code generator and WebAssembly backend
  bytecode/                Bytecode compiler, VM, writer/reader, assembler, decompiler
  runtime/                 SAFEValue, Environment, BuiltinRegistry, file handles
  analyzer/                Semantic analysis, type resolver, purity, termination
  scripting/               JSR 223 (javax.script) integration

src/main/resources/        ANTLR grammar, WASM runtime sources, embedded C runtime
src/test/java/             JUnit test classes

stdlib/                    Standard library modules written in SAFE
examples/                  Example .safe programs (compiled artefacts are git-ignored; build on demand)
tests/                     SAFE-native test files using the test module
docs/                      Language introduction, opcode reference, assembler and backend docs (JVM, C, WASM)
scripts/                   Build helpers (e.g. WASM builtin compilation)
benchmarks/                Micro-benchmarks
```

## Language tour

Core types: `int`, `float`, `uint`, `string`, `boolean`, `void`, `bytes`, `list<T>`, `map<K,V>`, `set<T>`, tuples
`(int, string)`, union types `int|float`, generic type variables `?T`.

Highlights:

- Structs (`type`) and enums with associated data
- Pattern matching with `case ... of` and guard conditions
- Higher-order functions and lambdas (`fn(x) -> x + 1`)
- Bitwise operators, ranges with `step`, tuple destructuring
- String interpolation `` `Hello, ${name}!` ``
- `requires` / `ensures` contracts, `assert` statements, explicit `decreases` clauses
- Modules with `program` / `module` headers and selective imports
- Tail call optimisation on the bytecode backend (self-recursion)

### A few flavours

**Iterative Fibonacci** (`examples/fibonacci.safe`):

```safe
program fibonacci;
import io;
import std;

int fib(int n) {
    int a = 0;
    int b = 1;
    int result = 0;
    for i in std:range(n) {
        result = a;
        int temp = a + b;
        a = b;
        b = temp;
    }
    return result;
}

for i in std:range(15) {
    io:println(fib(i));
}
```

**Contracts and assertions** (`examples/contracts.safe`):

```safe
int abs(int x)
    requires x > -100
{
    int result = if (x >= 0) then x else 0 - x;
    return result;
}

int clamp(int x, int lo, int hi)
    requires lo <= hi
{
    return if (x < lo) then lo
           else if (x > hi) then hi
           else x;
}

assert 1 + 1 == 2;
```

**Enums with associated data** (from `examples/tour.safe`):

```safe
enum Shape {
    Circle(float),
    Rect(float, float),
    Dot
}

string describe(Shape s) {
    return case s of {
        Circle(r):  `circle r=${r}`;
        Rect(w, h): `rectangle ${w}x${h}`;
        Dot:        "a dot";
    };
}
```

**Runtime termination via `decreases`** (`examples/decreases.safe`):

```safe
int gcd(int a, int b)
decreases(a + b) {
    return if (b == 0) then a else gcd(b, a % b);
}

int power(int base, int exp)
decreases(exp) {
    return if (exp <= 0) then 1 else base * power(base, exp - 1);
}
```

The measure must be a non-negative `int`/`uint` that strictly decreases on every recursive call — every backend
traps if it doesn't.

More samples live in [`examples/`](examples/): `tour.safe` (language overview), `file_io.safe`, `json_demo.safe`,
`xml_demo.safe`, `csv_demo.safe`, `interpolation.safe`, `maps.safe`, `visibility.safe`, and more.

## Documentation

- [`docs/introduction.md`](docs/introduction.md) — full language walkthrough, from hello-world through modules,
  contracts, and the termination model
- [`docs/assembler.md`](docs/assembler.md) — `.safea` assembly syntax, `.safeb` binary format, assembler and
  decompiler usage
- [`docs/opcodes.md`](docs/opcodes.md) — complete reference for every SAFE VM instruction
- [`docs/jvm-backend.md`](docs/jvm-backend.md) — the JVM bytecode backend: design, the `jvm` command, and the
  self-contained executable jar
- [`docs/c-backend.md`](docs/c-backend.md) — the C backend: design, the `compile`/`build` commands, and the
  self-contained C runtime
- [`docs/wasm-backend.md`](docs/wasm-backend.md) — the WebAssembly backend: design, the `wasm` command, multi-module
  linking, and the committed WASI runtime

## Standard library

Modules in `stdlib/` cover I/O, strings, collections, math, functional helpers, sorting, trees, stacks and queues,
files and paths, binary/bytes, hashing, JSON / XML / CSV, date-time, UUIDs, base64, environment variables, and
on-disk key-value stores (`btree`, `lsm`, `dbm`). The `test` module powers SAFE-native test files.

## File extensions

| Extension | Contents                              |
|-----------|---------------------------------------|
| `.safe`   | SAFE source                           |
| `.safeb`  | Compiled bytecode (binary)            |
| `.safea`  | Bytecode assembly (human-readable)    |
| `.wasm`   | WebAssembly module                    |

## Entry point

`io.safelang.SafeMain` dispatches CLI subcommands to the appropriate pipeline stage.

## License

BSD 3-Clause — see [LICENSE](LICENSE) for the full text.
