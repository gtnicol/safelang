package io.safelang.analyzer;

import io.safelang.ast.*;
import io.safelang.runtime.BuiltinRegistry;
import java.util.*;

/**
 * Heuristic static termination analysis for recursive functions — the compile-time half of SAFE's
 * two-tier termination model. The runtime half is the {@code decreases(expr)} clause, enforced by
 * every backend (interpreter, bytecode VM, native C, WebAssembly) on every recursive call.
 *
 * <p>This pass is intentionally heuristic. Recursive functions that cannot be statically proven
 * structurally or numerically decreasing must declare an explicit {@code decreases(expr)} clause,
 * which is then checked at runtime. Functions that are neither statically provable nor carry a
 * {@code decreases} clause are rejected at compile time, so a SAFE program that type-checks always
 * has a termination story for every recursive function — either static or runtime.
 *
 * <p>Static checks performed:
 *
 * <ul>
 *   <li><b>Structural recursion</b> on recursive enum types: at least one argument must be a strict
 *       sub-component (bound via case/of pattern matching) at each recursive call site.
 *   <li><b>Numeric decrease</b>: for int/uint parameters, at least one argument must decrease
 *       (subtraction by positive literal, division by &gt;1, right shift). Other arguments are not
 *       tracked and may grow.
 *   <li><b>Base case detection</b>: syntactic check that at least one non-recursive branch exists
 *       in a conditional (if/case). Recursive calls in conditions and guards are detected.
 *   <li><b>Mutual recursion</b>: SCC detection via Tarjan's algorithm; each cycle is checked as a
 *       group for structural decrease.
 *   <li><b>Explicit measures</b>: {@code decreases(expr)} clauses bypass the static check and are
 *       enforced at runtime. The expression must be {@code int} or {@code uint}, non-negative, and
 *       strictly decreasing across recursive calls; any backend traps otherwise.
 * </ul>
 *
 * <p><b>Known limitations of the static pass:</b> base case detection is syntactic only (not
 * path-sensitive); non-decreasing arguments are not tracked; a single decreasing argument suffices
 * even when others grow without bound. Programs that need stronger guarantees should rely on the
 * runtime {@code decreases} mechanism, which is exact and backend-uniform.
 */
class TerminationChecker {

  private final Map<String, FunctionDeclarationNode> functions;
  private final Map<String, EnumDeclarationNode> enums;
  private final Map<String, EnumDeclarationNode> recursive;
  private final boolean module;
  private final Set<String> nonStrict = new HashSet<>();
  private final Map<String, ASTNode> constants = new HashMap<>();
  private Map<String, String> aliases = new HashMap<>();

  TerminationChecker(
      final Map<String, FunctionDeclarationNode> functions,
      final Map<String, EnumDeclarationNode> enums,
      final boolean module) {
    this(functions, enums, module, List.of());
  }

  TerminationChecker(
      final Map<String, FunctionDeclarationNode> functions,
      final Map<String, EnumDeclarationNode> enums,
      final boolean module,
      final List<ASTNode> statements) {
    this.functions = functions;
    this.enums = enums;
    this.module = module;
    this.recursive = new HashMap<>();
    detect();
    scan(statements);
  }

  void check() {
    Map<String, Set<String>> graph = graph();
    final var groups = RecursionCycleDetector.cycles(graph);
    for (final var group : groups) {
      check(group);
    }
  }

  private void detect() {
    // Build enum dependency graph: enum A → enum B if any variant of A has a field of type B
    final var graph = new HashMap<String, Set<String>>();
    for (final var entry : enums.entrySet()) {
      final var deps = new HashSet<String>();
      for (final var variant : entry.getValue().variants()) {
        for (final var field : variant.fields()) {
          if (enums.containsKey(field.name())) {
            deps.add(field.name());
          }
        }
      }
      graph.put(entry.getKey(), deps);
    }
    // Any enum in an SCC (size > 1, or self-edge) is recursive
    final var groups = RecursionCycleDetector.cycles(graph);
    for (final var group : groups) {
      for (final var name : group) {
        recursive.put(name, enums.get(name));
      }
    }
  }

  private Map<String, Set<String>> graph() {
    final var graph = new HashMap<String, Set<String>>();
    for (final var entry : functions.entrySet()) {
      final var targets = new HashSet<String>();
      final var aliases = new HashMap<String, String>();
      final var function = entry.getValue();
      // Scan default parameter values
      for (final var parameter : function.parameters()) {
        if (parameter.hasDefault()) {
          collect(parameter.initial(), targets, aliases);
        }
      }
      // Scan requires/ensures contracts
      if (function.hasRequires()) collect(function.requires(), targets, aliases);
      if (function.hasEnsures()) collect(function.ensures(), targets, aliases);
      for (final var node : function.body()) {
        collect(node, targets, aliases);
      }
      graph.put(entry.getKey(), targets);
    }
    return graph;
  }

  private void collect(
      final ASTNode node, final Set<String> targets, final Map<String, String> aliases) {
    if (node == null) return;
    new CallEdgeCollector(targets, aliases).walk(node);
  }

