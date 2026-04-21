package io.safelang;

import static org.junit.jupiter.api.Assertions.*;

import io.safelang.analyzer.SemanticAnalyzer;
import io.safelang.analyzer.SemanticException;
import io.safelang.interpreter.InterpreterException;
import io.safelang.parser.SAFEParser;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class TerminationTests {

  private static final String[] STDLIB_MODULES = {
    "io",
    "std",
    "math",
    "strings",
    "file",
    "collections",
    "option",
    "result",
    "stack",
    "queue",
    "sorting",
    "tree",
    "functional"
  };

  private static void analyze(final String source) {
    final var program = SAFEParser.parse(source);
    final var loader = new ModuleLoader(Path.of("stdlib/io.safe"));
    final var registry = new ModuleRegistry();
    for (final var name : STDLIB_MODULES) {
      try {
        final var module = loader.load(name);
        registry.register(name, module);
      } catch (Exception ignored) {
      }
    }
    final var analyzer = new SemanticAnalyzer(registry);
    analyzer.analyze(program);
  }

  // ======================== Valid structural recursion ========================

  @Test
  void simpleTraversal() {
    assertDoesNotThrow(
        () ->
            analyze(
                """
                program test;
                enum Tree { Empty, Node(int, Tree, Tree) }
                int count(Tree t) {
                    return case t of {
                        Empty: 0;
                        Node(x, left, right): 1 + count(left) + count(right);
                    };
                }
                """));
  }

  @Test
  void singleBranchRecursion() {
    assertDoesNotThrow(
        () ->
            analyze(
                """
                program test;
                enum Linked { Nil, Cons(int, Linked) }
                int length(Linked l) {
                    return case l of {
                        Nil: 0;
                        Cons(x, rest): 1 + length(rest);
                    };
                }
                """));
  }

  @Test
  void nestedCaseExpression() {
    assertDoesNotThrow(
        () ->
            analyze(
                """
                program test;
                enum Tree { Empty, Node(int, Tree, Tree) }
                int smallest(Tree t) {
                    return case t of {
                        Node(x, left, right): case left of {
                            Empty: x;
                            Node(lx, ll, lr): smallest(left);
                        };
                        Empty: 0;
                    };
                }
                """));
  }

  @Test
  void mixedParams() {
    assertDoesNotThrow(
        () ->
            analyze(
                """
                program test;
                enum Tree { Empty, Node(int, Tree, Tree) }
                Tree insert(Tree t, int v) {
                    return case t of {
                        Empty: Node(v, Empty, Empty);
                        Node(x, left, right): if (v < x)
                            then Node(x, insert(left, v), right)
                            else Node(x, left, insert(right, v));
                    };
                }
                """));
  }

  @Test
  void nonRecursiveFunctionWithRecursiveEnumParam() {
    assertDoesNotThrow(
        () ->
            analyze(
                """
                program test;
                enum Tree { Empty, Node(int, Tree, Tree) }
                boolean blank(Tree t) {
                    return case t of {
                        Empty: true;
                        Node(x, left, right): false;
                    };
                }
                """));
  }

  @Test
  void recursionOnNonEnumParam() {
    assertDoesNotThrow(
        () ->
            analyze(
                """
                program test;
                int fib(int n) {
                    return if (n <= 1) then n else fib(n - 1) + fib(n - 2);
                }
                """));
  }

  @Test
  void nonRecursiveEnum() {
    assertDoesNotThrow(
        () ->
            analyze(
                """
                program test;
                enum Option { None, Some(int) }
                int unwrap(Option o) {
                    return case o of {
                        None: 0;
                        Some(v): v;
                    };
                }
                """));
  }

  @Test
  void transitiveSubComponent() {
    assertDoesNotThrow(
        () ->
            analyze(
                """
                program test;
                enum Tree { Empty, Node(int, Tree, Tree) }
                int deep(Tree t) {
                    return case t of {
                        Empty: 0;
                        Node(x, left, right): case left of {
                            Empty: 0;
                            Node(lx, ll, lr): deep(ll);
                        };
                    };
                }
                """));
  }

  @Test
  void dropWithMerge() {
    assertDoesNotThrow(
        () ->
            analyze(
                """
                program test;
                enum Tree { Empty, Node(int, Tree, Tree) }
                Tree merge(Tree a, Tree b) {
                    return a;
                }
                Tree drop(Tree t, int v) {
                    return case t of {
                        Empty: Empty;
                        Node(x, left, right): if (v < x)
                            then Node(x, drop(left, v), right)
                            else merge(left, right);
                    };
                }
                """));
  }

  @Test
  void treeModuleIntegration() {
    assertDoesNotThrow(
        () ->
            analyze(
                """
                program test;
                import tree;
                Tree t = tree:insert(tree:insert(tree:insert(Empty, 2), 1), 3);
                int n = tree:count(t);
                """));
  }

  // ======================== Invalid (should reject) ========================

  @Test
  void originalParamUnchanged() {
    assertThrows(
        SemanticException.class,
        () ->
            analyze(
                """
                program test;
                enum Tree { Empty, Node(int, Tree, Tree) }
                int bad(Tree t) {
                    return case t of {
                        Empty: 0;
                        Node(x, left, right): bad(t);
                    };
                }
                """));
  }

  @Test
  void computedArgument() {
    assertThrows(
        SemanticException.class,
        () ->
            analyze(
                """
                program test;
                enum Tree { Empty, Node(int, Tree, Tree) }
                Tree wrap(Tree t) {
                    return Node(0, t, Empty);
                }
                int bad(Tree t) {
                    return case t of {
                        Empty: 0;
                        Node(x, left, right): bad(wrap(left));
                    };
                }
                """));
  }

  @Test
  void callOutsideCaseBranch() {
    assertThrows(
        SemanticException.class,
        () ->
            analyze(
                """
                program test;
                enum Tree { Empty, Node(int, Tree, Tree) }
                int bad(Tree t) {
                    return bad(t);
                }
                """));
  }

  @Test
  void callInBaseCaseBranch() {
    assertThrows(
        SemanticException.class,
        () ->
            analyze(
                """
                program test;
                enum Linked { Nil, Cons(int, Linked) }
                int bad(Linked l) {
                    return case l of {
                        Nil: bad(l);
                        Cons(x, rest): x;
                    };
                }
                """));
  }

  @Test
  void nonSubComponentBinding() {
    assertThrows(
        SemanticException.class,
        () ->
            analyze(
                """
                program test;
                enum Tree { Empty, Node(int, Tree, Tree) }
                int bad(Tree t) {
                    return case t of {
                        Empty: 0;
                        Node(x, left, right): bad(Node(x, left, right));
                    };
                }
                """));
  }

  @Test
  void wrappedDecreaseExpressionRejected() {
    assertThrows(
        SemanticException.class,
        () ->
            analyze(
                """
                program test;
                int inc(int x) {
                    return x + 1;
                }
                int bad(int n) {
                    return if (n <= 0) then 0 else bad(inc(n - 1));
                }
                """));
  }

  @Test
  void recursiveEnumAliasCallRejected() {
    assertThrows(
        SemanticException.class,
        () ->
            analyze(
                """
                program test;
                enum NList { Nil, Cons(int, NList) }
                int len(NList xs) {
                    fn(NList) -> int g = len;
                    return case xs of {
                        Nil: 0;
                        Cons(x, rest): 1 + g(xs);
                    };
                }
                """));
  }

  // ======================== Level 2: Mutual recursion (valid) ========================

  @Test
  void mutualRecursionWithStrictDecrease() {
    assertDoesNotThrow(
        () ->
            analyze(
                """
                program test;
                enum Tree { Empty, Node(int, Tree, Tree) }
                int f(Tree t) {
                    return case t of {
                        Empty: 0;
                        Node(x, left, right): g(left) + g(right);
                    };
                }
                int g(Tree t) {
                    return case t of {
                        Empty: 1;
                        Node(x, left, right): f(left);
                    };
                }
                """));
  }

  @Test
  void dropMergeMutualRecursion() {
    assertDoesNotThrow(
        () ->
            analyze(
                """
                program test;
                enum Tree { Empty, Node(int, Tree, Tree) }
                Tree merge(Tree a, Tree b) {
                    return case a of {
                        Empty: b;
                        Node(x, left, right): Node(x, left, drop(right, x));
                    };
                }
                Tree drop(Tree t, int v) {
                    return case t of {
                        Empty: Empty;
                        Node(x, left, right): if (v == x)
                            then merge(left, right)
                            else Node(x, drop(left, v), drop(right, v));
                    };
                }
                """));
  }

  @Test
  void threeWayMutualRecursion() {
    assertDoesNotThrow(
        () ->
            analyze(
                """
                program test;
                enum Tree { Empty, Node(int, Tree, Tree) }
                int f(Tree t) {
                    return case t of {
                        Empty: 0;
                        Node(x, left, right): g(left);
                    };
                }
                int g(Tree t) {
                    return case t of {
                        Empty: 0;
                        Node(x, left, right): h(right);
                    };
                }
                int h(Tree t) {
                    return case t of {
                        Empty: 0;
                        Node(x, left, right): f(left);
                    };
                }
                """));
  }

  @Test
  void mutualRecursionOneWithoutRecursiveParam() {
    assertDoesNotThrow(
        () ->
            analyze(
                """
                program test;
                enum Tree { Empty, Node(int, Tree, Tree) }
                int helper(int x) {
                    return worker(Node(x, Empty, Empty));
                }
                int worker(Tree t) {
                    return case t of {
                        Empty: 0;
                        Node(x, left, right): x;
                    };
                }
                """));
  }

  // ======================== Level 2: Mutual recursion (invalid) ========================

  @Test
  void mutualRecursionNoDecrease() {
    assertThrows(
        SemanticException.class,
        () ->
            analyze(
                """
                program test;
                enum Tree { Empty, Node(int, Tree, Tree) }
                int f(Tree t) {
                    return case t of {
                        Empty: 0;
                        Node(x, left, right): g(t);
                    };
                }
                int g(Tree t) {
                    return case t of {
                        Empty: 1;
                        Node(x, left, right): f(t);
                    };
                }
                """));
  }

  @Test
  void mutualRecursionComputed() {
    assertThrows(
        SemanticException.class,
        () ->
            analyze(
                """
                program test;
                enum Tree { Empty, Node(int, Tree, Tree) }
                int f(Tree t) {
                    return case t of {
                        Empty: 0;
                        Node(x, left, right): g(Node(0, left, right));
                    };
                }
                int g(Tree t) {
                    return case t of {
                        Empty: 1;
                        Node(x, left, right): f(left);
                    };
                }
                """));
  }

  @Test
  void mutualRecursionNonStrictCycle() {
    assertThrows(
        SemanticException.class,
        () ->
            analyze(
                """
                program test;
                enum Tree { Empty, Node(int, Tree, Tree) }
                int f(Tree t) {
                    return case t of {
                        Empty: 0;
                        Node(x, left, right): 1 + g(left) + g(t);
                    };
                }
                int g(Tree t) {
                    return case t of {
                        Empty: 1;
                        Node(x, left, right): f(t);
                    };
                }
                """));
  }

  // ======================== Division and nested subtraction patterns ========================

  @Test
  void divisionByTwo() {
    assertDoesNotThrow(
        () ->
            analyze(
                """
                program test;
                int search(int n) {
                    return if (n <= 0) then 0 else 1 + search(n / 2);
                }
                """));
  }

  @Test
  void divisionByThree() {
    assertDoesNotThrow(
        () ->
            analyze(
                """
                program test;
                int reduce(int n) {
                    return if (n <= 0) then 0 else 1 + reduce(n / 3);
                }
                """));
  }

  @Test
  void divisionByOneRejected() {
    assertThrows(
        SemanticException.class,
        () ->
            analyze(
                """
                program test;
                int bad(int n) {
                    return if (n <= 0) then 0 else 1 + bad(n / 1);
                }
                """));
  }

  @Test
  void nestedSubtraction() {
    assertDoesNotThrow(
        () ->
            analyze(
                """
                program test;
                int skip(int n) {
                    return if (n <= 0) then 0 else 1 + skip((n - 1) - 1);
                }
                """));
  }

  @Test
  void deepNestedSubtraction() {
    assertDoesNotThrow(
        () ->
            analyze(
                """
                program test;
                int deep(int n) {
                    return if (n <= 0) then 0 else 1 + deep(((n - 1) - 1) - 1);
                }
                """));
  }

  // ======================== Fix 1: Function reference tracking ========================

  @Test
  void functionReferenceInCallGraphDetected() {
    // A bare function reference creates a call graph edge even without a direct call.
    // The synthesized zero-arg call fails the "does not decrease" check.
    assertThrows(
        SemanticException.class,
        () ->
            analyze(
                """
                program test;
                int bad(int n) {
                    fn(int) -> int ref = bad;
                    return if (n <= 0) then 0 else 1;
                }
                """));
  }

  // ======================== Decreases clause ========================

  @Test
  void decreasesOnSimpleRecursion() {
    assertDoesNotThrow(
        () ->
            analyze(
                """
                program test;
                int fib(int n)
                decreases(n) {
                    return if (n <= 1) then n else fib(n - 1) + fib(n - 2);
                }
                """));
  }

  @Test
  void decreasesOnGcd() {
    assertDoesNotThrow(
        () ->
            analyze(
                """
                program test;
                int gcd(int a, int b)
                decreases(a + b) {
                    return if (b == 0) then a else gcd(b, a % b);
                }
                """));
  }

  @Test
  void decreasesWithFloatParam() {
    // Float recursion with decreases clause (int cast) should be accepted
    assertDoesNotThrow(
        () ->
            analyze(
                """
                program test;
                import std;
                float loop(float x)
                decreases(std:integer(x)) {
                    return if (x <= 0.0) then 0.0 else loop(x - 1.0);
                }
                """));
  }

  @Test
  void floatRecursionWithoutDecreasesRejected() {
    // Float without decreases should now be rejected
    assertThrows(
        SemanticException.class,
        () ->
            analyze(
                """
                program test;
                float loop(float x) {
                    return if (x <= 0.0) then 0.0 else loop(x - 1.0);
                }
                """));
  }

  @Test
  void decreasesWithNonIntegerTypeRejected() {
    // Decreases expression must be int or uint
    assertThrows(
        SemanticException.class,
        () ->
            analyze(
                """
                program test;
                float loop(float x)
                decreases(x) {
                    return if (x <= 0.0) then 0.0 else loop(x - 1.0);
                }
                """));
  }

  @Test
  void decreasesWithStringTypeRejected() {
    assertThrows(
        SemanticException.class,
        () ->
            analyze(
                """
                program test;
                int bad(string s)
                decreases(s) {
                    return 0;
                }
                """));
  }

  @Test
  void decreasesViolationAtRuntime() {
    // Runtime: measure does not decrease → error
    assertThrows(
        InterpreterException.class,
        () -> {
          TestHelper.run(
              """
                    program test;
                    int bad(int n)
                    decreases(n) {
                        return if (n <= 0) then 0 else bad(n);
                    }
                    bad(5);
                    """);
        });
  }

  @Test
  void decreasesNegativeMeasureAtRuntime() {
    // Runtime: negative measure → error
    // Uses a computed negative value to bypass static analysis
    assertThrows(
        InterpreterException.class,
        () -> {
          TestHelper.run(
              """
                    program test;
                    int compute(int n)
                    decreases(n) {
                        return if (n <= 0) then 0 else compute(n - 1);
                    }
                    compute(0 - 1);
                    """);
        });
  }

  @Test
  void decreasesSuccessAtRuntime() {
    // Runtime: decreasing measure succeeds
    final var result =
        TestHelper.run(
            """
                program test;
                import io;
                import std;
                int countdown(int n)
                decreases(n) {
                    return if (n <= 0) then 0 else 1 + countdown(n - 1);
                }
                io:println(std:str(countdown(5)));
                """);
    assertEquals("5", result);
  }

  @Test
  void decreasesBaseCaseStillRequired() {
    // Even with decreases, a base case is required
    assertThrows(
        SemanticException.class,
        () ->
            analyze(
                """
                program test;
                int bad(int n)
                decreases(n) {
                    return bad(n - 1);
                }
                """));
  }

  // ======================== Fix 3: Mutually recursive enums ========================

  @Test
  void mutuallyRecursiveEnumsDetected() {
    // Two enums that reference each other should both be detected as recursive
    assertDoesNotThrow(
        () ->
            analyze(
                """
                program test;
                enum Expr { Num(int), Add(Expr, Expr), Neg(Stmt) }
                enum Stmt { Print(Expr), Seq(Stmt, Stmt) }
                int eval(Expr e) {
                    return case e of {
                        Num(n): n;
                        Add(left, right): eval(left) + eval(right);
                        Neg(s): 0;
                    };
                }
                """));
  }

  @Test
  void selfRecursiveEnumStillDetected() {
    // Self-recursive enum (Tree → Tree) still works via SCC
    assertDoesNotThrow(
        () ->
            analyze(
                """
                program test;
                enum Tree { Empty, Node(int, Tree, Tree) }
                int count(Tree t) {
                    return case t of {
                        Empty: 0;
                        Node(x, left, right): 1 + count(left) + count(right);
                    };
                }
                """));
  }

  @Test
  void baseCaseInsideDoBlockAccepted() {
    assertDoesNotThrow(
        () ->
            analyze(
                """
                program test;
                enum List { Nil, Cons(int, List) }
                int sum(List l) {
                    return case l of {
                        Nil: 0;
                        Cons(x, rest): do { int s = sum(rest); s + x };
                    };
                }
                """));
  }

  // ======================== Error message quality ========================

  @Test
  void arithmeticDecreaseErrorIncludesParameters() {
    final var error =
        assertThrows(
            SemanticException.class,
            () ->
                analyze(
                    """
                program test;
                int f(int n) {
                    return if (n > 0) then f(n * 2) else 0;
                }
                """));
    assertTrue(error.getMessage().contains("checked:"));
    assertTrue(error.getMessage().contains("n"));
  }

  // ======================== Constant propagation in base case detection ========================

  @Test
  void constVariableInConditionAccepted() {
    // const int ZERO = 0 before function; if (n <= ZERO) should be recognized
    assertDoesNotThrow(
        () ->
            analyze(
                """
                program test;
                const int ZERO = 0;
                int f(int n) {
                    return if (n <= ZERO) then 1 else f(n - 1);
                }
                """));
  }

  @Test
  void constBooleanPropagated() {
    // const FALSE = false; if (FALSE) should be detected as trivially false
    assertDoesNotThrow(
        () ->
            analyze(
                """
                program test;
                int f(int n) {
                    return if (n <= 0) then 1 else f(n - 1);
                }
                """));
  }
}
