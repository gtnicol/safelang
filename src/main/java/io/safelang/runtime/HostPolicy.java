package io.safelang.runtime;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

/**
 * The host-access policy an embedder imposes on a SAFE program. It carries the coarse {@link
 * Capabilities} (on/off per category) plus finer refinements that apply <em>within</em> a granted
 * capability: a filesystem root jail, an HTTP egress allowlist, an exec command/env policy, and the
 * server bind address.
 *
 * <p>Immutable. The trusted CLI uses {@link #trusted()} (everything granted, no restrictions); an
 * embedder running untrusted code uses {@link #sandbox()} (nothing granted) and opts in.
 */
public final class HostPolicy {

  private final Capabilities capabilities;
  private final Path fsRoot; // null ⇒ no jail
  private final Set<String> netAllow; // null ⇒ any host
  private final Set<String> execAllow; // null ⇒ any command
  private final boolean scrubEnv;
  private final String serveBind;

  private HostPolicy(
      final Capabilities capabilities,
      final Path fsRoot,
      final Set<String> netAllow,
      final Set<String> execAllow,
      final boolean scrubEnv,
      final String serveBind) {
    this.capabilities = capabilities != null ? capabilities : Capabilities.none();
    this.fsRoot = fsRoot;
    this.netAllow = netAllow;
    this.execAllow = execAllow;
    this.scrubEnv = scrubEnv;
    this.serveBind = serveBind != null ? serveBind : "127.0.0.1";
  }

  /**
   * Everything granted, no path/host/command restrictions — the trusted local CLI / AOT default.
   */
  public static HostPolicy trusted() {
    return new HostPolicy(Capabilities.all(), null, null, null, false, "127.0.0.1");
  }

  /** Nothing granted — the deny-by-default for embedded/untrusted code. */
  public static HostPolicy sandbox() {
    return new HostPolicy(Capabilities.none(), null, null, null, false, "127.0.0.1");
  }

  /** A policy granting exactly {@code capabilities}, with no finer restrictions. */
  public static HostPolicy of(final Capabilities capabilities) {
    return new HostPolicy(capabilities, null, null, null, false, "127.0.0.1");
  }

  public Builder toBuilder() {
    return new Builder(this);
  }

  public Capabilities capabilities() {
    return capabilities;
  }

  public boolean scrubEnv() {
    return scrubEnv;
  }

  public String serveBind() {
    return serveBind;
  }

  /** True when {@code argv0} is permitted by the exec command allowlist (no list ⇒ any command). */
  public boolean execAllowed(final String argv0) {
    return execAllow == null || execAllow.contains(argv0);
  }

  /** True when {@code host} is permitted by the egress allowlist (no list ⇒ any host). */
  public boolean hostAllowed(final String host) {
    if (netAllow == null) {
      return true;
    }
    for (final var rule : netAllow) {
      if (rule.equalsIgnoreCase(host) || NetMatch.matches(rule, host)) {
        return true;
      }
    }
    return false;
  }

  /**
   * Egress policy for an HTTP client request, resolving the URL host to its IP(s) so a name cannot
   * smuggle a request to an internal address (SSRF). When an allowlist is configured, only listed
   * hosts are reachable (a listed host may be internal — the embedder trusts it deliberately).
   * Otherwise (open egress) any host is reachable <em>except</em> one resolving to a loopback,
   * link-local/metadata, private, or wildcard address — blocked by default. (DNS may rebind between
   * this check and the JDK client's own resolution at connect time; this is best-effort.)
   */
  public boolean egressAllowed(final String url) {
    final String host;
    try {
      host = java.net.URI.create(url).getHost();
    } catch (final IllegalArgumentException malformed) {
      return false;
    }
    if (host == null) {
      return false;
    }
    if (netAllow != null) {
      return hostAllowed(host);
    }
    try {
      for (final var address : java.net.InetAddress.getAllByName(host)) {
        if (isInternal(address)) {
          return false;
        }
      }
    } catch (final java.net.UnknownHostException unresolved) {
      return false;
    }
    return true;
  }

