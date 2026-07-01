package io.safelang.runtime;

import java.util.EnumSet;
import java.util.Set;

/**
 * An immutable set of host-access {@link Capability} grants. The execution backends consult a
 * {@code Capabilities} to decide whether a builtin's effect is permitted; an embedder running
 * untrusted code passes {@link #none()} (deny-all) and grants explicitly.
 */
public final class Capabilities {

  private static final Capabilities NONE = new Capabilities(EnumSet.noneOf(Capability.class));
  private static final Capabilities ALL = new Capabilities(EnumSet.allOf(Capability.class));

  private final Set<Capability> granted;

  private Capabilities(final Set<Capability> granted) {
    this.granted = granted;
  }

  /** No host capabilities — the secure default for untrusted code. */
  public static Capabilities none() {
    return NONE;
  }

  /** Every host capability — the default for the trusted local CLI and AOT compilation. */
  public static Capabilities all() {
    return ALL;
  }

  public static Capabilities of(final Capability... capabilities) {
    final var set = EnumSet.noneOf(Capability.class);
    for (final var capability : capabilities) {
      set.add(capability);
    }
    return new Capabilities(set);
  }

  /**
   * Parse a comma-separated grant list (e.g. {@code "filesystem,network"} or {@code "fs,net"}). An
   * empty/blank string grants nothing; {@code "all"} grants everything.
   */
  public static Capabilities parse(final String spec) {
    if (spec == null || spec.isBlank()) {
      return NONE;
    }
    if (spec.trim().equalsIgnoreCase("all")) {
      return ALL;
    }
    final var set = EnumSet.noneOf(Capability.class);
    for (final var token : spec.split(",")) {
      if (!token.isBlank()) {
        set.add(Capability.parse(token));
      }
    }
    return new Capabilities(set);
  }

  /**
   * This set with {@code capability} removed (used by the CLI {@code --deny} flag over {@link
   * #all()}).
   */
  public Capabilities without(final Capability capability) {
    final var set = EnumSet.copyOf(granted.isEmpty() ? EnumSet.noneOf(Capability.class) : granted);
    set.remove(capability);
    return new Capabilities(set);
  }

  public boolean granted(final Capability capability) {
    return granted.contains(capability);
  }
}