  /**
   * Full-tree visitor that records call edges and function-reference identifiers into {@code
   * targets}, and tracks variable→function aliases in {@code aliases}. Inherits structural
   * recursion from {@link TraversingASTVisitor} so only the three nodes with custom behaviour —
   * variable reference, function call, variable/assignment alias tracking — need explicit handling.
   */
  private final class CallEdgeCollector extends TraversingASTVisitor<Void> {

    private final Set<String> targets;
    private final Map<String, String> tracked;

    CallEdgeCollector(final Set<String> targets, final Map<String, String> tracked) {
      this.targets = targets;
      this.tracked = tracked;
    }

    /** Entry point that accepts any ASTNode and runs it through {@code this} visitor. */
    void walk(final ASTNode node) {
      node.accept(this);
    }

    @Override
    public Void visitVariableReference(final VariableReferenceNode reference) {
      if (!reference.hasPrefix()
          && reference.parts().size() == 1
          && functions.containsKey(reference.parts().get(0))) {
        targets.add(reference.parts().get(0));
      }
      return null;
    }

    @Override
    public Void visitFunctionCall(final FunctionCallNode call) {
      final var name = call.hasPrefix() ? null : call.name();
      if (name != null && functions.containsKey(name)) {
        targets.add(name);
      } else if (name != null && tracked.containsKey(name)) {
        targets.add(tracked.get(name));
      }
      return super.visitFunctionCall(call);
    }

    @Override
    public Void visitVariableDeclaration(final VariableDeclarationNode declaration) {
      if (declaration.hasInitializer()) {
        track(declaration.name(), declaration.initializer());
      }
      return super.visitVariableDeclaration(declaration);
    }

    @Override
    public Void visitAssignment(final AssignmentNode assignment) {
      if (assignment.parts().size() == 1) {
        track(assignment.parts().get(0), assignment.value());
      }
      return super.visitAssignment(assignment);
    }

    private void track(final String alias, final ASTNode value) {
      if (value instanceof VariableReferenceNode reference
          && !reference.hasPrefix()
          && reference.parts().size() == 1) {
        final var target = reference.parts().get(0);
        if (functions.containsKey(target)) {
          tracked.put(alias, target);
        }
      }
    }
  }

  private void check(final Set<String> group) {
    nonStrict.clear();

    for (final var name : group) {
      final var function = functions.get(name);
      aliases = new HashMap<>();
      final var params = new ArrayList<ParameterNode>();
      for (final var parameter : function.parameters()) {
        if (recursive.containsKey(parameter.type().name())) {
          params.add(parameter);
        }
      }

      if (params.isEmpty()) {
        if (group.size() == 1) {
          // Self-recursive: check arithmetic decrease or decreases clause
          arithmetic(name, function);
        } else if (!function.hasDecreases()) {
          final var sorted = new ArrayList<>(group);
          Collections.sort(sorted);
          error(
              "Function '"
                  + name
                  + "' in mutual recursion cycle "
                  + sorted
                  + " has no parameter of a recursive enum type for structural decrease",
              function);
        }
        continue;
      }

      final var components = new HashMap<String, String>();
      // Walk default parameter values
      for (final var parameter : function.parameters()) {
        if (parameter.hasDefault()) {
          walk(parameter.initial(), name, group, function.parameters(), params, components);
        }
      }
      // Walk requires/ensures contracts
      if (function.hasRequires())
        walk(function.requires(), name, group, function.parameters(), params, components);
      if (function.hasEnsures())
        walk(function.ensures(), name, group, function.parameters(), params, components);
      for (final var node : function.body()) {
        walk(node, name, group, function.parameters(), params, components);
      }
    }

    if (hasNonStrictCycle(group)) {
      final var sorted = new ArrayList<>(group);
      Collections.sort(sorted);
      final var type = enumerated(group);
      final var path = cycle(group);
      error(
          "Mutual recursion cycle "
              + sorted
              + " does not structurally decrease on type '"
              + type
              + "'"
              + (path.isEmpty() ? "" : " (call chain: " + String.join(" -> ", path) + ")"),
          functions.get(sorted.get(0)));
    }
  }

  private String enumerated(final Set<String> group) {
    for (final var name : group) {
      final var function = functions.get(name);
      for (final var parameter : function.parameters()) {
        if (recursive.containsKey(parameter.type().name())) {
          return parameter.type().name();
        }
      }
    }
    return "?";
  }

  private boolean hasNonStrictCycle(final Set<String> group) {
    final var edges = new HashMap<String, Set<String>>();
    for (final var name : group) {
      edges.put(name, new HashSet<>());
    }
    for (final var edge : nonStrict) {
      final var parts = edge.split("→");
      if (edges.containsKey(parts[0])) {
        edges.get(parts[0]).add(parts[1]);
      }
    }

    for (final var start : group) {
      final var visited = new HashSet<String>();
      if (dfs(start, start, edges, visited)) {
        return true;
      }
    }
    return false;
  }

