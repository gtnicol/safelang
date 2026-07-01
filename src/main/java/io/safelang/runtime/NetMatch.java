package io.safelang.runtime;

import java.net.InetAddress;

/**
 * Host-matching for the egress allowlist. A rule is one of: an exact hostname (handled by the
 * caller), a domain suffix ({@code .example.com} or {@code *.example.com}), or an IPv4 CIDR ({@code
 * 10.0.0.0/8}) checked against the host's resolved address(es).
 */
final class NetMatch {

  private NetMatch() {}

  static boolean matches(final String rule, final String host) {
    if (rule.startsWith("*.")) {
      return host.equalsIgnoreCase(rule.substring(2))
          || host.toLowerCase().endsWith(rule.substring(1).toLowerCase());
    }
    if (rule.startsWith(".")) {
      return host.toLowerCase().endsWith(rule.toLowerCase());
    }
    if (rule.contains("/")) {
      return cidr(rule, host);
    }
    return false;
  }

  private static boolean cidr(final String rule, final String host) {
    final var slash = rule.indexOf('/');
    final int bits;
    final long network;
    try {
      bits = Integer.parseInt(rule.substring(slash + 1));
      network = ipv4(InetAddress.getByName(rule.substring(0, slash)).getAddress());
    } catch (final Exception malformed) {
      return false;
    }
    if (bits < 0 || bits > 32) {
      return false;
    }
    final long mask = bits == 0 ? 0L : (0xFFFFFFFFL << (32 - bits)) & 0xFFFFFFFFL;
    try {
      for (final var address : InetAddress.getAllByName(host)) {
        final var bytes = address.getAddress();
        if (bytes.length == 4 && (ipv4(bytes) & mask) == (network & mask)) {
          return true;
        }
      }
    } catch (final Exception unresolved) {
      return false;
    }
    return false;
  }

  private static long ipv4(final byte[] bytes) {
    return ((bytes[0] & 0xFFL) << 24)
        | ((bytes[1] & 0xFFL) << 16)
        | ((bytes[2] & 0xFFL) << 8)
        | (bytes[3] & 0xFFL);
  }
}
