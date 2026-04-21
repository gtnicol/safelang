package io.safelang.analyzer;

import io.safelang.ast.ImportNode;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Folds a program's {@link ImportNode} list into a module → allowed-symbols map.
 *
 * <p>Semantics (agreed with the user during fourth-round audit planning):
 *
 * <ul>
 *   <li>Any non-selective import of a module grants full access. Subsequent selective imports of
 *       the same module are absorbed into that full access.
 *   <li>Selective imports compose additively (union of symbol sets) only when no non-selective
 *       import of the same module has been seen.
 *   <li>A module absent from the returned map has either no imports or full access. Callers must
 *       track module presence separately if they need to distinguish the two.
 * </ul>
 *
 * <p>Replaces the overwrite-at-put pattern duplicated across {@code SemanticImportProcessor},
 * {@code Interpreter}, {@code BytecodeImportCompiler}, {@code CCodeGenerator}, and {@code
 * TypeRegistry}.
 */
public final class ImportResolver {

  private ImportResolver() {}

  public static Map<String, Set<String>> fold(final List<ImportNode> imports) {
    final var result = new HashMap<String, Set<String>>();
    final var full = new HashSet<String>();
    for (final var node : imports) {
      if (!node.isSelective()) {
        full.add(node.module());
        result.remove(node.module());
      } else if (!full.contains(node.module())) {
        result.computeIfAbsent(node.module(), k -> new HashSet<>()).addAll(node.symbols());
      }
    }
    return result;
  }
}