  private boolean dfs(
      final String current,
      final String target,
      final Map<String, Set<String>> edges,
      final Set<String> visited) {
    for (final var next : edges.getOrDefault(current, Set.of())) {
      if (next.equals(target)) {
        return true;
      }
      if (visited.add(next)) {
        if (dfs(next, target, edges, visited)) {
          return true;
        }
      }
    }
    return false;
  }

  private List<String> cycle(final Set<String> group) {
    final var edges = new HashMap<String, Set<String>>();
    for (final var name : group) {
      edges.put(name, new HashSet<>());
    }
    for (final var edge : nonStrict) {
      final var parts = edge.split("→");
      if (edges.containsKey(parts[0])) {
        edges.get(parts[0]).add(parts[1]);
      }
    }
    for (final var start : group) {
      final var path = new ArrayList<String>();
      path.add(start);
      if (trace(start, start, edges, new HashSet<>(), path)) {
        return path;
      }
    }
    return List.of();
  }

  private boolean trace(
      final String current,
      final String target,
      final Map<String, Set<String>> edges,
      final Set<String> visited,
      final List<String> path) {
    for (final var next : edges.getOrDefault(current, Set.of())) {
      if (next.equals(target)) {
        path.add(next);
        return true;
      }
      if (visited.add(next)) {
        path.add(next);
        if (trace(next, target, edges, visited, path)) {
          return true;
        }
        path.remove(path.size() - 1);
      }
    }
    return false;
  }

  private void walk(
      final ASTNode node,
      final String caller,
      final Set<String> group,
      final List<ParameterNode> all,
      final List<ParameterNode> params,
      final Map<String, String> components) {
    if (node == null) return;
    new RecursionWalker(caller, group, all, params, components).walk(node);
  }

  /**
   * Full-tree visitor that drives the recursive-call verification. Inherits the uniform "visit all
   * children" traversal from {@link TraversingASTVisitor}; only the nodes that feed custom
   * behaviour — function calls to verify, variable tracking, and case expressions that bind
   * sub-components — override their visit methods.
   */
  private final class RecursionWalker extends TraversingASTVisitor<Void> {

    private final String caller;
    private final Set<String> group;
    private final List<ParameterNode> all;
    private final List<ParameterNode> params;
    private Map<String, String> components;

    RecursionWalker(
        final String caller,
        final Set<String> group,
        final List<ParameterNode> all,
        final List<ParameterNode> params,
        final Map<String, String> components) {
      this.caller = caller;
      this.group = group;
      this.all = all;
      this.params = params;
      this.components = components;
    }

    void walk(final ASTNode node) {
      node.accept(this);
    }

    @Override
    public Void visitFunctionCall(final FunctionCallNode call) {
      // Delegate to the verify-and-recurse helper which knows how to
      // distinguish calls we must check (in-cycle) from simple descents.
      TerminationChecker.this.walk(call, caller, group, all, params, components);
      return null;
    }

    @Override
    public Void visitVariableDeclaration(final VariableDeclarationNode declaration) {
      if (declaration.hasInitializer()) {
        walk(declaration.initializer());
        track(declaration.name(), declaration.initializer(), group);
      }
      return null;
    }

    @Override
    public Void visitAssignment(final AssignmentNode assignment) {
      walk(assignment.value());
      if (assignment.parts().size() == 1) {
        track(assignment.parts().get(0), assignment.value(), group);
      }
      return null;
    }

    @Override
    public Void visitCaseExpression(final CaseExpressionNode node) {
      walk(node.subject());
      for (final var caseBranch : node.branches()) {
        // branch() creates its own extended components map for the branch
        // result's walk, so sibling branches see the original mapping.
        branch(caseBranch, node.subject(), caller, group, all, params, components);
      }
      if (node.hasFallback()) {
        walk(node.fallback());
      }
      return null;
    }
  }

  private void walk(
      final FunctionCallNode call,
      final String caller,
      final Set<String> group,
      final List<ParameterNode> all,
      final List<ParameterNode> params,
      final Map<String, String> components) {
    for (final var argument : call.arguments()) {
      walk(argument, caller, group, all, params, components);
    }

    final var target = resolve(call, group);
    if (target == null) {
      return;
    }

    verify(call, target, caller, all, params, components);
  }

  private void track(final String alias, final ASTNode value, final Set<String> group) {
    final var target = target(value, group);
    if (target != null) {
      aliases.put(alias, target);
    } else {
      aliases.remove(alias);
    }
  }

  private String target(final ASTNode value, final Set<String> group) {
    if (!(value instanceof VariableReferenceNode reference)
        || reference.hasPrefix()
        || reference.parts().size() != 1) {
      return null;
    }

    final var name = reference.parts().get(0);
    if (group.contains(name)) {
      return name;
    }

    final var aliased = aliases.get(name);
    if (aliased != null && group.contains(aliased)) {
      return aliased;
    }
    return null;
  }

  private String resolve(final FunctionCallNode call, final Set<String> group) {
    if (call.hasPrefix()) {
      return null;
    }
    if (group.contains(call.name())) {
      return call.name();
    }
    final var aliased = aliases.get(call.name());
    return aliased != null && group.contains(aliased) ? aliased : null;
  }

