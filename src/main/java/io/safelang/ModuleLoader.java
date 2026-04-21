package io.safelang;

import io.safelang.ast.*;
import io.safelang.parser.SAFEParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/**
 * Resolves module imports to files, parses them, and caches the results. Detects circular imports
 * and validates module headers.
 *
 * <p>Resolution is deterministic and does not depend on ambient process state ({@code user.dir},
 * cwd, environment). The search order is, in order of priority:
 *
 * <ol>
 *   <li>The directory of the importing source file (when supplied).
 *   <li>Each directory in the explicit {@code modulePath}, in the order given.
 *   <li>The {@code stdlib/} directory adjacent to the jar (when running from a packaged build).
 *   <li>The {@code /stdlib/} resource path baked into the jar (always present).
 * </ol>
 *
 * <p>This list is intentionally hermetic: the same program loaded from the same arguments resolves
 * the same modules regardless of where the JVM was launched from. Callers that need additional
 * search directories pass them via the {@code modulePath} parameter (the CLI surface for that is
 * the {@code --module-path} flag and the {@code SAFE_MODULE_PATH} environment variable, plumbed
 * through {@link io.safelang.SafeFrontend.Options#withModulePath(java.util.List)}).
 */
public class ModuleLoader {

  private final Map<String, ProgramNode> cache = new LinkedHashMap<>();
  private final Set<String> loading = new HashSet<>();
  private final List<Path> search;

  /**
   * Backwards-compatible constructor: equivalent to {@code new ModuleLoader(source, List.of())}.
   */
  public ModuleLoader(final Path source) {
    this(source, List.of());
  }

  /**
   * Create a loader with deterministic search paths.
   *
   * @param source path to the importing source file (used to derive relative search)
   * @param modulePath additional explicit search directories (may be empty)
   */
  public ModuleLoader(final Path source, final List<Path> modulePath) {
    this.search = new ArrayList<>();

    // 1. Directory of the importing file (local overrides take precedence).
    if (source != null) {
      final var parent = source.toAbsolutePath().getParent();
      if (parent != null) {
        search.add(parent);
      }
    }

    // 2. Explicit module path entries (the deterministic equivalent of
    //    cwd/stdlib that the previous behaviour relied on ambiently).
    if (modulePath != null) {
      for (final var path : modulePath) {
        if (path != null && Files.isDirectory(path)) {
          search.add(path);
        }
      }
    }

    // 3. stdlib/ relative to the jar location (packaged distribution only).
    try {
      final var jar =
          Path.of(ModuleLoader.class.getProtectionDomain().getCodeSource().getLocation().toURI());
      final var stdlib = jar.getParent().resolve("stdlib");
      if (Files.isDirectory(stdlib)) {
        search.add(stdlib);
      }
    } catch (Exception ignored) {
      // Not running from jar, skip.
    }

    // The bundled classpath stdlib (`/stdlib/` resource) is the authoritative
    // fallback — handled inside {@link #classpath(String)} when disk
    // resolution misses, so it does not need an entry in {@code search}.
  }

  /**
   * Load a module by name. Returns the parsed ProgramNode. Throws if the module can't be found, has
   * a circular dependency, or doesn't use a module header.
   */
  public ProgramNode load(final String name) {
    return load(name, null);
  }

