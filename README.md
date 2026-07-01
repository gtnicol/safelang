# SAFE

[![CI](https://github.com/gtnicol/safelang/actions/workflows/ci.yml/badge.svg)](https://github.com/gtnicol/safelang/actions/workflows/ci.yml)
[![License: BSD-3-Clause](https://img.shields.io/badge/License-BSD%203--Clause-blue.svg)](LICENSE)
[![Java 21+](https://img.shields.io/badge/Java-21%2B-orange.svg)](https://adoptium.net/)

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
   numeric decrease on `int`/`uint` parameters. For numeric recursion a parameter must **strictly decrease on
   every** recursive call (a monotone measure) **and** that parameter must be tested by the base-case condition,
   so a measure cannot shrink while an unrelated, growing parameter gates the recursion. Programs that pass have
   no runtime overhead.
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

The build produces two artifacts in `target/`:

- `safe-lang-1.0.0.jar` — the thin library jar (declares ANTLR as a dependency), for embedding the
  JSR-223 `ScriptEngine` in another project.
- `safe-lang-1.0.0-cli.jar` — a self-contained executable (`java -jar … <subcommand>`); this is the
  one the examples below use.

## Running programs

```bash
# Interpret
java -jar target/safe-lang-1.0.0-cli.jar run examples/hello.safe

# Bytecode pipeline
java -jar target/safe-lang-1.0.0-cli.jar bytecode examples/fibonacci.safe
java -jar target/safe-lang-1.0.0-cli.jar vm examples/fibonacci.safeb

# Native build via C
java -jar target/safe-lang-1.0.0-cli.jar build examples/fibonacci.safe
./examples/fibonacci

# Compile to WebAssembly
java -jar target/safe-lang-1.0.0-cli.jar wasm examples/fibonacci.safe
wasmtime examples/fibonacci.wasm          # or any WASI-capable runtime

# Assembly round-trip
java -jar target/safe-lang-1.0.0-cli.jar disassemble examples/fibonacci.safeb
java -jar target/safe-lang-1.0.0-cli.jar assemble file.safea

# Debugging aids
java -jar target/safe-lang-1.0.0-cli.jar tokens examples/hello.safe
java -jar target/safe-lang-1.0.0-cli.jar ast examples/hello.safe

# SAFE-native test runner
java -jar target/safe-lang-1.0.0-cli.jar test tests/
java -jar target/safe-lang-1.0.0-cli.jar test --native tests/
```

Useful flags: `--strict` / `--deterministic` (purity checking — rejects non-deterministic builtins
such as `time`, `rand`, file I/O, and the `http` / `system:exec` network/process builtins),
`--allow` / `--deny` (host capabilities — see below), and `--bytecode` / `--jvm` / `--native` /
`--wasm` (select the backend for the test runner).

### Capabilities (host-access sandboxing)

Dangerous builtins are gated by a **host capability policy** — `FILESYSTEM`, `NETWORK`, `PROCESS`,
`ENVIRONMENT`, `STDIN`. This is the *host-access* axis, orthogonal to `--strict`'s *determinism*
axis; a fully sandboxed embedding denies the relevant capabilities **and** runs strict.

- **Embedding is deny-by-default.** The JSR-223 `ScriptEngine` *and the entire programmatic
  `SafeRuntime` API* run untrusted code with **no** host capabilities — an embedder who forgets to
  pass a policy gets a sandbox, not the host. Grant explicitly:
  `engine.put("safe.capabilities", "filesystem,network")` (or `SafeRuntime.run(..., HostPolicy)`).
- **The CLI grants all** (a trusted local dev tool, via an explicit policy). Restrict with
  `--allow fs,net` (exactly those) or `--deny net,proc` (everything except those):
  `java -jar … run --deny net,proc app.safe`.
- **Enforcement is at runtime** for the interpreter, bytecode VM, and JVM — so it protects the
  `.safeb` bytecode path that source-level `--strict` cannot — and at **compile time** for the
  self-executing AOT artifacts (native C `build`, JVM `jvm` jar, and **WASM**): a `jvm --deny net`
  jar / `wasm --deny fs` module physically cannot call the denied builtin. A denied call fails with
  a clear "capability denied" / "host not allowed" error.
- **SSRF guard:** the HTTP client resolves the URL host and blocks loopback / link-local / cloud
  metadata / private-range IPs by default; an explicit `--net-allow` entry re-permits a trusted
  internal target.
- **Deployed-artifact policy:** the JVM jar reads `SAFE_FS_ROOT`/`SAFE_NET_ALLOW`/`SAFE_EXEC_ALLOW`/
  `SAFE_SERVE_BIND` from the environment, so an operator can confine a running jar without
  recompiling.

#### Policy refinements (within a granted capability)

Capabilities are coarse on/off; an embedder can further constrain a *granted* capability:

| Refinement | CLI flag | Engine binding | Effect |
|------------|----------|----------------|--------|
| Filesystem root jail | `--fs-root <dir>` | `safe.fs.root` | All `file`/`binary` paths are confined under the root (`../`, absolute, and symlink escapes rejected). |
| HTTP egress allowlist | `--net-allow <hosts>` | `safe.net.allow` | The client may only reach the listed hosts / `*.suffix` / CIDRs; others return `Err`. |
| Exec command allowlist | `--exec-allow <cmds>` | `safe.exec.allow` | `system:exec` may only run a listed `argv[0]`. |
| Env scrub | — | `safe.env.scrub` | Clear the child process environment before `exec`. |
| Server bind address | — | `safe.serve.bind` | `http:serve` bind address (default `127.0.0.1`, loopback-only). |

With no refinement set, a granted capability is unrestricted (so the trusted CLI is unchanged). The
`http:serve` default is **loopback-only** — a guest server is not exposed to the network unless the
embedder opens it up. The egress/jail/exec refinements are enforced on the interpreter/VM/JVM run
paths; native-AOT binaries are a trusted-builder artifact and enforce the capability gate only.

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

The `http` module provides an HTTP client (`get`, `post`, `request`) and a server (`serve`, `serve_until`,
`serve_tls`, `serve_until_tls` — the `*_until` forms take a `fn(int) -> boolean` stop predicate). The `system` module
runs child processes with `system:exec(["cmd", "arg"])` (argument list, no shell). These network/process builtins are
non-deterministic and **rejected under `--strict`**; they run on the interpreter, bytecode VM, JVM, and native C
backends (native HTTP needs libcurl; native TLS server needs OpenSSL), but not WASM.

These builtins enforce fixed, fail-fast resource limits (the same on every backend): `system:exec` is bounded to 30 s
and 16 MiB of captured output per stream (a flooding child is killed immediately); the HTTP client has a 30 s deadline
and 32 MiB response cap and returns `Err` on a forbidden request header; the HTTP server caps request bodies (8 MiB →
`413`), headers (64 KiB / 100 → `431`), and per-connection read time (3 s, slowloris), and aborts on a malformed request
or a CR/LF-injected response header. `http:serve` is single-threaded (its handler runs on the calling thread) and is
intended for **embedded/trusted** use, not a public DoS-hardened endpoint.

Other sandbox limits protect the host: the **interpreter, VM, JVM, and C backends all bound recursion at 1000 frames**
(a runaway recurses to a catchable error, never a host `StackOverflowError` — the JSR-223 engine also wraps any
escaping `Error` as a `ScriptException`); whole-file reads and the buffered write handle are capped at **64 MiB**; and
the on-disk databases get a transparent per-handle block cache.

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
