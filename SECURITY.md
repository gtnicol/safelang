# Security Policy

## Supported versions

| Version | Supported          |
| ------- | ------------------ |
| 1.0.x   | :white_check_mark: |
| < 1.0   | :x:                |

## Reporting a vulnerability

Please **do not** open a public issue for security problems.

Report vulnerabilities privately through GitHub Security Advisories:
[**Report a vulnerability**](https://github.com/gtnicol/safelang/security/advisories/new).

Include, where possible:

- a description of the issue and its impact,
- a minimal SAFE program or input that reproduces it,
- the affected backend(s) (interpreter, bytecode VM, JVM, native C, WASM) and version.

You can expect an acknowledgement within a few days. Once a fix is available we will coordinate a
disclosure timeline with you and credit you in the advisory unless you prefer otherwise.

## Scope notes

SAFE is designed for guaranteed termination and deterministic execution. Reports that are
especially in scope include: a program accepted by the compiler that fails to terminate and does
not trap, a `decreases`/bound check that can be bypassed, or a divergence where one backend
produces a different result than the others for the same accepted program.
