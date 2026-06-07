# SAFE WebAssembly Backend

This document describes the WebAssembly backend (`compiler/wasm/`). It compiles a SAFE program to one or more `.wasm`
modules that run on a WASI runtime such as [wasmtime](https://wasmtime.dev/), linked at load time against a committed C
runtime module.

> **CLI invocation.** Examples use a short `safe <subcommand>` form for readability. The actual command is
> `java -jar target/safe-lang-1.0-SNAPSHOT.jar <subcommand>`; alias `safe` to that in your shell if you want the
> short form to work literally.

## Commands

| Command                   | Input       | Output             | Description                                       |
|---------------------------|-------------|--------------------|---------------------------------------------------|
| `safe wasm file.safe`     | Source code | `file.wasm` + deps | Compile to WebAssembly modules                    |
| `safe test --wasm tests/` | Test files  | stdout             | Run the SAFE test suite through the WASM backend  |

```bash
# Compile to WebAssembly
safe wasm examples/fibonacci.safe
# Emits: fibonacci.wasm, safe_wasm_builtins.wasm, and one <module>.wasm per imported module.
# Prints the exact wasmtime command to run it, e.g.:
#   wasmtime run --preload builtins=.../safe_wasm_builtins.wasm \
#                --preload io=.../io.wasm --dir /tmp --dir . fibonacci.wasm

# Run the test suite through the WASM backend
safe test --wasm tests/
```

`safe wasm` prints the precise `wasmtime` command to run the result — built through the same code path the test runner
uses, so the displayed command matches the actual invocation.

---

## Pipeline

```
Source (.safe) → Lexer → Parser → AST
                                    │
                                    ▼
                             WasmPipeline           (per-module compile + link plan)
                                    │
                                    ▼
                             WasmCompiler            (AST → one .wasm module)
                                    │
                                    ▼
                  WasmModule / WasmBinaryWriter      (serialize .wasm bytes)
                                    │
                                    ▼
        file.wasm + <module>.wasm + safe_wasm_builtins.wasm + __closures
                                    │
                                    ▼
              wasmtime run --preload ... (links modules at load time)
```

CLI wiring: `SafeMain` (`wasm` command) → `SafeRuntime.wasm` → `WebAssemblyCompilerService` → `WasmPipeline` →
`WasmCompiler`.

---

## Design

### Multi-module, linked at load time

Unlike the C and JVM backends (single output), the WASM backend emits **several** modules and links them with wasmtime
`--preload`:

- `file.wasm` — the main program; exports `_start`.
- one `<module>.wasm` per imported module (e.g. `io.wasm`, `math.wasm`); exports its public functions.
- `safe_wasm_builtins.wasm` — the C runtime (see below); every module imports its memory and functions.
- a shared `__closures` module — exports a funcref `__table` and `__callN` trampolines (arities 0–8) so closures can be
  called across module boundaries.

All modules share one linear memory (imported from the builtins module), which is how values flow between them.

### Value representation: tagged `i64`

Every SAFE value is a tagged `i64` on the WASM stack (`WasmRuntime`):

- **Bits 0–3** — type tag: `0=int, 1=float, 2=bool, 3=string, 4=void, 5=list, 6=map, 7=set, 8=tuple, 9=enum,
  10=object, 11=closure, 12=bytes, 13=uint`.
- **Bits 4–63** — payload: the value itself (ints, bools) or a 32-bit linear-memory pointer (strings, collections,
  enums, …) left-shifted by 4.

Linear memory has a fixed low layout — a null guard, WASI iovec/scratch areas, a print buffer, then the data section
(string constants) followed by a bump-allocated arena heap that grows upward. Heap objects carry the same `SAFEHeader`
(refcount, kind, size class, meta) used by the C backend, so the reference-counting discipline is shared.

### Generated WASM calls into a C runtime

Generated code keeps SAFE values as tagged `i64`, but the heavy lifting (allocation, string/list/map operations, math,
hashing, printing, I/O) lives in **`safe_wasm_builtins.wasm`**, a C runtime compiled from
`src/main/resources/safe_wasm_builtins.c`. `WasmRuntimeBuilder` imports those host functions and emits thin tag/untag
wrapper stubs: a tagged `i64` is untagged to a raw value, passed to the C builtin, and the raw result is re-tagged. The
runtime uses a bump allocator and the shared `SAFEHeader` refcount scheme.

### WASI for the outside world

I/O and platform services go through WASI (`wasi_snapshot_preview1`): `fd_write`/`fd_read` for stdout/stderr/stdin,
`proc_exit` for termination, plus `clock_time_get`, `random_get`, `args_get`, and `environ_get`. The displayed run
command mounts `/tmp` and the current directory (`--dir`) so file I/O works.

---

## The committed runtime: `safe_wasm_builtins.wasm`

The compiled runtime module is **committed to the repository** at `src/main/resources/safe_wasm_builtins.wasm`, so a
normal `mvn compile` / `mvn test` / `mvn package` does **not** require clang or a WASI sysroot. It is bundled in the jar
and extracted next to the output modules (`SafeMain.extractWasmBuiltins`) at compile time.

Recompile it only when changing `safe_wasm_builtins.c`:

```bash
mvn compile -Dbuild.wasm=true     # activates the build-wasm-builtins profile
```

That profile runs `scripts/compile_builtins.sh`, which locates a WASI sysroot and a `wasm32`-capable clang and invokes
clang with `--target=wasm32-wasip1`, no startfiles/default libs, and explicit memory/stack/global-base linker settings,
producing the `.wasm` runtime.

---

## Components

| File                        | Responsibility                                                                       |
|-----------------------------|--------------------------------------------------------------------------------------|
| `WasmPipeline`              | Per-module compilation + multi-module link plan; builds the wasmtime command; runs it. |
| `WasmCompiler`              | The compiler: walks the AST and emits one WASM module (two-pass: register, then emit). |
| `WasmModule` / `WasmBinaryWriter` | Assemble and serialize a complete `.wasm` module (types, imports, funcs, memory, …). |
| `WasmRuntime`               | Constants for the tagged-value scheme and the linear-memory layout.                   |
| `WasmRuntimeBuilder` / `WasmRuntimeContext` | Import the host runtime and emit tag/untag wrappers; per-compile state. |
| `WasmHostBuiltins`          | Declarative table of the C builtins imported from `safe_wasm_builtins.wasm`.          |
| `WasmBuiltinEmitter` / `WasmBuiltinRegistrar` | Emit and allocate stubs for the builtins a program references.      |
| `WasmBinaryEmitter`         | Lower binary expressions (short-circuit, string concat/eq, membership, numeric/bitwise). |
| `WasmObjectCompiler` / `WasmMapSupport` / `WasmCaseCompiler` | Structs/field access, map literals, pattern matching. |
| `WasmLambdaCompiler` / `WasmLambdaPlanner` | Compile lambda bodies and plan closure capture.                       |
| `WasmRefcount`              | Retain/release helpers and heap-field bitmaps for the shared refcount scheme.         |
| `safe_wasm_builtins.c` / `.wasm` | C runtime source and the committed compiled module.                              |

---

## Modules and builtins

Each imported module compiles to its own `.wasm`, exporting its public functions; the main program imports them and
wasmtime links everything via `--preload`. `BuiltinRegistry` remains the single source of truth for builtins; the WASM
backend imports their pre-compiled C implementations from `safe_wasm_builtins.wasm` and generates per-program stubs that
bridge the tagged/untagged calling conventions on demand.

Closures that cross module boundaries are dispatched through the shared `__closures` module's funcref table and `__callN`
trampolines, which support arities 0–8.

---

## Contracts and `decreases`

Contracts are enforced in `WasmCompiler.emitFunctionBody` and trap on violation (a trap is terminal):

- `requires` is evaluated before the body; if false, it calls the runtime trap with a descriptive message.
- `ensures` binds the return value to a `result` local and is evaluated on every return path.
- `decreases(expr)` uses a per-function mutable global holding the in-flight measure. On entry it saves the parent's
  measure, evaluates the new measure, rejects negatives, and — if a parent call is active — requires a strict decrease;
  on return it restores the parent's measure. A sentinel of `-1` marks "no parent call" (distinct from a valid measure of
  `0`). This matches the interpreter, VM, and JVM backends.

---

## Limitations

- **Closure arity** for cross-module trampolines is capped at 8 (`MAX_ARITY`).
- A struct's per-field heap bitmap covers the first 8 fields; reference-counting of heap fields beyond index 7 is not
  tracked per-field.
- File I/O is sandboxed by wasmtime to the mounted directories (`--dir`).

---

## Tests

- **`src/test/java/io/safelang/WasmPipelineTests.java`** compiles and runs programs through wasmtime, asserting output
  for hello-world, arithmetic, strings, loops, module imports, enums and pattern matching, control flow, and lambda
  captures.
- **`src/test/java/io/safelang/WasmContractTests.java`** verifies `requires`/`ensures`/`decreases` trap (and accept)
  correctly, including the contract type in the trap message.
- Additional parity/diagnostic tests (`WasmParityTests`, `WasmCodeGeneratorTests`, `WasmStdlibDiagnosticTest`) compare
  output against the interpreter and exercise stdlib compilation.
- The full SAFE native suite (`tests/`) runs through this backend with `safe test --wasm tests/`.
