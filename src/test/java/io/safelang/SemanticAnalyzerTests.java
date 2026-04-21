package io.safelang;

import static org.junit.jupiter.api.Assertions.*;

import io.safelang.analyzer.SemanticAnalyzer;
import io.safelang.analyzer.SemanticException;
import io.safelang.parser.SAFEParser;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class SemanticAnalyzerTests {

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
    "functional",
    "binary"
  };

  private static void analyze(final String source) {
    warnings(source);
  }

  private static List<String> warnings(final String source) {
    return warnings(source, false);
  }

  private static List<String> warnings(final String source, final boolean strict) {
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
    analyzer.analyze(program, strict);
    return analyzer.warnings();
  }

  // ======================== Check 1: Type on variable init ========================

  @Test
  void intVariableWithStringInitializer() {
    assertThrows(
        SemanticException.class,
        () ->
            analyze(
                """
                program test;
                int x = "hello";
                """));
  }

  @Test
  void stringVariableWithIntInitializer() {
    assertThrows(
        SemanticException.class,
        () ->
            analyze(
                """
                program test;
                string x = 42;
                """));
  }

  @Test
  void booleanVariableWithIntInitializer() {
    assertThrows(
        SemanticException.class,
        () ->
            analyze(
                """
                program test;
                boolean x = 42;
                """));
  }

  @Test
  void validIntVariable() {
    assertDoesNotThrow(
        () ->
            analyze(
                """
                program test;
                int x = 42;
                """));
  }

  @Test
  void validStringVariable() {
    assertDoesNotThrow(
        () ->
            analyze(
                """
                program test;
                string x = "hello";
                """));
  }

  // ======================== Check 2: Type on reassignment ========================

  @Test
  void reassignIntWithString() {
    assertThrows(
        SemanticException.class,
        () ->
            analyze(
                """
                program test;
                int x = 0;
                x = "hello";
                """));
  }

  @Test
  void reassignStringWithInt() {
    assertThrows(
        SemanticException.class,
        () ->
            analyze(
                """
                program test;
                string x = "hello";
                x = 42;
                """));
  }

  @Test
  void validReassignment() {
    assertDoesNotThrow(
        () ->
            analyze(
                """
                program test;
                int x = 0;
                x = 42;
                """));
  }

  // ======================== Check 3: Const variable reassignment ========================

  @Test
  void constVariableReassignment() {
    assertThrows(
        SemanticException.class,
        () ->
            analyze(
                """
                program test;
                const int x = 5;
                x = 10;
                """));
  }

  @Test
  void mutableVariableReassignment() {
    assertDoesNotThrow(
        () ->
            analyze(
                """
                program test;
                int x = 5;
                x = 10;
                """));
  }

  // ======================== Check 4: Const parameter reassignment ========================

  @Test
  void constParameterReassignment() {
    assertThrows(
        SemanticException.class,
        () ->
            analyze(
                """
                program test;
                void f(const int n) {
                    n = 1;
                }
                """));
  }

  @Test
  void mutableParameterReassignment() {
    assertDoesNotThrow(
        () ->
            analyze(
                """
                program test;
                void f(int n) {
                    n = 1;
                }
                """));
  }

  // ======================== Check 5: Const field reassignment ========================

  @Test
  void constFieldReassignment() {
    assertThrows(
        SemanticException.class,
        () ->
            analyze(
                """
                program test;
                type Point {
                    const int x;
                    int y;
                }
                Point p = Point { x: 3, y: 4 };
                p.x = 5;
                """));
  }

  @Test
  void mutableFieldReassignment() {
    assertDoesNotThrow(
        () ->
            analyze(
                """
                program test;
                type Point {
                    int x;
                    int y;
                }
                Point p = Point { x: 3, y: 4 };
                p.y = 5;
                """));
  }

  // ======================== Check 6: Const index assignment ========================

  @Test
  void constListIndexAssignment() {
    assertThrows(
        SemanticException.class,
        () ->
            analyze(
                """
                program test;
                const list<int> x = [1, 2, 3];
                x[0] = 9;
                """));
  }

  @Test
  void mutableListIndexAssignment() {
    assertDoesNotThrow(
        () ->
            analyze(
                """
                program test;
                list<int> x = [1, 2, 3];
                x[0] = 9;
                """));
  }

  // ======================== Check 7: Return type mismatch ========================

  @Test
  void returnTypeMismatch() {
    assertThrows(
        SemanticException.class,
        () ->
            analyze(
                """
                program test;
                int f() {
                    return "hello";
                }
                """));
  }

  @Test
  void validReturn() {
    assertDoesNotThrow(
        () ->
            analyze(
                """
                program test;
                int f() {
                    return 42;
                }
                """));
  }

  // ======================== Check 8: Missing return value ========================

  @Test
  void missingReturnValue() {
    assertThrows(
        SemanticException.class,
        () ->
            analyze(
                """
                program test;
                int f() {
                    return;
                }
                """));
  }

  @Test
  void voidReturnAllowed() {
    assertDoesNotThrow(
        () ->
            analyze(
                """
                program test;
                void f() {
                    return;
                }
                """));
  }

  // ======================== Check 9: Function argument count ========================

  @Test
  void tooManyArguments() {
    assertThrows(
        SemanticException.class,
        () ->
            analyze(
                """
                program test;
                void f(int x) {
                    return;
                }
                f(1, 2);
                """));
  }

  @Test
  void tooFewArguments() {
    assertThrows(
        SemanticException.class,
        () ->
            analyze(
                """
                program test;
                void f(int x, int y) {
                    return;
                }
                f(1);
                """));
  }

  @Test
  void correctArgumentCount() {
    assertDoesNotThrow(
        () ->
            analyze(
                """
                program test;
                void f(int x) {
                    return;
                }
                f(1);
                """));
  }

  // ======================== Check 10: Function argument types ========================

  @Test
  void argumentTypeMismatch() {
    assertThrows(
        SemanticException.class,
        () ->
            analyze(
                """
                program test;
                void f(int x) {
                    return;
                }
                f("hello");
                """));
  }

  @Test
  void validArgumentTypes() {
    assertDoesNotThrow(
        () ->
            analyze(
                """
                program test;
                void f(int x) {
                    return;
                }
                f(42);
                """));
  }

  // ======================== Check 11: Object field types ========================

  @Test
  void objectFieldTypeMismatch() {
    assertThrows(
        SemanticException.class,
        () ->
            analyze(
                """
                program test;
                type Point {
                    int x;
                    int y;
                }
                Point p = Point { x: "hi", y: 4 };
                """));
  }

  @Test
  void validObjectFieldTypes() {
    assertDoesNotThrow(
        () ->
            analyze(
                """
                program test;
                type Point {
                    int x;
                    int y;
                }
                Point p = Point { x: 3, y: 4 };
                """));
  }

  // ======================== Check 12: Missing required fields ========================

  @Test
  void missingRequiredField() {
    assertThrows(
        SemanticException.class,
        () ->
            analyze(
                """
                program test;
                type Point {
                    int x;
                    int y;
                }
                Point p = Point { x: 3 };
                """));
  }

  @Test
  void allFieldsProvided() {
    assertDoesNotThrow(
        () ->
            analyze(
                """
                program test;
                type Point {
                    int x;
                    int y;
                }
                Point p = Point { x: 3, y: 4 };
                """));
  }

  // ======================== Check 13: Undefined function ========================

  @Test
  void undefinedFunction() {
    assertThrows(
        SemanticException.class,
        () ->
            analyze(
                """
                program test;
                foo();
                """));
  }

  @Test
  void moduleFunctionAllowed() {
    assertDoesNotThrow(
        () ->
            analyze(
                """
                program test;
                import io;
                io:println("hello");
                """));
  }

  // ======================== Check 14: Undefined type ========================

  @Test
  void undefinedType() {
    assertThrows(
        SemanticException.class,
        () ->
            analyze(
                """
                program test;
                Foo f = Foo { };
                """));
  }

  @Test
  void definedType() {
    assertDoesNotThrow(
        () ->
            analyze(
                """
                program test;
                type Foo {
                    int x;
                }
                Foo f = Foo { x: 1 };
                """));
  }

  // ======================== Check 15: Enum variant arg count ========================

  @Test
  void enumVariantTooManyArgs() {
    assertThrows(
        SemanticException.class,
        () ->
            analyze(
                """
                program test;
                enum Option {
                    Some(int),
                    None
                }
                Option x = Some(1, 2);
                """));
  }

  @Test
  void enumVariantTooFewArgs() {
    assertThrows(
        SemanticException.class,
        () ->
            analyze(
                """
                program test;
                enum Option {
                    Some(int),
                    None
                }
                Option x = Some();
                """));
  }

  @Test
  void enumVariantCorrectArgCount() {
    assertDoesNotThrow(
        () ->
            analyze(
                """
                program test;
                enum Option {
                    Some(int),
                    None
                }
                Option x = Some(42);
                """));
  }

  // ======================== Check 16: Enum variant arg types ========================

  @Test
  void enumVariantArgTypeMismatch() {
    assertThrows(
        SemanticException.class,
        () ->
            analyze(
                """
                program test;
                enum Option {
                    Some(int),
                    None
                }
                Option x = Some("hello");
                """));
  }

  @Test
  void enumVariantCorrectArgType() {
    assertDoesNotThrow(
        () ->
            analyze(
                """
                program test;
                enum Option {
                    Some(int),
                    None
                }
                Option x = Some(42);
                """));
  }

  // ======================== Positive: existing programs should still work ========================

  @Test
  void emptyProgram() {
    assertDoesNotThrow(
        () ->
            analyze(
                """
                program test;
                """));
  }

  @Test
  void variableWithExpression() {
    assertDoesNotThrow(
        () ->
            analyze(
                """
                program test;
                int x = 1 + 2;
                """));
  }

  @Test
  void forLoop() {
    assertDoesNotThrow(
        () ->
            analyze(
                """
                program test;
                import std;
                import io;
                for i in std:range(10) {
                    io:println(i);
                }
                """));
  }

  @Test
  void ifExpression() {
    assertDoesNotThrow(
        () ->
            analyze(
                """
                program test;
                int x = if (true) then 1 else 2;
                """));
  }

  @Test
  void builtinFunctions() {
    assertDoesNotThrow(
        () ->
            analyze(
                """
                program test;
                import io;
                import std;
                io:println("hello");
                io:print("world");
                int x = std:len("hello");
                string s = std:str(42);
                """));
  }

  // ======================== New: Undefined variable detection ========================

  @Test
  void undefinedVariableDetected() {
    assertThrows(
        SemanticException.class,
        () ->
            analyze(
                """
                program test;
                import io;
                io:println(x);
                """));
  }

  @Test
  void definedVariableNoError() {
    assertDoesNotThrow(
        () ->
            analyze(
                """
                program test;
                import io;
                int x = 5;
                io:println(x);
                """));
  }

  @Test
  void loopVariableAccessible() {
    assertDoesNotThrow(
        () ->
            analyze(
                """
                program test;
                import std;
                import io;
                for i in std:range(5) {
                    io:println(i);
                }
                """));
  }

  @Test
  void functionParameterAccessible() {
    assertDoesNotThrow(
        () ->
            analyze(
                """
                program test;
                int f(int n) {
                    return n;
                }
                """));
  }

  // ======================== New: Extra fields in object creation ========================

  @Test
  void extraFieldInObject() {
    assertThrows(
        SemanticException.class,
        () ->
            analyze(
                """
                program test;
                type P { int x; }
                P p = P { x: 1, y: 2 };
                """));
  }

  // ======================== New: Operator type checking ========================

  @Test
  void logicalOnNonBoolean() {
    assertThrows(
        SemanticException.class,
        () ->
            analyze(
                """
                program test;
                int x = 5;
                int y = 3;
                boolean z = x && y;
                """));
  }

  @Test
  void subtractOnBoolean() {
    assertThrows(
        SemanticException.class,
        () ->
            analyze(
                """
                program test;
                boolean a = true;
                boolean b = false;
                int c = a - b;
                """));
  }

  @Test
  void validArithmetic() {
    assertDoesNotThrow(
        () ->
            analyze(
                """
                program test;
                int x = 5 + 3;
                int y = x * 2;
                """));
  }

  @Test
  void stringConcatenation() {
    assertDoesNotThrow(
        () ->
            analyze(
                """
                program test;
                string s = "hello" + " world";
                """));
  }

  // ======================== New: Module function argument count ========================

  @Test
  void printlnNoArgs() {
    assertThrows(
        SemanticException.class,
        () ->
            analyze(
                """
                program test;
                import io;
                io:println();
                """));
  }

  @Test
  void printlnTwoArgs() {
    assertThrows(
        SemanticException.class,
        () ->
            analyze(
                """
                program test;
                import io;
                io:println(1, 2);
                """));
  }

  @Test
  void rangeThreeArgs() {
    assertThrows(
        SemanticException.class,
        () ->
            analyze(
                """
                program test;
                import std;
                import io;
                for i in std:range(1, 2, 3) {
                    io:println(i);
                }
                """));
  }

  @Test
  void rangeOneArgValid() {
    assertDoesNotThrow(
        () ->
            analyze(
                """
                program test;
                import std;
                import io;
                for i in std:range(10) {
                    io:println(i);
                }
                """));
  }

  @Test
  void rangeTwoArgsValid() {
    assertDoesNotThrow(
        () ->
            analyze(
                """
                program test;
                import std;
                import io;
                for i in std:span(1, 10) {
                    io:println(i);
                }
                """));
  }

  // ======================== New: Duplicate variant names ========================

  @Test
  void duplicateVariantNames() {
    assertThrows(
        SemanticException.class,
        () ->
            analyze(
                """
                program test;
                enum A { X }
                enum B { X }
                """));
  }

  @Test
  void distinctVariantNames() {
    assertDoesNotThrow(
        () ->
            analyze(
                """
                program test;
                enum A { X }
                enum B { Y }
                """));
  }

  // ======================== Generic type parameters ========================

  @Test
  void genericIdentity() {
    assertDoesNotThrow(
        () ->
            analyze(
                """
                program test;
                ?T identity(?T x) {
                    return x;
                }
                int n = identity(42);
                """));
  }

  @Test
  void genericFirstFromList() {
    assertDoesNotThrow(
        () ->
            analyze(
                """
                program test;
                ?T first(list<?T> items) {
                    return items[0];
                }
                int n = first([1, 2, 3]);
                """));
  }

  @Test
  void genericTypeMismatchSameVariable() {
    assertThrows(
        SemanticException.class,
        () ->
            analyze(
                """
                program test;
                ?T pick(?T a, ?T b) {
                    return a;
                }
                pick(42, "hello");
                """));
  }

  @Test
  void genericDifferentVariables() {
    assertDoesNotThrow(
        () ->
            analyze(
                """
                program test;
                ?T first(?T a, ?U b) {
                    return a;
                }
                first(42, "hello");
                """));
  }

  @Test
  void genericReturnTypeMismatch() {
    assertThrows(
        SemanticException.class,
        () ->
            analyze(
                """
                program test;
                ?T identity(?T x) {
                    return x;
                }
                string s = identity(42);
                """));
  }

  @Test
  void genericMapKeys() {
    assertDoesNotThrow(
        () ->
            analyze(
                """
                program test;
                import collections;
                list<string> k = collections:keys({"a": 1, "b": 2});
                """));
  }

  // ======================== Union type parameters ========================

  @Test
  void unionTypeVariableDeclaration() {
    assertDoesNotThrow(
        () ->
            analyze(
                """
                program test;
                int|float x = 42;
                """));
  }

  @Test
  void unionTypeVariableWithFloat() {
    assertDoesNotThrow(
        () ->
            analyze(
                """
                program test;
                int|float x = 3.14;
                """));
  }

  @Test
  void unionTypeMismatch() {
    assertThrows(
        SemanticException.class,
        () ->
            analyze(
                """
                program test;
                int|float x = "hello";
                """));
  }

  @Test
  void unionTypeFunctionParameter() {
    assertDoesNotThrow(
        () ->
            analyze(
                """
                program test;
                int|float abs(int|float x) {
                    return x;
                }
                int|float r = abs(42);
                """));
  }

  @Test
  void unionTypeFunctionParameterFloat() {
    assertDoesNotThrow(
        () ->
            analyze(
                """
                program test;
                int|float abs(int|float x) {
                    return x;
                }
                int|float r = abs(3.14);
                """));
  }

  @Test
  void unionTypeFunctionParameterMismatch() {
    assertThrows(
        SemanticException.class,
        () ->
            analyze(
                """
                program test;
                int|float abs(int|float x) {
                    return x;
                }
                abs("hello");
                """));
  }

  // ======================== Union type narrowing ========================

  @Test
  void unionReturnNarrowsToInt() {
    assertDoesNotThrow(
        () ->
            analyze(
                """
                program test;
                int|float max(int|float a, int|float b) {
                    return if (a > b) then a else b;
                }
                int x = max(2, 1);
                """));
  }

  @Test
  void unionReturnNarrowsToFloat() {
    assertDoesNotThrow(
        () ->
            analyze(
                """
                program test;
                int|float max(int|float a, int|float b) {
                    return if (a > b) then a else b;
                }
                float x = max(2.0, 1.0);
                """));
  }

  @Test
  void unionReturnWidensToFloat() {
    assertDoesNotThrow(
        () ->
            analyze(
                """
                program test;
                int|float max(int|float a, int|float b) {
                    return if (a > b) then a else b;
                }
                float x = max(2.0, 1);
                """));
  }

  @Test
  void unionReturnNarrowingRejectsIntForFloatResult() {
    assertThrows(
        SemanticException.class,
        () ->
            analyze(
                """
                program test;
                int|float max(int|float a, int|float b) {
                    return if (a > b) then a else b;
                }
                int x = max(2.0, 1);
                """));
  }

  @Test
  void unionReturnNarrowsSingleParam() {
    assertDoesNotThrow(
        () ->
            analyze(
                """
                program test;
                int|float abs(int|float x) {
                    return if (x > 0) then x else 0 - x;
                }
                int r = abs(5);
                """));
  }

  @Test
  void unionReturnNarrowsSingleParamFloat() {
    assertDoesNotThrow(
        () ->
            analyze(
                """
                program test;
                int|float abs(int|float x) {
                    return if (x > 0) then x else 0 - x;
                }
                float r = abs(3.14);
                """));
  }

  @Test
  void unionReturnModuleNarrowsToInt() {
    assertDoesNotThrow(
        () ->
            analyze(
                """
                program test;
                import math;
                int x = math:max(2, 1);
                """));
  }

  @Test
  void unionReturnModuleWidensToFloat() {
    assertDoesNotThrow(
        () ->
            analyze(
                """
                program test;
                import math;
                float x = math:max(2.0, 1);
                """));
  }

  @Test
  void unionReturnModuleRejectsIntForFloatResult() {
    assertThrows(
        SemanticException.class,
        () ->
            analyze(
                """
                program test;
                import math;
                int x = math:max(2.0, 1);
                """));
  }

  // ======================== Generic-wrapped union narrowing ========================

  @Test
  void genericUnionNarrowsToInt() {
    assertDoesNotThrow(
        () ->
            analyze(
                """
                program test;
                int|float sum(list<int|float> items) {
                    int|float total = 0;
                    for x in items {
                        total = total + x;
                    }
                    return total;
                }
                int x = sum([1, 2, 3]);
                """));
  }

  @Test
  void genericUnionNarrowsToFloat() {
    assertDoesNotThrow(
        () ->
            analyze(
                """
                program test;
                int|float sum(list<int|float> items) {
                    int|float total = 0;
                    for x in items {
                        total = total + x;
                    }
                    return total;
                }
                float x = sum([1.0, 2.0, 3.0]);
                """));
  }

  @Test
  void genericUnionRejectsIntForFloatList() {
    assertThrows(
        SemanticException.class,
        () ->
            analyze(
                """
                program test;
                int|float sum(list<int|float> items) {
                    int|float total = 0;
                    for x in items {
                        total = total + x;
                    }
                    return total;
                }
                int x = sum([1.0, 2.0, 3.0]);
                """));
  }

  @Test
  void genericUnionModuleSumNarrowsToInt() {
    assertDoesNotThrow(
        () ->
            analyze(
                """
                program test;
                import math;
                int x = math:sum([1, 2, 3]);
                """));
  }

  @Test
  void genericUnionModuleSumNarrowsToFloat() {
    assertDoesNotThrow(
        () ->
            analyze(
                """
                program test;
                import math;
                float x = math:sum([1.0, 2.0, 3.0]);
                """));
  }

  @Test
  void genericUnionModuleSumRejectsIntForFloatList() {
    assertThrows(
        SemanticException.class,
        () ->
            analyze(
                """
                program test;
                import math;
                int x = math:sum([1.0, 2.0, 3.0]);
                """));
  }

  // ======================== Generic Option enum ========================

  @Test
  void genericOptionSomeInt() {
    assertDoesNotThrow(
        () ->
            analyze(
                """
                program test;
                import option;
                Option o = Some(42);
                """));
  }

  @Test
  void genericOptionSomeString() {
    assertDoesNotThrow(
        () ->
            analyze(
                """
                program test;
                import option;
                Option o = Some("hello");
                """));
  }

  @Test
  void genericOptionNone() {
    assertDoesNotThrow(
        () ->
            analyze(
                """
                program test;
                import option;
                Option o = None;
                """));
  }

  // ======================== Generic stack ========================

  @Test
  void genericStackPushString() {
    assertDoesNotThrow(
        () ->
            analyze(
                """
                program test;
                import stack;
                Stack s = stack:push(stack:create(), "hello");
                """));
  }

  // ======================== Generic queue ========================

  @Test
  void genericQueueEnqueueString() {
    assertDoesNotThrow(
        () ->
            analyze(
                """
                program test;
                import queue;
                Queue q = queue:enqueue(queue:create(), "hello");
                """));
  }

  // ======================== Tree ========================

  @Test
  void treeInsertTypeChecks() {
    assertDoesNotThrow(
        () ->
            analyze(
                """
                program test;
                import tree;
                Tree t = tree:insert(Empty, 5);
                """));
  }

  // ======================== Builtin type validation via registry ========================

  @Test
  void sqrtWithStringArgument() {
    assertThrows(
        SemanticException.class,
        () ->
            analyze(
                """
                program test;
                import math;
                float x = math:sqrt("hello");
                """));
  }

  @Test
  void sqrtWithFloatArgument() {
    assertDoesNotThrow(
        () ->
            analyze(
                """
                program test;
                import math;
                float x = math:sqrt(4.0);
                """));
  }

  @Test
  void floorWithStringArgument() {
    assertThrows(
        SemanticException.class,
        () ->
            analyze(
                """
                program test;
                import math;
                int x = math:floor("hello");
                """));
  }

  @Test
  void substringWithWrongTypes() {
    assertThrows(
        SemanticException.class,
        () ->
            analyze(
                """
                program test;
                import strings;
                string s = strings:substring(42, 0, 1);
                """));
  }

  @Test
  void substringWithCorrectTypes() {
    assertDoesNotThrow(
        () ->
            analyze(
                """
                program test;
                import strings;
                string s = strings:substring("hello", 0, 3);
                """));
  }

  @Test
  void splitWithIntDelimiter() {
    assertThrows(
        SemanticException.class,
        () ->
            analyze(
                """
                program test;
                import strings;
                list<string> parts = strings:split("a,b", 42);
                """));
  }

  @Test
  void printlnAcceptsAnyType() {
    assertDoesNotThrow(
        () ->
            analyze(
                """
                program test;
                import io;
                io:println(42);
                io:println(3.14);
                io:println("hello");
                io:println(true);
                """));
  }

  @Test
  void strAcceptsAnyType() {
    assertDoesNotThrow(
        () ->
            analyze(
                """
                program test;
                import std;
                string a = std:str(42);
                string b = std:str(3.14);
                string c = std:str(true);
                """));
  }

  @Test
  void appendTypeMismatch() {
    assertThrows(
        SemanticException.class,
        () ->
            analyze(
                """
                program test;
                import collections;
                list<int> items = [1, 2, 3];
                list<int> result = collections:append(items, "wrong");
                """));
  }

  @Test
  void appendTypeMatch() {
    assertDoesNotThrow(
        () ->
            analyze(
                """
                program test;
                import collections;
                list<int> items = [1, 2, 3];
                list<int> result = collections:append(items, 4);
                """));
  }

  @Test
  void absWithIntArgument() {
    assertDoesNotThrow(
        () ->
            analyze(
                """
                program test;
                import math;
                int x = math:abs(-5);
                """));
  }

  @Test
  void absWithStringArgument() {
    assertThrows(
        SemanticException.class,
        () ->
            analyze(
                """
                program test;
                import math;
                int x = math:abs("hello");
                """));
  }

  @Test
  void lenAcceptsString() {
    assertDoesNotThrow(
        () ->
            analyze(
                """
                program test;
                import std;
                int n = std:len("hello");
                """));
  }

  @Test
  void lenAcceptsList() {
    assertDoesNotThrow(
        () ->
            analyze(
                """
                program test;
                import std;
                int n = std:len([1, 2, 3]);
                """));
  }

  @Test
  void fileExistsWithCorrectType() {
    assertDoesNotThrow(
        () ->
            analyze(
                """
                program test;
                import file;
                boolean exists = file:exists("test.txt");
                """));
  }

  // ======================== Finding #1: in operator typing ========================

  @Test
  void inOperatorResolvesToBoolean() {
    assertDoesNotThrow(
        () ->
            analyze(
                """
                program test;
                boolean found = 1 in [1, 2, 3];
                """));
  }

  @Test
  void inOperatorOnNonCollectionFails() {
    assertThrows(
        SemanticException.class,
        () ->
            analyze(
                """
                program test;
                boolean found = 1 in 2;
                """));
  }

  @Test
  void inOperatorStringContains() {
    assertDoesNotThrow(
        () ->
            analyze(
                """
                program test;
                boolean found = "a" in "abc";
                """));
  }

  @Test
  void inOperatorMapContains() {
    assertDoesNotThrow(
        () ->
            analyze(
                """
                program test;
                boolean found = "a" in {"a": 1};
                """));
  }

  @Test
  void inOperatorTypeMismatchOnList() {
    assertThrows(
        SemanticException.class,
        () ->
            analyze(
                """
                program test;
                boolean found = "hello" in [1, 2, 3];
                """));
  }

  @Test
  void inOperatorIntOnStringFails() {
    assertThrows(
        SemanticException.class,
        () ->
            analyze(
                """
                program test;
                boolean found = 1 in "hello";
                """));
  }

  // ======================== Finding #3: dotted assignment validation ========================

  @Test
  void dottedAssignmentUndefinedVariable() {
    assertThrows(
        SemanticException.class,
        () ->
            analyze(
                """
                program test;
                type Point { int x; int y; }
                nonexistent.x = 1;
                """));
  }

  @Test
  void dottedAssignmentUnknownField() {
    assertThrows(
        SemanticException.class,
        () ->
            analyze(
                """
                program test;
                type Point { int x; int y; }
                Point p = Point { x: 0, y: 0 };
                p.z = 1;
                """));
  }

  // ======================== Finding #4: return-path analysis ========================

  @Test
  void earlyReturnWithTrailingCode() {
    assertDoesNotThrow(
        () ->
            analyze(
                """
                program test;
                int identity(int x) {
                    return x;
                    int dead = 0;
                }
                """));
  }

  // ======================== Finding #9: qualified variable prefix ========================

  @Test
  void qualifiedVariableUnknownModule() {
    assertThrows(
        SemanticException.class,
        () ->
            analyze(
                """
                program test;
                int x = nonexistent.VAR;
                """));
  }

  // ======================== Type alias cycle detection ========================

  @Test
  void cyclicTypeAliasDetected() {
    assertThrows(
        SemanticException.class,
        () ->
            analyze(
                """
                program test;
                type A = B;
                type B = A;
                A x = 1;
                """));
  }

  @Test
  void selfReferentialTypeAliasDetected() {
    assertThrows(
        SemanticException.class,
        () ->
            analyze(
                """
                program test;
                type A = A;
                A x = 1;
                """));
  }

  @Test
  void threeWayCyclicAliasDetected() {
    assertThrows(
        SemanticException.class,
        () ->
            analyze(
                """
                program test;
                type A = B;
                type B = C;
                type C = A;
                A x = 1;
                """));
  }

  // ======================== Unused variable warnings ========================

  @Test
  void unusedVariableWarning() {
    final var result =
        warnings(
            """
                program test;
                int unused(int x) {
                    int y = 5;
                    return x;
                }
                """);
    assertTrue(result.stream().anyMatch(w -> w.contains("Unused variable 'y'")));
  }

  @Test
  void usedVariableNoWarning() {
    final var result =
        warnings(
            """
                program test;
                int identity(int x) {
                    int y = x + 1;
                    return y;
                }
                """);
    assertFalse(result.stream().anyMatch(w -> w.contains("Unused variable")));
  }

  @Test
  void underscorePrefixSuppressesWarning() {
    final var result =
        warnings(
            """
                program test;
                int f(int x) {
                    int _ignored = 5;
                    return x;
                }
                """);
    assertFalse(result.stream().anyMatch(w -> w.contains("_ignored")));
  }

  @Test
  void unusedParameterWarning() {
    final var result =
        warnings(
            """
                program test;
                int f(int x, int y) {
                    return x;
                }
                """);
    assertTrue(result.stream().anyMatch(w -> w.contains("Unused variable 'y'")));
  }

  // ======================== Dead code after return ========================

  @Test
  void deadCodeAfterReturn() {
    final var result =
        warnings(
            """
                program test;
                int f(int x) {
                    return x;
                    int y = 5;
                }
                """);
    assertTrue(result.stream().anyMatch(w -> w.contains("Unreachable code after return")));
  }

  @Test
  void noDeadCodeWarningWithoutReturn() {
    final var result =
        warnings(
            """
                program test;
                int f(int x) {
                    int y = x + 1;
                    return y;
                }
                """);
    assertFalse(result.stream().anyMatch(w -> w.contains("Unreachable")));
  }

  // ======================== Unused import warnings ========================

  @Test
  void unusedImportWarning() {
    final var result =
        warnings(
            """
                program test;
                import math;
                int f(int x) {
                    return x + 1;
                }
                """);
    assertTrue(result.stream().anyMatch(w -> w.contains("Unused import: math")));
  }

  @Test
  void usedImportNoWarning() {
    final var result =
        warnings(
            """
                program test;
                import math;
                import std;
                import io;
                io:println(std:str(math:sqrt(4.0)));
                """);
    assertFalse(result.stream().anyMatch(w -> w.contains("Unused import")));
  }

  // ======================== HOF impure lambda in strict mode ========================

  @Test
  void impureLambdaArgumentInStrictMode() {
    assertThrows(
        SemanticException.class,
        () ->
            warnings(
                """
                program test;
                import io;
                import functional;
                functional:each(functional:map(1..3, fn(x) -> x), fn(x) -> io:println(io:str(x)));
                """,
                true));
  }

  @Test
  void pureLambdaArgumentInStrictMode() {
    // Should NOT throw — lambda is pure
    warnings(
        """
                program test;
                import functional;
                import std;
                functional:map(1..3, fn(x) -> x + 1);
                """,
        true);
  }

  // ======================== Resource leak warnings ========================

  @Test
  void resourceLeakWarningOnOpenWithoutClose() {
    final var result =
        warnings(
            """
                program test;
                import file;
                void leak() {
                    file:open("test.txt", "r");
                    return;
                }
                """);
    assertTrue(
        result.stream().anyMatch(w -> w.contains("resource leak")),
        "Expected resource leak warning, got: " + result);
  }

  @Test
  void noResourceLeakWarningWhenBalanced() {
    final var result =
        warnings(
            """
                program test;
                import binary;
                void balanced() {
                    binary:open("test.bin", "r");
                    binary:close(0);
                    return;
                }
                """);
    assertFalse(result.stream().anyMatch(w -> w.contains("resource leak")));
  }

  @Test
  void resourceLeakWarningForBinaryOpen() {
    final var result =
        warnings(
            """
                program test;
                import binary;
                void leak() {
                    binary:open("test.bin", "r");
                    return;
                }
                """);
    assertTrue(
        result.stream().anyMatch(w -> w.contains("resource leak")),
        "Expected resource leak warning, got: " + result);
  }
}
