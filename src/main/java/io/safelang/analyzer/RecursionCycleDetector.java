package io.safelang.analyzer;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Tarjan's strongly-connected-components algorithm specialised for the call graph used by {@link
 * TerminationChecker}. Returns only the cycles — singletons without a self-loop are filtered out
 * because the termination analysis only cares about recursive groups.
 *
 * <p>This class is pure: it knows nothing about AST nodes or termination semantics. Pass it any
 * directed graph keyed by symbol name; it returns the recursive groups in discovery order.
 */
final class RecursionCycleDetector {

  private RecursionCycleDetector() {}

  /**
   * Find every cyclic strongly-connected component in {@code graph}. A node only present as an
   * outgoing edge target (not as a source key) is ignored, matching the behaviour the termination
   * checker relies on.
   */
  static List<Set<String>> cycles(final Map<String, Set<String>> graph) {
    final var result = new ArrayList<Set<String>>();
    final var indices = new HashMap<String, Integer>();
    final var lowlinks = new HashMap<String, Integer>();
    final var stacked = new HashSet<String>();
    final var stack = new ArrayDeque<String>();
    final int[] counter = {0};

    for (final var node : graph.keySet()) {
      if (!indices.containsKey(node)) {
        visit(node, graph, indices, lowlinks, stacked, stack, counter, result);
      }
    }
    return result;
  }

  private static void visit(
      final String node,
      final Map<String, Set<String>> graph,
      final Map<String, Integer> indices,
      final Map<String, Integer> lowlinks,
      final Set<String> stacked,
      final Deque<String> stack,
      final int[] counter,
      final List<Set<String>> result) {
    indices.put(node, counter[0]);
    lowlinks.put(node, counter[0]);
    counter[0]++;
    stack.push(node);
    stacked.add(node);

    for (final var target : graph.getOrDefault(node, Set.of())) {
      if (!graph.containsKey(target)) {
        continue;
      }
      if (!indices.containsKey(target)) {
        visit(target, graph, indices, lowlinks, stacked, stack, counter, result);
        lowlinks.put(node, Math.min(lowlinks.get(node), lowlinks.get(target)));
      } else if (stacked.contains(target)) {
        lowlinks.put(node, Math.min(lowlinks.get(node), indices.get(target)));
      }
    }

    if (lowlinks.get(node).equals(indices.get(node))) {
      final var component = new LinkedHashSet<String>();
      String popped;
      do {
        popped = stack.pop();
        stacked.remove(popped);
        component.add(popped);
      } while (!popped.equals(node));

      final var cyclic = component.size() > 1 || graph.getOrDefault(node, Set.of()).contains(node);
      if (cyclic) {
        result.add(component);
      }
    }
  }
}
