package io.safelang.compiler;

import io.safelang.ModuleRegistry;
import io.safelang.ast.ProgramNode;
import java.nio.file.Path;
import java.util.Objects;

/** Captures the context required by a backend compiler. */
public record CompileRequest(
    Path sourceFile, ProgramNode program, ModuleRegistry registry, boolean strict) {

  public CompileRequest {
    Objects.requireNonNull(sourceFile);
    Objects.requireNonNull(program);
    Objects.requireNonNull(registry);
  }

  public Path directory() {
    final var parent = sourceFile.getParent();
    return parent != null ? parent : Path.of(".");
  }

  public String baseName() {
    final var name = sourceFile.getFileName().toString();
    final var offset = name.lastIndexOf('.');
    return offset <= 0 ? name : name.substring(0, offset);
  }

  public Path binaryPath() {
    return directory().resolve(baseName());
  }

  public Path withExtension(final String extension) {
    return directory().resolve(baseName() + extension);
  }
}
