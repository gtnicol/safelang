package io.safelang.compiler.jvm;

import io.safelang.ModuleRegistry;
import io.safelang.ast.ProgramNode;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;

/**
 * Public entry point for the JVM backend: generates a class and packages it as an executable jar.
 */
public final class JvmBackend {

  private JvmBackend() {}

  /**
   * Compile {@code program} into a standalone executable jar at {@code output}, deriving the entry
   * class name from {@code base}. Returns the entry class's binary name (the jar's Main-Class).
   */
  public static String emit(
      final ProgramNode program,
      final ModuleRegistry registry,
      final String base,
      final Path output)
      throws IOException {
    final var className = sanitize(base);
    final var bytes = new JvmCodeGenerator(className, registry).generate(program);
    new JarAssembler().assemble(output, className, Map.of(className, bytes));
    return className;
  }

  /**
   * Compile {@code program} to raw class bytes for the entry class {@code className} (no jar). Used
   * by in-process harnesses that load the class directly rather than running a standalone jar.
   */
  public static byte[] classBytes(
      final ProgramNode program, final ModuleRegistry registry, final String className) {
    return new JvmCodeGenerator(className, registry).generate(program);
  }

  private static String sanitize(final String baseName) {
    final var builder = new StringBuilder();
    for (var index = 0; index < baseName.length(); index++) {
      final var character = baseName.charAt(index);
      final var valid =
          index == 0
              ? Character.isJavaIdentifierStart(character)
              : Character.isJavaIdentifierPart(character);
      builder.append(valid ? character : '_');
    }
    final var name = builder.toString();
    return name.isEmpty() || !Character.isJavaIdentifierStart(name.charAt(0))
        ? "Program" + name
        : name;
  }
}
