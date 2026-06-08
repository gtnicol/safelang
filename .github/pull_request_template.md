## Summary

What does this change and why?

## Related issue

Closes #

## Checklist

- [ ] `mvn -Pquality verify` passes locally (tests + Spotless + Checkstyle + SpotBugs).
- [ ] `mvn -Pquality spotless:apply` has been run (formatting).
- [ ] Semantics changes behave identically across backends (interpreter / bytecode VM / JVM), with
      a `BackendParityTests` case added or updated, and C/WASM emitters mirrored where relevant.
- [ ] The termination guarantee is preserved (no path that can loop without trapping).
- [ ] Changes are scoped — every changed line traces to this PR's purpose.

## Notes for reviewers

Anything worth calling out (tradeoffs, follow-ups, areas to scrutinize).
