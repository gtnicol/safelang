package io.safelang;

/**
 * Specific {@link ModuleException} for the "module not resolvable" case — thrown by {@link
 * ModuleLoader} when neither the file system nor the classpath has a {@code .safe} file matching
 * the requested name.
 *
 * <p>Distinct from the broader {@link ModuleException} so optional preloads can be tolerated
 * without also swallowing parse errors, circular imports, or header mismatches.
 */
public final class MissingModuleException extends ModuleException {

  private final String module;

  public MissingModuleException(final String module) {
    super("Module not found: " + module);
    this.module = module;
  }

  public String module() {
    return module;
  }
}
