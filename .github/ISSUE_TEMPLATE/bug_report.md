---
name: Bug report
about: Report incorrect behavior in the compiler, runtime, or a backend
title: "[bug] "
labels: bug
---

## Description

A clear description of what's wrong.

## Reproduction

A minimal SAFE program (or the smallest one you can manage):

```safe
program repro;
import io;

io:println("...");
```

Command used:

```bash
java -jar target/safe-lang-<version>-cli.jar run repro.safe
```

## Expected vs actual

- **Expected:** what you thought would happen.
- **Actual:** what actually happened (paste output / error / stack trace).

## Backend(s)

Which backends show the problem? (interpreter / bytecode VM / JVM / native C / WASM). If it differs
between backends, that's important — please say which agree and which don't.

## Environment

- SAFE version / commit:
- Java version (`java -version`):
- OS:
- For native/WASM: `gcc --version` / `wasmtime --version`:
