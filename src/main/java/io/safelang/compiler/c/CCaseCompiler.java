package io.safelang.compiler.c;

import io.safelang.ast.ASTNode;
import io.safelang.ast.CaseExpressionNode;
import io.safelang.ast.EnumPatternNode;
import io.safelang.ast.EnumVariantNode;
import io.safelang.ast.FunctionCallNode;
import io.safelang.ast.LiteralNode;
import java.util.Set;

/**
 * Emits C code for a SAFE {@code case ... of} expression.
 *
 * <p>The case expression lowers to a chain of nested ternary expressions ({@code (cond1 ? r1 :
 * (cond2 ? r2 : ... : fallback))}). Enum patterns compare against the variant's tag field; literal
 * patterns compare via {@code ==} (or {@code strcmp} for strings); pattern bindings are exposed to
 * the branch result via a GCC statement expression that introduces locals for each bound field.
 *
 * <p>Stateless apart from the injected {@link CCaseContext}.
 */
final class CCaseCompiler {

  private final CCaseContext context;
  private int counter;

  CCaseCompiler(final CCaseContext context) {
    this.context = context;
  }

  String compile(final CaseExpressionNode node) {
    final var builder = new StringBuilder();
    final var subject = context.emit(node.subject());

    // Detect if subject is an enum type for tag-based matching
    String matched = null;
    // First try type inference on the subject
    final var inferred = context.infer(node.subject());
    if (inferred != null && context.enumerations().containsKey(inferred)) {
      matched = inferred;
    }
    // Fall back to scanning variant names
    if (matched == null) {
      for (final var entry : context.enumerations().entrySet()) {
        for (final var variant : entry.getValue().variants()) {
          for (final var branch : node.branches()) {
            if (branch.pattern() instanceof EnumPatternNode ep) {
              if (variant.name().equals(ep.variant())) {
                matched = entry.getKey();
                break;
              }
            }
          }
          if (matched != null) break;
        }
        if (matched != null) break;
      }
    }

    // Bind the subject to a temp ONCE so an effectful streaming subject (e.g. `file:sline(r)`,
    // which
    // reads a fresh line on every evaluation) is not re-evaluated for each branch's tag check and
    // binding extraction. Scoped to the streaming s* builtins: their result enums hold only arena
    // strings/ints (never refcounted heap), so aliasing the temp is safe — whereas binding a temp
    // for a refcounted enum (e.g. lsm internals over `bytes`) would double-free under the C cycle
    // collector. Every other case keeps the original inline lowering.
    final var bindTemp = matched != null && streaming(node.subject());
    // Whether the matched enum is recursive (pointer-typed) — independent of bindTemp; it drives
    // both
    // the temp's C type and the `->` vs `.` member access.
    final var pointer = matched != null && context.recursive().contains(matched);
    final var temp = "__case" + counter++ + "__";
    final var subjectRef = bindTemp ? temp : subject;

    int depth = 0;

    // Check if all enum variants are covered (no fallthrough needed)
    boolean complete = false;
    if (matched != null && context.enumerations().containsKey(matched)) {
      final var variants = context.enumerations().get(matched).variants();
      long covered =
          node.branches().stream()
              .filter(b -> b.pattern() instanceof EnumPatternNode)
              .map(b -> ((EnumPatternNode) b.pattern()).variant())
              .distinct()
              .count();
      complete = covered >= variants.size();
    }

    if (!node.branches().isEmpty()) {
      for (int i = 0; i < node.branches().size(); i++) {
        final var branch = node.branches().get(i);
        // If all variants are covered, emit the last branch unconditionally
        final var exhaustive =
            complete
                && i == node.branches().size() - 1
                && !branch.isWildcard()
                && !branch.hasGuard();

        if (branch.isWildcard()) {
          final var result = context.emit(branch.result());
          if (branch.hasGuard()) {
            final var guard = context.emit(branch.guard());
            builder.append("((").append(guard).append(") ? ").append(result).append(" : ");
            depth++;
          } else {
            builder.append(result);
          }
        } else {
          final var pattern = branch.pattern();
          if (pattern instanceof EnumPatternNode ep && matched != null) {
            // Enum pattern: compare tag and bind variables
            // Use -> for recursive (pointer) enums, . for value enums
            final var access = pointer ? "->" : ".";
            final var condition = new StringBuilder();
            condition
                .append(subjectRef)
                .append(access)
                .append("tag == ")
                .append(matched)
                .append("_")
                .append(ep.variant());
            if (branch.hasGuard()) {
              // Bind variables for guard evaluation
              if (ep.hasBindings()) {
                final var variant = lookupVariant(matched, ep.variant());
                if (variant != null) {
                  for (int j = 0; j < ep.bindings().size() && j < variant.fields().size(); j++) {
                    context
                        .variables()
                        .put(ep.bindings().get(j), variant.fields().get(j).fullName());
                  }
                }
              }
              condition.append(" && ").append(context.emit(branch.guard()));
            }
            // Build result with bindings in scope via GCC statement expression
            final var result = new StringBuilder();
            if (ep.hasBindings()) {
              final var variant = lookupVariant(matched, ep.variant());
              if (variant != null && !ep.bindings().isEmpty()) {
                result.append("({ ");
                for (int j = 0; j < ep.bindings().size() && j < variant.fields().size(); j++) {
                  final var binding = ep.bindings().get(j);
                  final var mapped = context.translate(variant.fields().get(j).fullName());
                  result
                      .append(mapped)
                      .append(" ")
                      .append(context.user(binding))
                      .append(" = ")
                      .append(subjectRef)
                      .append(access)
                      .append("data.")
                      .append(ep.variant())
                      .append("._")
                      .append(j)
                      .append("; ");
                  context.variables().put(binding, variant.fields().get(j).fullName());
                }
                result.append(context.emit(branch.result())).append("; })");
              } else {
                result.append(context.emit(branch.result()));
              }
            } else {
              result.append(context.emit(branch.result()));
            }
            if (exhaustive) {
              builder.append(result);
            } else {
              builder.append("((").append(condition).append(") ? ").append(result).append(" : ");
              depth++;
            }
          } else {
            // Literal pattern: direct comparison
            String match;
            if (pattern instanceof LiteralNode) {
              match = context.emit(pattern);
            } else {
              match = "0";
            }
            final var condition = new StringBuilder();
            // Use strcmp for string comparisons instead of pointer equality
            if (pattern instanceof LiteralNode.StringLiteral) {
              condition
                  .append("strcmp(")
                  .append(subjectRef)
                  .append(", ")
                  .append(match)
                  .append(") == 0");
            } else {
              condition.append(subjectRef).append(" == ").append(match);
            }
            if (branch.hasGuard()) {
              condition.append(" && ").append(context.emit(branch.guard()));
            }
            final var result = context.emit(branch.result());
            builder.append("((").append(condition).append(") ? ").append(result).append(" : ");
            depth++;
          }
        }
      }

      final var last = node.branches().getLast();
      if ((!last.isWildcard() || last.hasGuard()) && !complete) {
        if (node.hasFallback()) {
          builder.append(context.emit(node.fallback()));
        } else if (matched != null && context.recursive().contains(matched)) {
          builder.append("NULL");
        } else if (matched != null && context.enumerations().containsKey(matched)) {
          builder.append("(").append(matched).append("){0}");
        } else {
          builder.append("0");
        }
      }

      builder.append(")".repeat(Math.max(0, depth)));
    }

    if (bindTemp) {
      // Evaluate the subject exactly once into a temp, then match against it.
      final var ctype = pointer ? matched + "*" : matched;
      return "({ " + ctype + " " + temp + " = " + subject + "; " + builder + "; })";
    }
    return builder.toString();
  }

  // The streaming file builtins whose result must be evaluated exactly once as a case subject.
  private static final Set<String> STREAMING =
      Set.of("sopen", "sline", "sread", "swrite", "sflush");

  private static boolean streaming(final ASTNode subject) {
    return subject instanceof FunctionCallNode call && STREAMING.contains(call.name());
  }

  /** Look up an enum variant by name within a known enum type. */
  private EnumVariantNode lookupVariant(final String name, final String label) {
    final var declaration = context.enumerations().get(name);
    if (declaration == null) return null;
    for (final var variant : declaration.variants()) {
      if (variant.name().equals(label)) return variant;
    }
    return null;
  }
}
