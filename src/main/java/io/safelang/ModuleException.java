package io.safelang;

/**
 * Thrown by {@link ModuleLoader} when a module cannot be parsed or is otherwise inconsistent
 * (circular import, name mismatch, missing header).
 *
 * <p>The "module not found" case has its own subclass {@link MissingModuleException} so callers
 * like {@link SafeFrontend} can tolerate optional preload misses without swallowing real errors.
 */
public class ModuleException extends SAFEException {

  public ModuleException(final String message) {
    super(message);
  }

  public ModuleException(final String message, final Throwable cause) {
    super(message, cause);
  }
}