  private void branch(
      final CaseBranchNode branch,
      final ASTNode subject,
      final String caller,
      final Set<String> group,
      final List<ParameterNode> all,
      final List<ParameterNode> params,
      final Map<String, String> components) {
    if (!(branch.pattern() instanceof EnumPatternNode pattern)) {
      walk(branch.result(), caller, group, all, params, components);
      return;
    }

    final var name = name(subject);
    if (name == null) {
      walk(branch.result(), caller, group, all, params, components);
      return;
    }

    String enumType = null;
    for (final var parameter : params) {
      if (parameter.name().equals(name)) {
        enumType = parameter.type().name();
        break;
      }
    }
    if (enumType == null) {
      enumType = components.get(name);
    }

    if (enumType == null || !recursive.containsKey(enumType)) {
      walk(branch.result(), caller, group, all, params, components);
      return;
    }

    final var variant = variant(enumType, pattern.variant());
    if (variant == null || !pattern.hasBindings()) {
      walk(branch.result(), caller, group, all, params, components);
      return;
    }

    final var extended = new HashMap<>(components);
    final var bindings = pattern.bindings();
    final var fields = variant.fields();
    for (int i = 0; i < bindings.size() && i < fields.size(); i++) {
      if (fields.get(i).name().equals(enumType)) {
        extended.put(bindings.get(i), enumType);
      }
    }

    walk(branch.result(), caller, group, all, params, extended);
  }

  private void verify(
      final FunctionCallNode call,
      final String callee,
      final String caller,
      final List<ParameterNode> all,
      final List<ParameterNode> params,
      final Map<String, String> components) {
    final var target = functions.get(callee);
    final var arguments = call.arguments();

    final var targets = new ArrayList<ParameterNode>();
    for (final var parameter : target.parameters()) {
      if (recursive.containsKey(parameter.type().name())) {
        targets.add(parameter);
      }
    }

    if (targets.isEmpty()) {
      return;
    }

    boolean strict = false;
    for (final var parameter : targets) {
      final int position = target.parameters().indexOf(parameter);
      if (position < 0 || position >= arguments.size()) {
        continue;
      }

      final var argument = arguments.get(position);
      final var name = name(argument);

      if (name != null
          && components.containsKey(name)
          && components.get(name).equals(parameter.type().name())) {
        strict = true;
        continue;
      }

      if (name != null && isCallerParam(name, parameter.type().name(), all)) {
        continue;
      }

      final var message =
          "Recursive call to '"
              + call.name()
              + "' does not structurally decrease. Argument for parameter '"
              + parameter.name()
              + "' (type "
              + parameter.type().name()
              + ") must be a pattern-bound sub-component or parameter";
      error(message, call);
    }

    if (!strict) {
      nonStrict.add(caller + "→" + callee);
    }
  }

  private boolean isCallerParam(
      final String name, final String type, final List<ParameterNode> all) {
    for (final var parameter : all) {
      if (parameter.name().equals(name) && parameter.type().name().equals(type)) {
        return true;
      }
    }
    return false;
  }

  private EnumVariantNode variant(final String name, final String label) {
    final var enumeration = recursive.get(name);
    if (enumeration == null) {
      return null;
    }
    for (final var variant : enumeration.variants()) {
      if (variant.name().equals(label)) {
        return variant;
      }
    }
    return null;
  }

  private String name(final ASTNode node) {
    if (node instanceof VariableReferenceNode reference) {
      final var parts = reference.parts();
      if (!reference.hasPrefix() && parts.size() == 1) {
        return parts.get(0);
      }
    }
    return null;
  }

