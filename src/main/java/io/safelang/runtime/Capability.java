package io.safelang.runtime;

/**
 * A host-access capability a SAFE program may require. Capabilities gate the <em>effect</em> a
 * builtin has on the host — reading files, reaching the network, spawning processes, reading the
 * environment, or reading stdin — and are enforced by the embedder's policy ({@link Capabilities}).
 *
 * <p>This is orthogonal to {@code --strict}: strict mode governs <em>determinism</em> (it also
 * rejects {@code time}/{@code rand}), whereas capabilities govern host access. A fully sandboxed
 * embedding denies the relevant capabilities <em>and</em> runs strict.
 */
public enum Capability {
  FILESYSTEM,
  NETWORK,
  PROCESS,
  ENVIRONMENT,
  STDIN;

  /** Parse a capability from its lower-case name or a short alias ({@code fs}/{@code net}/…). */
  public static Capability parse(final String token) {
    return switch (token.trim().toLowerCase()) {
      case "filesystem", "fs", "file" -> FILESYSTEM;
      case "network", "net", "http" -> NETWORK;
      case "process", "proc", "exec", "system" -> PROCESS;
      case "environment", "env" -> ENVIRONMENT;
      case "stdin", "input" -> STDIN;
      default -> throw new IllegalArgumentException("Unknown capability: " + token);
    };
  }
}
