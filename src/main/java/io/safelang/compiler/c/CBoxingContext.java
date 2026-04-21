package io.safelang.compiler.c;

import io.safelang.ast.TypeDeclarationNode;
import java.util.Map;

/**
 * Read-only state {@link CBoxing} needs to translate a SAFE type name into the matching field of
 * the C {@code SAFEValue} tagged union.
 */
interface CBoxingContext {

  /** Map a SAFE type name (e.g. {@code "list<int>"}) to its concrete C type. */
  String translate(String type);

  /** Live view of the currently-known struct declarations, keyed by type name. */
  Map<String, TypeDeclarationNode> structs();
}