  private void arithmetic(final String name, final FunctionDeclarationNode function) {
    // Collect constants from function body for constant propagation
    scan(function.body());

    // In module context, skip functions that merely delegate to a same-named builtin
    // (e.g., sqrt(x) { return sqrt(x); }) — these are not true self-recursion
    if (module && isBuiltinWrapper(name, function)) return;

    // If a decreases clause is provided, the user has given an explicit termination
    // measure — skip automatic arithmetic decrease checking but still verify base case
    if (function.hasDecreases()) {
      final var calls = new ArrayList<FunctionCallNode>();
      for (final var node : function.body()) {
        self(node, name, calls);
      }
      if (!calls.isEmpty() && !hasBaseCase(function.body(), name)) {
        error(
            "Self-recursive function '"
                + name
                + "' has no base case (missing non-recursive branch in conditional)",
            function);
      }
      return;
    }

    final var calls = new ArrayList<FunctionCallNode>();
    // A single collector accumulates variable→function aliases across
    // parameter defaults, contracts, and body statements so a `var foo =
    // knownFn;` in the body is honoured for later `foo()` calls.
    final var collector = new SelfReferenceCollector(name, calls);
    for (final var parameter : function.parameters()) {
      if (parameter.hasDefault()) {
        collector.walk(parameter.initial());
      }
    }
    if (function.hasRequires()) collector.walk(function.requires());
    if (function.hasEnsures()) collector.walk(function.ensures());
    for (final var node : function.body()) {
      collector.walk(node);
    }
    if (calls.isEmpty()) return;

    final var parameters = function.parameters();

    // Only check functions with at least one int/uint parameter (float not accepted)
    var hasNumeric = false;
    for (final var parameter : parameters) {
      final var type = parameter.type().name();
      if ("int".equals(type) || "uint".equals(type)) {
        hasNumeric = true;
        break;
      }
    }
    if (!hasNumeric) {
      error(
          "Self-recursive function '"
              + name
              + "' has no parameter of a decreasing type (int/uint or recursive enum)."
              + " Consider adding a decreases clause.",
          function);
      return;
    }

    // Compute the measure set: int/uint parameters that strictly decrease on EVERY self-call.
    // Requiring a strict decrease on every call makes the parameter monotone (it never grows on any
    // path), so a measure is a genuine bound on the recursion — not merely "some argument shrank
    // while another grew unbounded".
    final var numeric = new ArrayList<Integer>();
    for (int i = 0; i < parameters.size(); i++) {
      final var type = parameters.get(i).type().name();
      if ("int".equals(type) || "uint".equals(type)) {
        numeric.add(i);
      }
    }
    final var measures = new LinkedHashSet<String>();
    for (final var i : numeric) {
      var decreasesEverywhere = true;
      for (final var call : calls) {
        final var arguments = call.arguments();
        if (i >= arguments.size() || !isDecreasing(arguments.get(i), parameters.get(i).name())) {
          decreasesEverywhere = false;
          break;
        }
      }
      if (decreasesEverywhere) {
        measures.add(parameters.get(i).name());
      }
    }
    if (measures.isEmpty()) {
      final var checked = new ArrayList<String>();
      for (final var i : numeric) {
        checked.add(parameters.get(i).name());
      }
      error(
          "Self-recursive function '"
              + name
              + "' has no parameter that strictly decreases on every recursive call (checked: "
              + checked
              + "). Add a decreases clause if termination relies on another measure.",
          function);
      return;
    }

    // Verify a base case exists AND that it is guarded by a decreasing measure. Without the latter
    // link, a measure could shrink forever while the recursion is gated by an unrelated parameter
    // that grows without bound (e.g. foo(n+1, m-1) guarded by n) — the static-bypass this closes.
    if (!hasBaseCase(function.body(), name)) {
      error(
          "Self-recursive function '"
              + name
              + "' has no base case (missing non-recursive branch in conditional)",
          function);
    } else if (!baseCaseGuardedBy(function.body(), name, measures)) {
      error(
          "Self-recursive function '"
              + name
              + "': the decreasing measure "
              + measures
              + " is not tested by the base case. The terminating condition must check a parameter"
              + " that strictly decreases; otherwise add a decreases clause.",
          function);
    }
  }

  /**
   * True when some base case (non-recursive branch) is guarded by a condition referencing a
   * measure.
   */
  private boolean baseCaseGuardedBy(
      final List<ASTNode> body, final String name, final Set<String> measures) {
    for (final var node : body) {
      if (baseCaseNodeGuardedBy(node, name, measures)) return true;
    }
    return false;
  }

  private boolean baseCaseNodeGuardedBy(
      final ASTNode node, final String name, final Set<String> measures) {
    if (node instanceof ReturnNode r) {
      return r.hasExpression() && baseCaseNodeGuardedBy(r.expression(), name, measures);
    }
    if (node instanceof ExpressionStatementNode e) {
      return baseCaseNodeGuardedBy(e.expression(), name, measures);
    }
    if (node instanceof IfExpressionNode i) {
      if (AstReferences.contains(i.condition(), name)) return false;
      final var left = AstReferences.contains(i.then(), name);
      final var right = i.hasOtherwise() && AstReferences.contains(i.otherwise(), name);
      if (!left && isTriviallyFalse(i.condition())) return false;
      if (!right && i.hasOtherwise() && isTriviallyTrue(i.condition())) return false;
      if (left && right) return false; // no non-recursive branch
      // The non-recursive (base) branch is reached only when the condition takes the value that
      // does
      // NOT select the recursive branch: if `then` recurses, the base is `else` (condition false);
      // if `else` recurses, the base is `then` (condition true). A decreasing measure must provably
      // drive the condition to that value — checked polarity-aware so a `||`/`&&` guard cannot pass
      // on a single measure-mentioning disjunct while another operand grows unbounded.
      final var baseValue = right && !left;
      if (gates(i.condition(), baseValue, measures)) return true;
      return i.hasOtherwise() && baseCaseNodeGuardedBy(i.otherwise(), name, measures);
    }
    if (node instanceof CaseExpressionNode c) {
      if (AstReferences.contains(c.subject(), name)) return false;
      final var subjectGuards = referencesAny(c.subject(), measures);
      for (final var branch : c.branches()) {
        if (branch.hasGuard() && isTriviallyFalse(branch.guard())) continue;
        if (branch.hasGuard() && AstReferences.contains(branch.guard(), name)) continue;
        if (!AstReferences.contains(branch.result(), name)) {
          // A base-case branch — its reachability is gated by matching the subject (and any guard).
          if (subjectGuards || (branch.hasGuard() && referencesAny(branch.guard(), measures))) {
            return true;
          }
        }
      }
      return c.hasFallback() && !AstReferences.contains(c.fallback(), name) && subjectGuards;
    }
    if (node instanceof DoExpressionNode d) {
      for (final var statement : d.statements()) {
        if (baseCaseNodeGuardedBy(statement, name, measures)) return true;
      }
      return baseCaseNodeGuardedBy(d.expression(), name, measures);
    }
    return false;
  }

  private boolean referencesAny(final ASTNode node, final Set<String> names) {
    for (final var n : names) {
      if (mentions(node, n)) return true;
    }
    return false;
  }

  /**
   * True iff a strictly-decreasing measure provably drives {@code node} to {@code baseValue} (the
   * boolean at which the base case is reached). Polarity-aware over boolean connectives so a
   * disjunctive guard cannot pass on one measure-mentioning disjunct while another operand grows
   * without bound: for {@code A || B} to become {@code false}, BOTH operands must be driven false;
   * for {@code A && B} to become {@code false}, EITHER suffices (and the duals when {@code
   * baseValue} is true). An atomic comparison gates iff it tests a decreasing measure.
   * Conservative: anything not provably gating returns false, so the function is rejected (the user
   * adds a {@code decreases} clause) rather than wrongly accepted.
   */
  private boolean gates(final ASTNode node, final boolean baseValue, final Set<String> measures) {
    if (node instanceof UnaryExpressionNode u && "!".equals(u.operator())) {
      return gates(u.operand(), !baseValue, measures);
    }
    if (node instanceof BinaryExpressionNode b) {
      if ("||".equals(b.operator())) {
        return baseValue
            ? gates(b.left(), true, measures) || gates(b.right(), true, measures)
            : gates(b.left(), false, measures) && gates(b.right(), false, measures);
      }
      if ("&&".equals(b.operator())) {
        return baseValue
            ? gates(b.left(), true, measures) && gates(b.right(), true, measures)
            : gates(b.left(), false, measures) || gates(b.right(), false, measures);
      }
    }
    return referencesAny(node, measures);
  }

  /**
   * True if the local variable {@code name} is referenced in {@code node}. Unlike {@link
   * AstReferences#contains} (which finds <em>function calls</em>), this finds variable uses —
   * needed to check that a base-case guard like {@code n <= 0} actually tests the decreasing
   * measure.
   */
  private boolean mentions(final ASTNode node, final String name) {
    if (node instanceof VariableReferenceNode v) {
      return v.prefix() == null && v.parts().size() == 1 && v.parts().get(0).equals(name);
    }
    if (node instanceof BinaryExpressionNode b) {
      return mentions(b.left(), name) || mentions(b.right(), name);
    }
    if (node instanceof UnaryExpressionNode u) {
      return mentions(u.operand(), name);
    }
    if (node instanceof FunctionCallNode c) {
      for (final var argument : c.arguments()) {
        if (mentions(argument, name)) return true;
      }
      return false;
    }
    if (node instanceof IfExpressionNode i) {
      return mentions(i.condition(), name)
          || mentions(i.then(), name)
          || (i.hasOtherwise() && mentions(i.otherwise(), name));
    }
    if (node instanceof FieldAccessNode f) {
      return mentions(f.receiver(), name);
    }
    if (node instanceof IndexAccessNode ix) {
      return mentions(ix.container(), name) || mentions(ix.index(), name);
    }
    return false;
  }

  private boolean hasBaseCase(final List<ASTNode> body, final String name) {
    for (final var node : body) {
      if (hasBaseCaseNode(node, name)) return true;
    }
    return false;
  }

  private boolean hasBaseCaseNode(final ASTNode node, final String name) {
    if (node instanceof ReturnNode r) {
      return r.hasExpression() && hasBaseCaseNode(r.expression(), name);
    }
    if (node instanceof ExpressionStatementNode e) {
      return hasBaseCaseNode(e.expression(), name);
    }
    if (node instanceof IfExpressionNode i) {
      // If the condition itself contains a recursive call, reaching any branch
      // requires evaluating the recursion first — no branch is a valid base case
      if (AstReferences.contains(i.condition(), name)) return false;
      final var left = AstReferences.contains(i.then(), name);
      final var right = i.hasOtherwise() && AstReferences.contains(i.otherwise(), name);
      // C1: reject if the non-recursive branch is guarded by a trivially-false condition
      if (!left && isTriviallyFalse(i.condition())) return false;
      if (!right && i.hasOtherwise() && isTriviallyTrue(i.condition())) return false;
      return !left || !right;
    }
    if (node instanceof CaseExpressionNode c) {
      // If the subject itself contains a recursive call, no branch is a valid base case
      if (AstReferences.contains(c.subject(), name)) return false;
      for (final var branch : c.branches()) {
        // C2: skip branches whose guard is trivially false
        if (branch.hasGuard() && isTriviallyFalse(branch.guard())) continue;
        // Skip branches whose guard contains a recursive call — evaluating the
        // guard requires recursion, so this branch is not a reachable base case
        if (branch.hasGuard() && AstReferences.contains(branch.guard(), name)) continue;
        if (!AstReferences.contains(branch.result(), name)) return true;
      }
      return c.hasFallback() && !AstReferences.contains(c.fallback(), name);
    }
    if (node instanceof DoExpressionNode d) {
      for (final var statement : d.statements()) {
        if (hasBaseCaseNode(statement, name)) return true;
      }
      return hasBaseCaseNode(d.expression(), name);
    }
    return false;
  }

