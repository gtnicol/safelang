package io.safelang.compiler.c;

import io.safelang.ast.EnumDeclarationNode;
import java.util.Map;
import java.util.Set;

final class CTypeMapper {

  private final Set<String> recursive;
  private final Map<String, EnumDeclarationNode> enumerations;

  CTypeMapper(final Set<String> recursive, final Map<String, EnumDeclarationNode> enumerations) {
    this.recursive = recursive;
    this.enumerations = enumerations;
  }

  String translate(final String type) {
    if (type == null) return "void";
    if (type.startsWith("?")) return "void*";

    if (type.startsWith("list<")) return "SAFEList*";
    if (type.startsWith("map<")) return "SAFEMap*";
    if (type.startsWith("tuple<") || type.equals("tuple")) return "SAFETuple";
    if (type.startsWith("set<") || type.equals("set")) return "SAFESet*";
    // Closures are always heap-boxed so the captures array + retained
    // heap captures are reclaimed via safe_dispose_closure at refs=0.
    // See bug 006: unboxed SAFEClosure values leak their __captures__
    // block and every retained heap capture.
    if (type.startsWith("fn<") || type.equals("fn")) return "SAFEClosure*";

    if (type.contains("|")) {
      final var members = type.split("\\|");
      boolean floating = false;
      for (final var member : members) {
        if ("float".equals(member.trim())) {
          floating = true;
          break;
        }
      }
      if (floating) return "double";
      return "int64_t";
    }

    return switch (type) {
      case "int" -> "int64_t";
      case "uint" -> "uint64_t";
      case "float" -> "double";
      case "string" -> "char*";
      case "bytes" -> "SAFEBytes*";
      case "boolean" -> "bool";
      case "void" -> "void";
      default -> {
        if (recursive.contains(type)) yield type + "*";
        if (enumerations.containsKey(type)) yield type;
        yield type;
      }
    };
  }
}