  private static boolean isInternal(final java.net.InetAddress address) {
    if (address.isLoopbackAddress()
        || address.isLinkLocalAddress() // 169.254/16 (incl. 169.254.169.254 metadata), fe80::/10
        || address.isSiteLocalAddress() // 10/8, 172.16/12, 192.168/16, fec0::/10
        || address.isAnyLocalAddress() // 0.0.0.0, ::
        || address.isMulticastAddress()) {
      return true;
    }
    // Ranges the JDK's classifiers miss: IPv6 unique-local fc00::/7 (not site-local per the JDK)
    // and IPv4 carrier-grade NAT 100.64.0.0/10.
    final var bytes = address.getAddress();
    if (bytes.length == 16 && (bytes[0] & 0xfe) == 0xfc) {
      return true;
    }
    return bytes.length == 4 && (bytes[0] & 0xff) == 100 && (bytes[1] & 0xc0) == 0x40;
  }

  /**
   * Resolve a caller-supplied path against the filesystem jail. With no root configured the path is
   * returned as-is; otherwise the path is confined under the root (escapes throw) and returned in
   * canonical, symlink-collapsed form.
   *
   * <p>The deepest existing ancestor is realpath'd (resolving any symlinks in the existing prefix)
   * and the not-yet-existing tail re-attached, so the builtin operates on the canonical path rather
   * than the lexical one — a symlinked existing component is resolved here and a later swap of it
   * is not silently followed, narrowing the TOCTOU window. The JDK cannot open with {@code
   * O_NOFOLLOW} atomically, so a component created/swapped strictly between this call and the file
   * operation is a residual window; the jail is hardened-best-effort against a co-located attacker.
   */
  public Path resolve(final String path) throws IOException {
    if (fsRoot == null) {
      return Path.of(path);
    }
    final var root = fsRoot.toRealPath();
    final var candidate = root.resolve(path).normalize();
    if (!candidate.startsWith(root)) {
      throw new IOException("path escapes the sandbox root: " + path);
    }
    var ancestor = candidate;
    while (ancestor != null && !java.nio.file.Files.exists(ancestor)) {
      ancestor = ancestor.getParent();
    }
    if (ancestor == null) {
      return candidate;
    }
    final var resolved = ancestor.toRealPath().resolve(ancestor.relativize(candidate)).normalize();
    if (!resolved.startsWith(root)) {
      throw new IOException("path escapes the sandbox root via symlink: " + path);
    }
    return resolved;
  }

  /** Mutable builder for assembling a policy from CLI flags / engine bindings. */
  public static final class Builder {
    private Capabilities capabilities;
    private Path fsRoot;
    private Set<String> netAllow;
    private Set<String> execAllow;
    private boolean scrubEnv;
    private String serveBind;

    private Builder(final HostPolicy base) {
      this.capabilities = base.capabilities;
      this.fsRoot = base.fsRoot;
      this.netAllow = base.netAllow;
      this.execAllow = base.execAllow;
      this.scrubEnv = base.scrubEnv;
      this.serveBind = base.serveBind;
    }

    public Builder capabilities(final Capabilities value) {
      this.capabilities = value;
      return this;
    }

    public Builder fsRoot(final Path value) {
      this.fsRoot = value;
      return this;
    }

    public Builder netAllow(final List<String> hosts) {
      this.netAllow = hosts == null ? null : Set.copyOf(hosts);
      return this;
    }

    public Builder execAllow(final List<String> commands) {
      this.execAllow = commands == null ? null : Set.copyOf(commands);
      return this;
    }

    public Builder scrubEnv(final boolean value) {
      this.scrubEnv = value;
      return this;
    }

    public Builder serveBind(final String value) {
      this.serveBind = value;
      return this;
    }

    public HostPolicy build() {
      return new HostPolicy(capabilities, fsRoot, netAllow, execAllow, scrubEnv, serveBind);
    }
  }
}
