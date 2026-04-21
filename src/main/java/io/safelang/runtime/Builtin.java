package io.safelang.runtime;

import io.safelang.ast.FunctionDeclarationNode;

/**
 * Metadata for one SAFE builtin function.
 *
 * <p>The {@code purity} field classifies the builtin's effect into one of three tiers — see {@link
 * Purity}. The strict-mode purity check (in {@code PurityChecker}) admits {@link Purity#PURE} and
 * {@link Purity#OBSERVABLE} builtins; only {@link Purity#NONDETERMINISTIC} ones are rejected.
 */
public record Builtin(
    int id,
    String name,
    String module,
    FunctionDeclarationNode signature,
    int minimum,
    Purity purity) {

  /**
   * The three tiers of builtin "purity" used by SAFE's strict-mode analyzer.
   *
   * <ul>
   *   <li>{@link #PURE} — fully pure: deterministic, no side effects.
   *   <li>{@link #OBSERVABLE} — deterministic but produces a visible side effect such as writing to
   *       stdout. Allowed under strict mode because the effect doesn't introduce non-determinism.
   *   <li>{@link #NONDETERMINISTIC} — reads or writes external state, or otherwise produces results
   *       that are not a function of the arguments alone (clock, RNG, filesystem, env, stdin,
   *       exit). Rejected under strict mode.
   * </ul>
   */
  public enum Purity {
    PURE,
    OBSERVABLE,
    NONDETERMINISTIC
  }
}