  private boolean isTriviallyFalse(final ASTNode node) {
    final var result = evaluate(node);
    return result != null && !result;
  }

  private boolean isTriviallyTrue(final ASTNode node) {
    final var result = evaluate(node);
    return result != null && result;
  }

  /** Attempt to evaluate a constant boolean expression. Returns null if not determinable. */
  private Boolean evaluate(final ASTNode node) {
    if (node instanceof LiteralNode.BoolLiteral lit) {
      return lit.value();
    }
    if (node instanceof LiteralNode) {
      return null;
    }
    // Resolve const variable references
    if (node instanceof VariableReferenceNode ref && !ref.hasPrefix() && ref.parts().size() == 1) {
      final var initializer = constants.get(ref.parts().get(0));
      if (initializer != null) {
        return evaluate(initializer);
      }
    }
    // !expr
    if (node instanceof UnaryExpressionNode unary && "!".equals(unary.operator())) {
      final var inner = evaluate(unary.operand());
      return inner != null ? !inner : null;
    }
    // binary: &&, ||, ==, !=, <, >, <=, >= on constant operands
    if (node instanceof BinaryExpressionNode binary) {
      final var operator = binary.operator();
      // Boolean operators
      if ("&&".equals(operator) || "||".equals(operator)) {
        final var left = evaluate(binary.left());
        final var right = evaluate(binary.right());
        if (left != null && right != null) {
          return "&&".equals(operator) ? left && right : left || right;
        }
        // Short-circuit: false && x = false, true || x = true
        if (left != null) {
          if ("&&".equals(operator) && !left) return false;
          if ("||".equals(operator) && left) return true;
        }
        return null;
      }
      // Comparison operators on literal numbers/strings
      final var left = literal(binary.left());
      final var right = literal(binary.right());
      if (left != null && right != null) {
        return comparison(operator, left, right);
      }
    }
    return null;
  }

  /** Extract a constant comparable value from a literal or const variable. */
  private Comparable<?> literal(final ASTNode node) {
    if (node instanceof LiteralNode lit) {
      return switch (lit) {
        case LiteralNode.IntLiteral i -> i.value();
        case LiteralNode.UintLiteral u -> u.value();
        case LiteralNode.FloatLiteral f -> f.value();
        case LiteralNode.StringLiteral s -> s.value();
        case LiteralNode.BoolLiteral b -> b.value();
      };
    }
    // Resolve const variable references
    if (node instanceof VariableReferenceNode ref && !ref.hasPrefix() && ref.parts().size() == 1) {
      final var initializer = constants.get(ref.parts().get(0));
      if (initializer != null) {
        return literal(initializer);
      }
    }
    return null;
  }

  private void scan(final List<ASTNode> body) {
    for (final var node : body) {
      if (node instanceof VariableDeclarationNode variable
          && variable.isConstant()
          && variable.hasInitializer()
          && variable.initializer() instanceof LiteralNode) {
        constants.put(variable.name(), variable.initializer());
      }
    }
  }

  @SuppressWarnings("unchecked")
  private Boolean comparison(
      final String operator, final Comparable<?> left, final Comparable<?> right) {
    // Integral fast-path: compare as long to avoid precision loss above 2^53
    if (left instanceof Long a && right instanceof Long b) {
      final long x = a;
      final long y = b;
      return switch (operator) {
        case "==" -> x == y;
        case "!=" -> x != y;
        case "<" -> x < y;
        case ">" -> x > y;
        case "<=" -> x <= y;
        case ">=" -> x >= y;
        default -> null;
      };
    }
    // Mixed or floating-point numeric comparison via double
    if (left instanceof Number && right instanceof Number) {
      final var a = ((Number) left).doubleValue();
      final var b = ((Number) right).doubleValue();
      return switch (operator) {
        case "==" -> a == b;
        case "!=" -> a != b;
        case "<" -> a < b;
        case ">" -> a > b;
        case "<=" -> a <= b;
        case ">=" -> a >= b;
        default -> null;
      };
    }
    if (left.getClass().equals(right.getClass())) {
      @SuppressWarnings("rawtypes")
      final var cmp = ((Comparable) left).compareTo(right);
      return switch (operator) {
        case "==" -> cmp == 0;
        case "!=" -> cmp != 0;
        case "<" -> cmp < 0;
        case ">" -> cmp > 0;
        case "<=" -> cmp <= 0;
        case ">=" -> cmp >= 0;
        default -> null;
      };
    }
    return null;
  }