  /**
   * Load {@code name}, preferring the directory of {@code origin} as an extra search path. This is
   * what lets a module find its siblings even when that directory was not on the top-level {@code
   * modulePath} — the javadoc contract at the class level promises deterministic resolution, and
   * promising "siblings of an imported module are findable" is part of that contract.
   */
  private ProgramNode load(final String name, final Path origin) {
    if (cache.containsKey(name)) {
      return cache.get(name);
    }

    if (loading.contains(name)) {
      throw new ModuleException("Circular import detected: " + name);
    }

    final var file = resolve(name, origin);
    if (file == null) {
      // Fallback: try classpath resources (stdlib bundled in JAR)
      final var loaded = classpath(name);
      if (loaded != null) {
        return loaded;
      }
      throw new MissingModuleException(name);
    }

    loading.add(name);
    try {
      final var source = Files.readString(file);
      final var program = SAFEParser.parse(source);

      if (!"module".equals(program.header())) {
        throw new ModuleException("File '" + file + "' is not a module (missing 'module' header)");
      }

      if (!name.equals(program.name())) {
        throw new ModuleException(
            "Module identity mismatch: file '"
                + file
                + "' declares 'module "
                + program.name()
                + "' but was loaded as '"
                + name
                + "'");
      }

      // Recursively load any imports this module has, passing the just-loaded
      // file as the new origin so sibling modules can be found beside it.
      for (final var imported : program.imports()) {
        load(imported.module(), file);
      }

      cache.put(name, program);
      return program;
    } catch (IOException exception) {
      throw new ModuleException(
          "Cannot read module file: " + file + " — " + exception.getMessage(), exception);
    } finally {
      loading.remove(name);
    }
  }

  /**
   * Resolve a module name to a file path, optionally consulting the directory of {@code origin}
   * (the file that triggered this import) before falling back to the static search list.
   */
  private Path resolve(final String name, final Path origin) {
    if (!name.matches("[a-zA-Z_][a-zA-Z0-9_]*")) {
      throw new ModuleException("Invalid module name: " + name);
    }
    final var filename = name + ".safe";
    if (origin != null) {
      final var parent = origin.toAbsolutePath().getParent();
      if (parent != null) {
        final var sibling = parent.resolve(filename);
        if (Files.isRegularFile(sibling)) {
          return sibling;
        }
      }
    }
    for (final var directory : search) {
      final var candidate = directory.resolve(filename);
      if (Files.isRegularFile(candidate)) {
        return candidate;
      }
    }
    return null;
  }

  /**
   * Try to load a module from classpath resources (stdlib bundled in JAR).
   *
   * <p>Returns {@code null} only for the legitimate "not bundled on this classpath" case (null
   * stream). Any other failure — I/O error while reading an existing stream, parse error, wrong
   * header, or name mismatch — indicates a corrupt packaging and is surfaced via {@link
   * ModuleException} so callers cannot silently render corruption as "module not found".
   */
  private ProgramNode classpath(final String name) {
    if (!name.matches("[a-zA-Z_][a-zA-Z0-9_]*")) return null;
    final var resource = "/stdlib/" + name + ".safe";
    final String source;
    try (var stream = ModuleLoader.class.getResourceAsStream(resource)) {
      if (stream == null) return null;
      source = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
    } catch (final IOException exception) {
      throw new ModuleException(
          "Bundled stdlib module '" + name + "' is unreadable: " + exception.getMessage(),
          exception);
    }
    final var program = parseBundled(name, source);

    loading.add(name);
    try {
      for (final var imported : program.imports()) {
        load(imported.module());
      }
      cache.put(name, program);
      return program;
    } finally {
      loading.remove(name);
    }
  }

  /**
   * Parse and validate a bundled stdlib source string. Package-private for tests: corruption
   * detection is exercised via this static entry point so we can inject text fixtures without
   * classloader gymnastics.
   *
   * @throws ModuleException if the source fails to parse, or declares a non-module header or a
   *     mismatched module name
   */
  static ProgramNode parseBundled(final String name, final String source) {
    final ProgramNode program;
    try {
      program = SAFEParser.parse(source);
    } catch (final Exception exception) {
      throw new ModuleException(
          "Bundled stdlib module '"
              + name
              + "' is corrupt (parse failed): "
              + exception.getMessage(),
          exception);
    }
    if (!"module".equals(program.header())) {
      throw new ModuleException(
          "Bundled stdlib module '" + name + "' is corrupt (missing 'module' header)");
    }
    if (!name.equals(program.name())) {
      throw new ModuleException(
          "Bundled stdlib module '"
              + name
              + "' is corrupt (declares 'module "
              + program.name()
              + "')");
    }
    return program;
  }

  /** Get all loaded modules. */
  public Map<String, ProgramNode> loaded() {
    return Collections.unmodifiableMap(cache);
  }
}
