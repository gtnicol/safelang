package io.safelang.compiler.c;

import io.safelang.ast.*;

final class CFormatResolver {

  private final CFormatContext context;

  CFormatResolver(final CFormatContext context) {
    this.context = context;
  }

  String format(final ASTNode node) {
    if (node instanceof LiteralNode literal) {
      return switch (literal) {
        case LiteralNode.StringLiteral ignored -> "%s";
        case LiteralNode.IntLiteral ignored -> "%lld";
        case LiteralNode.UintLiteral ignored -> "%llu";
        case LiteralNode.FloatLiteral ignored -> "%g";
        case LiteralNode.BoolLiteral ignored -> "%d";
      };
    }
    if (node instanceof FunctionCallNode call) {
      final var name = call.name();
      if ("len".equals(name)
          || "int".equals(name)
          || "integer".equals(name)
          || "floor".equals(name)
          || "ceil".equals(name)
          || "round".equals(name)
          || "randint".equals(name)
          || "sign".equals(name)
          || "factorial".equals(name)
          || "gcd".equals(name)
          || "lcm".equals(name)
          || "fib".equals(name)) return "%lld";
      if ("float".equals(name)
          || "decimal".equals(name)
          || "sqrt".equals(name)
          || "pow".equals(name)
          || "abs".equals(name)
          || "min".equals(name)
          || "max".equals(name)
          || "log".equals(name)
          || "sin".equals(name)
          || "cos".equals(name)
          || "tan".equals(name)
          || "asin".equals(name)
          || "acos".equals(name)
          || "atan".equals(name)
          || "atan2".equals(name)
          || "exp".equals(name)
          || "log10".equals(name)
          || "rand".equals(name)
          || "clamp".equals(name)
          || "sum".equals(name)) return "%g";
      if ("str".equals(name)
          || "substring".equals(name)
          || "trim".equals(name)
          || "upper".equals(name)
          || "lower".equals(name)
          || "replace".equals(name)
          || "charAt".equals(name)
          || "replaceall".equals(name)) return "%s";
      if ("matches".equals(name)
          || "isdir".equals(name)
          || "starts".equals(name)
          || "ends".equals(name)
          || "empty".equals(name)
          || "blank".equals(name)
          || "prime".equals(name)
          || "mkdir".equals(name)
          || "rmdir".equals(name)) return "%d";
      final var declaration = context.function(name);
      if (declaration != null && declaration.returns() != null) {
        return specifier(declaration.returns().name());
      }
    }
    if (node instanceof StringInterpolationNode) return "%s";
    if (node instanceof BinaryExpressionNode binary) {
      if ("+".equals(binary.operator())) {
        if (context.stringlike(binary.left()) || context.stringlike(binary.right())) return "%s";
      }
      final var operator = binary.operator();
      if ("==".equals(operator)
          || "!=".equals(operator)
          || "<".equals(operator)
          || "<=".equals(operator)
          || ">".equals(operator)
          || ">=".equals(operator)
          || "&&".equals(operator)
          || "||".equals(operator)) {
        return "%d";
      }
    }
    if (node instanceof VariableReferenceNode reference && reference.parts().size() == 1) {
      final var type = context.variables().get(reference.parts().getFirst());
      if (type != null) {
        return switch (type) {
          case "string" -> "%s";
          case "float" -> "%g";
          case "boolean" -> "%d";
          case "uint" -> "%llu";
          default -> "%lld";
        };
      }
    }
    // Struct field access (e.g. r.body) — either a FieldAccessNode or a multi-part reference.
    // Route through type inference so a string field prints with %s, not the %lld fallback.
    if (node instanceof FieldAccessNode
        || (node instanceof VariableReferenceNode reference && reference.parts().size() >= 2)) {
      return specifier(context.infer(node));
    }
    if (node instanceof IndexAccessNode
        || node instanceof IfExpressionNode
        || node instanceof CaseExpressionNode
        || node instanceof DoExpressionNode
        || node instanceof FunctionCallNode) {
      return specifier(context.infer(node));
    }
    return "%lld";
  }

  String specifier(final String type) {
    if (type == null) return "%lld";
    return switch (type) {
      case "string" -> "%s";
      case "void" -> "%s";
      case "float" -> "%g";
      case "boolean" -> "%d";
      case "uint" -> "%llu";
      default -> "%lld";
    };
  }
}
