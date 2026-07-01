package io.safelang;

import static org.junit.jupiter.api.Assertions.*;

import io.safelang.runtime.Capabilities;
import io.safelang.runtime.HostPolicy;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Host policy refinements: filesystem jail, egress allowlist, exec allowlist, serve bind. */
class HostPolicyTests {

  private Path root;

  @BeforeEach
  void setup() throws Exception {
    root = Files.createTempDirectory("safe-jail");
  }

  @AfterEach
  void teardown() throws Exception {
    try (var paths = Files.walk(root)) {
      paths.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
    }
  }

  private HostPolicy jailed() {
    return HostPolicy.sandbox().toBuilder().capabilities(Capabilities.all()).fsRoot(root).build();
  }

  @Test
  void testInRootResolves() throws IOException {
    final var resolved = jailed().resolve("sub/file.txt");
    assertTrue(resolved.startsWith(root.toRealPath()));
  }

  @Test
  void testRelativeEscapeRejected() {
    assertThrows(IOException.class, () -> jailed().resolve("../escape.txt"));
  }

  @Test
  void testAbsoluteEscapeRejected() {
    assertThrows(IOException.class, () -> jailed().resolve("/etc/passwd"));
  }

  @Test
  void testDeepRelativeEscapeRejected() {
    assertThrows(IOException.class, () -> jailed().resolve("a/b/../../../escape.txt"));
  }

  @Test
  void testSymlinkComponentEscapeRejected() throws IOException {
    // A symlinked existing component that points outside the jail is rejected (resolve
    // canonicalizes
    // the existing prefix, so the escape is caught at check time rather than followed by the op).
    final var outside = Files.createTempDirectory("safe-outside");
    try {
      final var link = root.resolve("link");
      Files.createSymbolicLink(link, outside);
      assertThrows(IOException.class, () -> jailed().resolve("link/file.txt"));
    } finally {
      outside.toFile().delete();
    }
  }

  @Test
  void testSymlinkInRootResolvesToCanonical() throws IOException {
    // A path with no symlink resolves to a canonical path still under the (realpath'd) root.
    final var resolved = jailed().resolve("sub/file.txt");
    assertTrue(resolved.startsWith(root.toRealPath()));
  }

  @Test
  void testNoRootIsUnrestricted() throws IOException {
    assertEquals(Path.of("/etc/passwd"), HostPolicy.trusted().resolve("/etc/passwd"));
  }

  @Test
  void testEgressAllowlist() {
    final var policy =
        HostPolicy.sandbox().toBuilder()
            .netAllow(java.util.List.of("example.com", "*.test"))
            .build();
    assertTrue(policy.hostAllowed("example.com"));
    assertTrue(policy.hostAllowed("api.test"));
    assertFalse(policy.hostAllowed("evil.com"));
  }

  @Test
  void testEgressCidr() {
    final var policy =
        HostPolicy.sandbox().toBuilder().netAllow(java.util.List.of("127.0.0.0/8")).build();
    assertTrue(policy.hostAllowed("127.0.0.1"));
    assertFalse(policy.hostAllowed("8.8.8.8"));
  }

  @Test
  void testEgressNoListAllowsAll() {
    assertTrue(HostPolicy.trusted().hostAllowed("anything.example.com"));
  }

  @Test
  void testExecAllowlist() {
    final var policy =
        HostPolicy.sandbox().toBuilder().execAllow(java.util.List.of("/bin/echo")).build();
    assertTrue(policy.execAllowed("/bin/echo"));
    assertFalse(policy.execAllowed("/bin/sh"));
  }

  @Test
  void testExecNoListAllowsAll() {
    assertTrue(HostPolicy.trusted().execAllowed("/bin/anything"));
  }

  @Test
  void testEgressBlocksInternalIpsByDefault() {
    // No allowlist set: public hosts reachable, internal IPs blocked by default (SSRF guard).
    final var open = HostPolicy.trusted();
    assertTrue(open.egressAllowed("http://8.8.8.8/")); // public
    assertFalse(open.egressAllowed("http://127.0.0.1/")); // loopback
    assertFalse(open.egressAllowed("http://169.254.169.254/latest/meta-data")); // metadata
    assertFalse(open.egressAllowed("http://10.0.0.5/")); // private
    assertFalse(open.egressAllowed("http://192.168.1.1/")); // private
    assertFalse(open.egressAllowed("http://[::1]/")); // ipv6 loopback
    assertFalse(open.egressAllowed("http://[fc00::1]/")); // ipv6 unique-local (fc00::/7)
    assertFalse(open.egressAllowed("http://[fd12:3456::1]/")); // ipv6 ula (fd00::/8)
    assertFalse(open.egressAllowed("http://100.64.0.1/")); // ipv4 carrier-grade NAT (100.64/10)
  }

  @Test
  void testEgressReturnsFalseForMalformedUrl() {
    // A malformed URL must return false (the builtin then yields Err), never throw.
    final var open = HostPolicy.trusted();
    assertFalse(open.egressAllowed("ht!tp://no where/x"));
    assertFalse(open.egressAllowed("not a url"));
  }

  @Test
  void testEgressAllowlistOverridesInternalBlock() {
    // An explicit net-allow entry re-permits a deliberately-trusted internal target.
    final var policy =
        HostPolicy.sandbox().toBuilder().netAllow(java.util.List.of("10.0.0.5")).build();
    assertTrue(policy.egressAllowed("http://10.0.0.5/"));
    assertFalse(policy.egressAllowed("http://evil.example.com/")); // allowlist is exclusive
  }

  @Test
  void testServeBindDefaultsLoopback() {
    assertEquals("127.0.0.1", HostPolicy.trusted().serveBind());
    assertEquals("127.0.0.1", HostPolicy.sandbox().serveBind());
  }
}
