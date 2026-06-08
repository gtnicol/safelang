# Contributing to SAFE

Thanks for your interest in SAFE! This guide covers building, testing, and the conventions the
project follows.

## Prerequisites

- **Java 21+** and **Maven**.
- Optional, for the full backend matrix: **`gcc`** (native C backend) and a WASI runtime such as
  **`wasmtime`** (WebAssembly backend). Tests that need these skip gracefully when the tool is
  absent.
- The committed `src/main/resources/safe_wasm_builtins.wasm` means a normal build does **not**
  require `clang`/WASI — those are only needed when modifying `safe_wasm_builtins.c` (see the
  `build.wasm` profile in `pom.xml`).

## Build

```bash
mvn clean package -DskipTests
```

This produces `target/safe-lang-<version>.jar` (thin library) and
`target/safe-lang-<version>-cli.jar` (runnable). Use the `-cli` jar to run programs:

```bash
java -jar target/safe-lang-<version>-cli.jar run examples/hello.safe
```

## Test

```bash
mvn test                          # full Java test suite
mvn test -Dtest=BuiltinTests      # a single class
```

The SAFE-native suite under `tests/` runs through every backend — keep them in parity:

```bash
java -jar target/safe-lang-<version>-cli.jar test tests/              # interpreter
java -jar target/safe-lang-<version>-cli.jar test --bytecode tests/   # bytecode VM
java -jar target/safe-lang-<version>-cli.jar test --jvm tests/        # JVM bytecode backend
java -jar target/safe-lang-<version>-cli.jar test --native tests/     # native C backend (needs gcc)
java -jar target/safe-lang-<version>-cli.jar test --wasm tests/       # WASM backend (needs wasmtime)
```

## Quality gate

Before opening a PR, run the same gate CI runs:

```bash
mvn -Pquality verify
```

It runs the full test suite plus:

- **Spotless** (Google Java Format) — `mvn -Pquality spotless:apply` auto-fixes formatting.
- **Checkstyle** — bug-focused rules (unused imports, fall-through, missing switch default, …).
- **SpotBugs** — `High` threshold at max effort.

## Conventions

- **Parity is mandatory.** A semantics change must behave identically across the interpreter,
  bytecode VM, and JVM backends — add a case to `BackendParityTests` when you touch shared
  semantics, and mirror the behavior in the C/WASM emitters.
- **Termination is the core invariant.** Every recursive call site must be statically proven
  terminating or carry a `decreases` clause; never weaken this.
- Match the surrounding style. The project favors short, single-word identifiers and immutable
  locals (`final var`).
- Keep changes surgical — every changed line should trace to the issue you're solving.

## Adding a new AST node

1. Create the node in `ast/` implementing `accept(ASTVisitor<T>)`.
2. Add a `visit*` method to `ASTVisitor<T>` and implement it in every visitor
   (`Interpreter`, `CCodeGenerator`, `BytecodeCompiler`, `ASTPrinter`, `SemanticAnalyzer`).
3. Handle it in the `switch`-dispatch backends: `JvmCodeGenerator` and `WasmCompiler`.

## Pull requests

- Branch from `main`; keep PRs focused.
- Ensure `mvn -Pquality verify` is green and `spotless:apply` has been run.
- Describe what changed and why; link any related issue.

By contributing you agree your contributions are licensed under the project's
[BSD 3-Clause License](LICENSE).
