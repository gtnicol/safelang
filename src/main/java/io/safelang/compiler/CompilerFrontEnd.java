package io.safelang.compiler;

import io.safelang.ModuleRegistry;
import io.safelang.SafeFrontend;
import io.safelang.ast.ProgramNode;
import io.safelang.runtime.BuiltinRegistry;
import java.nio.file.Path;
import java.util.List;

/**
 * Handles parsing and semantic validation for SAFE programs.
 *
 * <p>{@link #parse} accepts a {@code null} filename and uses it only for relative
 * module-search-path resolution. {@link #request} requires a non-null filename because the
 * resulting {@link CompileRequest} embeds the binary output path derived from it.
 *
 * <p>An optional {@code modulePath} threads explicit additional search directories through to
 * {@link io.safelang.ModuleLoader}. Callers that don't care use the no-modulePath overloads, which
 * delegate with an empty list.
 */
public final class CompilerFrontEnd {

  private CompilerFrontEnd() {}

  public static ParseResult parse(
      final String source, final String filename, final boolean strict) {
    return parse(source, filename, strict, List.of());
  }

  public static ParseResult parse(
      final String source,
      final String filename,
      final boolean strict,
      final List<Path> modulePath) {
    final var options =
        SafeFrontend.Options.defaults()
            .withSource(filename != null ? Path.of(filename) : null)
            .withStrict(strict)
            .withBindings(BuiltinRegistry.variables().keySet())
            .withModulePath(modulePath != null ? modulePath : List.of());
    return SafeFrontend.bootstrap(source, options);
  }

  public static CompileRequest request(
      final String source, final String filename, final boolean strict) {
    return request(source, filename, strict, List.of());
  }

  public static CompileRequest request(
      final String source,
      final String filename,
      final boolean strict,
      final List<Path> modulePath) {
    if (filename == null) {
      throw new IllegalArgumentException(
          "CompilerFrontEnd.request: filename is required (the compile output "
              + "path is derived from it). Use parse() instead if no filename is available.");
    }
    final var result = parse(source, filename, strict, modulePath);
    return new CompileRequest(Path.of(filename), result.program(), result.registry(), strict);
  }

  public record ParseResult(ProgramNode program, ModuleRegistry registry, List<String> warnings) {

    public ParseResult {
      warnings = warnings != null ? List.copyOf(warnings) : List.of();
    }

    public ParseResult(final ProgramNode program, final ModuleRegistry registry) {
      this(program, registry, List.of());
    }
  }
}