  private void self(final ASTNode node, final String name, final List<FunctionCallNode> calls) {
    if (node == null) return;
    new SelfReferenceCollector(name, calls).walk(node);
  }

  /**
   * Full-tree visitor that records self-calls and function-value references for the target function
   * {@code name}. Inherits recursion from {@link TraversingASTVisitor}; only the three nodes that
   * matter — variable reference, function call, variable declaration (for aliasing) — need custom
   * behaviour.
   */
  private final class SelfReferenceCollector extends TraversingASTVisitor<Void> {

    private final String name;
    private final List<FunctionCallNode> calls;
    private final Map<String, String> tracked = new HashMap<>();

    SelfReferenceCollector(final String name, final List<FunctionCallNode> calls) {
      this.name = name;
      this.calls = calls;
    }

    void walk(final ASTNode node) {
      node.accept(this);
    }

    @Override
    public Void visitVariableReference(final VariableReferenceNode reference) {
      if (!reference.hasPrefix()
          && reference.parts().size() == 1
          && name.equals(reference.parts().get(0))) {
        // The function is used as a value (HOF arg, stored in a collection,
        // etc.); synthesise a zero-arg call so the termination check still
        // observes the reference and flags the lack of decrease.
        calls.add(
            new FunctionCallNode(reference.line(), reference.column(), null, name, List.of()));
      }
      return null;
    }

    @Override
    public Void visitFunctionCall(final FunctionCallNode call) {
      final var target = call.hasPrefix() ? null : call.name();
      if (name.equals(target) || (target != null && name.equals(tracked.get(target)))) {
        calls.add(call);
      }
      return super.visitFunctionCall(call);
    }

    @Override
    public Void visitVariableDeclaration(final VariableDeclarationNode declaration) {
      if (declaration.hasInitializer()) {
        final var initializer = declaration.initializer();
        if (initializer instanceof VariableReferenceNode reference
            && !reference.hasPrefix()
            && reference.parts().size() == 1
            && name.equals(reference.parts().get(0))) {
          tracked.put(declaration.name(), name);
        }
      }
      return super.visitVariableDeclaration(declaration);
    }
  }

  private boolean isDecreasing(final ASTNode argument, final String parameter) {
    if (argument instanceof BinaryExpressionNode binary) {
      if ("-".equals(binary.operator())) {
        final var left = name(binary.left());
        if (parameter.equals(left) && isStrictlyPositive(binary.right())) {
          return true;
        }
        // Nested subtraction: (param - k1) - k2
        if (isDecreasing(binary.left(), parameter) && isStrictlyPositive(binary.right())) {
          return true;
        }
      }
      if ("/".equals(binary.operator())) {
        final var left = name(binary.left());
        return parameter.equals(left) && isGreaterThanOne(binary.right());
      }
      // Modulo applied to an already-decreasing expression: (expr) % k where k > 0
      // Safe because the inner expression guarantees decrease, and modulo can only
      // make the value smaller or equal. Bare n % k is NOT accepted (no decrease).
      if ("%".equals(binary.operator())) {
        return isDecreasing(binary.left(), parameter) && isStrictlyPositive(binary.right());
      }
      // B2: bitwise right shift — n >> k divides by 2^k
      if (">>".equals(binary.operator())) {
        final var left = name(binary.left());
        return parameter.equals(left) && isStrictlyPositive(binary.right());
      }
    }
    // B3: conditional decrease — both branches must decrease and else must exist
    if (argument instanceof IfExpressionNode conditional) {
      return conditional.hasOtherwise()
          && isDecreasing(conditional.then(), parameter)
          && isDecreasing(conditional.otherwise(), parameter);
    }
    return false;
  }

  private boolean isStrictlyPositive(final ASTNode node) {
    return switch (node) {
      case LiteralNode.IntLiteral i -> i.value() > 0;
      case LiteralNode.UintLiteral u -> u.value() > 0;
      case LiteralNode.FloatLiteral f -> f.value() > 0;
      default -> false;
    };
  }

  private boolean isGreaterThanOne(final ASTNode node) {
    return switch (node) {
      case LiteralNode.IntLiteral i -> i.value() > 1;
      case LiteralNode.UintLiteral u -> u.value() > 1;
      default -> false;
    };
  }

  private boolean isBuiltinWrapper(final String name, final FunctionDeclarationNode function) {
    // A builtin wrapper is: single return statement calling a builtin with the same name
    if (function.body().size() != 1) return false;
    final var only = function.body().get(0);
    ASTNode expression = null;
    if (only instanceof ReturnNode r && r.hasExpression()) {
      expression = r.expression();
    } else if (only instanceof ExpressionStatementNode e) {
      expression = e.expression();
    }
    if (expression instanceof FunctionCallNode call) {
      return call.name().equals(name) && BuiltinRegistry.isBuiltin(name);
    }
    return false;
  }

  private void error(final String message, final ASTNode node) {
    throw new SemanticException(message, node.line(), node.column());
  }
}
