package io.safelang.compiler;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Represents the output of a backend compilation. */
public final class SafeCompileResult {

  private final Path output;
  private final List<Path> artifacts;
  private final Optional<String> runInstruction;

  public SafeCompileResult(Path output) {
    this(output, List.of(), null);
  }

  public SafeCompileResult(Path output, List<Path> artifacts) {
    this(output, artifacts, null);
  }

  public SafeCompileResult(Path output, List<Path> artifacts, String runInstruction) {
    this.output = Objects.requireNonNull(output);
    this.artifacts = List.copyOf(artifacts != null ? artifacts : List.of());
    this.runInstruction = Optional.ofNullable(runInstruction);
  }

  public Path output() {
    return output;
  }

  public List<Path> artifacts() {
    return artifacts;
  }

  public Optional<String> runInstruction() {
    return runInstruction;
  }
}
