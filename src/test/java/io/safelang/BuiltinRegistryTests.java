package io.safelang;

import static org.junit.jupiter.api.Assertions.*;

import io.safelang.runtime.BuiltinRegistry;
import org.junit.jupiter.api.Test;

class BuiltinRegistryTests {

  // ======================== ID Lookups ========================

  @Test
  void allBuiltinIdsResolvable() {
    assertEquals(0, BuiltinRegistry.id("print"));
    assertEquals(1, BuiltinRegistry.id("println"));
    assertEquals(2, BuiltinRegistry.id("len"));
    assertEquals(3, BuiltinRegistry.id("range"));
    assertEquals(4, BuiltinRegistry.id("str"));
    assertEquals(5, BuiltinRegistry.id("int"));
    assertEquals(6, BuiltinRegistry.id("float"));
    assertEquals(7, BuiltinRegistry.id("append"));
    assertEquals(8, BuiltinRegistry.id("keys"));
    assertEquals(9, BuiltinRegistry.id("values"));
    assertEquals(10, BuiltinRegistry.id("contains"));
    assertEquals(11, BuiltinRegistry.id("size"));
    assertEquals(12, BuiltinRegistry.id("sqrt"));
    assertEquals(13, BuiltinRegistry.id("pow"));
    assertEquals(14, BuiltinRegistry.id("abs"));
    assertEquals(15, BuiltinRegistry.id("min"));
    assertEquals(16, BuiltinRegistry.id("max"));
    assertEquals(17, BuiltinRegistry.id("floor"));
    assertEquals(18, BuiltinRegistry.id("ceil"));
    assertEquals(19, BuiltinRegistry.id("round"));
    assertEquals(20, BuiltinRegistry.id("log"));
    assertEquals(21, BuiltinRegistry.id("sin"));
    assertEquals(22, BuiltinRegistry.id("cos"));
    assertEquals(23, BuiltinRegistry.id("substring"));
    assertEquals(24, BuiltinRegistry.id("indexOf"));
    assertEquals(25, BuiltinRegistry.id("charAt"));
    assertEquals(26, BuiltinRegistry.id("split"));
    assertEquals(27, BuiltinRegistry.id("trim"));
    assertEquals(28, BuiltinRegistry.id("upper"));
    assertEquals(29, BuiltinRegistry.id("lower"));
    assertEquals(30, BuiltinRegistry.id("replace"));
    assertEquals(31, BuiltinRegistry.id("starts"));
    assertEquals(32, BuiltinRegistry.id("ends"));
    assertEquals(33, BuiltinRegistry.id("join"));
    assertEquals(34, BuiltinRegistry.id("chars"));
    assertEquals(35, BuiltinRegistry.id("read"));
    assertEquals(36, BuiltinRegistry.id("write"));
    assertEquals(37, BuiltinRegistry.id("appendfile"));
    assertEquals(38, BuiltinRegistry.id("exists"));
    assertEquals(39, BuiltinRegistry.id("delete"));
    assertEquals(40, BuiltinRegistry.id("lines"));
    assertEquals(41, BuiltinRegistry.id("input"));
    assertEquals(42, BuiltinRegistry.id("exit"));
    assertEquals(43, BuiltinRegistry.id("args"));
    assertEquals(44, BuiltinRegistry.id("time"));
    assertEquals(45, BuiltinRegistry.id("typeof"));
    assertEquals(46, BuiltinRegistry.id("remove"));
    assertEquals(47, BuiltinRegistry.id("slice"));
    assertEquals(48, BuiltinRegistry.id("reverse"));
    assertEquals(49, BuiltinRegistry.id("sort"));
    assertEquals(50, BuiltinRegistry.id("fileopen"));
    assertEquals(51, BuiltinRegistry.id("fileclose"));
    assertEquals(52, BuiltinRegistry.id("fileread"));
    assertEquals(53, BuiltinRegistry.id("filewrite"));
    assertEquals(54, BuiltinRegistry.id("filereadlines"));
    assertEquals(55, BuiltinRegistry.id("filevalid"));
    assertEquals(56, BuiltinRegistry.id("fileload"));
    assertEquals(57, BuiltinRegistry.id("filesave"));
    assertEquals(58, BuiltinRegistry.id("add"));
    assertEquals(59, BuiltinRegistry.id("union"));
    assertEquals(60, BuiltinRegistry.id("intersect"));
    assertEquals(61, BuiltinRegistry.id("difference"));
    assertEquals(62, BuiltinRegistry.id("tan"));
    assertEquals(63, BuiltinRegistry.id("asin"));
    assertEquals(64, BuiltinRegistry.id("acos"));
    assertEquals(65, BuiltinRegistry.id("atan"));
    assertEquals(66, BuiltinRegistry.id("atan2"));
    assertEquals(67, BuiltinRegistry.id("exp"));
    assertEquals(68, BuiltinRegistry.id("log10"));
    assertEquals(69, BuiltinRegistry.id("rand"));
    assertEquals(70, BuiltinRegistry.id("randint"));
    assertEquals(71, BuiltinRegistry.id("seed"));
    assertEquals(72, BuiltinRegistry.id("matches"));
    assertEquals(73, BuiltinRegistry.id("findall"));
    assertEquals(74, BuiltinRegistry.id("replaceall"));
    assertEquals(75, BuiltinRegistry.id("listdir"));
    assertEquals(76, BuiltinRegistry.id("mkdir"));
    assertEquals(77, BuiltinRegistry.id("rmdir"));
    assertEquals(78, BuiltinRegistry.id("isdir"));
  }

  // ======================== Aliases ========================

  @Test
  void integerAliasResolves() {
    assertEquals(5, BuiltinRegistry.id("integer"));
  }

  @Test
  void decimalAliasResolves() {
    assertEquals(6, BuiltinRegistry.id("decimal"));
  }

  // ======================== Name Lookups ========================

  @Test
  void allNamesMatch() {
    for (int i = 0; i <= 101; i++) {
      final var name = BuiltinRegistry.name(i);
      assertNotNull(name, "No name for id " + i);
      assertFalse(name.startsWith("unknown"), "Unknown name for id " + i);
    }
  }

  // ======================== Arity Lookups ========================

  @Test
  void allAritiesPositive() {
    for (int i = 0; i <= 101; i++) {
      final var arity = BuiltinRegistry.arity(i);
      assertTrue(arity >= 0, "Negative arity for " + BuiltinRegistry.name(i));
    }
  }

  @Test
  void rangeIsVariadic() {
    final var id = BuiltinRegistry.id("range");
    assertEquals(2, BuiltinRegistry.arity(id));
    assertEquals(1, BuiltinRegistry.minimum(id));
  }

  // ======================== Module Lookups ========================

  @Test
  void allModulesMatch() {
    final String[] names = {
      "print",
      "println",
      "input",
      "sqrt",
      "pow",
      "abs",
      "min",
      "max",
      "floor",
      "ceil",
      "round",
      "log",
      "sin",
      "cos",
      "substring",
      "indexOf",
      "charAt",
      "split",
      "trim",
      "upper",
      "lower",
      "replace",
      "starts",
      "ends",
      "join",
      "chars",
      "read",
      "write",
      "appendfile",
      "exists",
      "delete",
      "lines",
      "fileopen",
      "fileclose",
      "fileread",
      "filewrite",
      "filereadlines",
      "filevalid",
      "fileload",
      "filesave",
      "append",
      "keys",
      "values",
      "contains",
      "size",
      "remove",
      "slice",
      "reverse",
      "sort",
      "str",
      "len",
      "range",
      "typeof",
      "time",
      "args",
      "exit",
      "int",
      "float",
      "add",
      "union",
      "intersect",
      "difference",
      "tan",
      "asin",
      "acos",
      "atan",
      "atan2",
      "exp",
      "log10",
      "rand",
      "randint",
      "seed",
      "matches",
      "findall",
      "replaceall",
      "listdir",
      "mkdir",
      "rmdir",
      "isdir"
    };
    for (final var name : names) {
      final var module = BuiltinRegistry.module(name);
      assertNotNull(module, "No module for " + name);
      assertNotEquals("unknown", module, "Unknown module for " + name);
    }
  }

  @Test
  void aliasModulesMatch() {
    assertEquals("std", BuiltinRegistry.module("integer"));
    assertEquals("std", BuiltinRegistry.module("decimal"));
  }

  // ======================== Unknown Lookups ========================

  @Test
  void unknownNameReturnsNegativeId() {
    assertEquals(-1, BuiltinRegistry.id("nonexistent"));
  }

  @Test
  void unknownIdReturnsDefaultName() {
    assertEquals("unknown_builtin_999", BuiltinRegistry.name(999));
  }

  @Test
  void isBuiltinTrueForKnown() {
    assertTrue(BuiltinRegistry.isBuiltin("print"));
    assertTrue(BuiltinRegistry.isBuiltin("sqrt"));
    assertTrue(BuiltinRegistry.isBuiltin("integer"));
  }

  @Test
  void isBuiltinFalseForUnknown() {
    assertFalse(BuiltinRegistry.isBuiltin("nonexistent"));
  }

  // ======================== Signatures ========================

  @Test
  void printSignatureIsGeneric() {
    final var signature = BuiltinRegistry.signature("print");
    assertNotNull(signature);
    assertEquals(1, signature.parameters().size());
    assertTrue(signature.parameters().get(0).type().isVariable());
    assertEquals("void", signature.returns().name());
  }

  @Test
  void sqrtSignatureIsFloat() {
    final var signature = BuiltinRegistry.signature("sqrt");
    assertNotNull(signature);
    assertEquals(1, signature.parameters().size());
    assertEquals("float", signature.parameters().get(0).type().name());
    assertEquals("float", signature.returns().name());
  }

  @Test
  void appendSignatureIsGenericList() {
    final var signature = BuiltinRegistry.signature("append");
    assertNotNull(signature);
    assertEquals(2, signature.parameters().size());
    final var list = signature.parameters().get(0).type();
    assertEquals("list", list.name());
    assertTrue(list.isParameterized());
    assertTrue(list.parameters().get(0).isVariable());
  }

  @Test
  void absSignatureIsUnion() {
    final var signature = BuiltinRegistry.signature("abs");
    assertNotNull(signature);
    assertEquals(1, signature.parameters().size());
    assertTrue(signature.parameters().get(0).type().isUnion());
    assertTrue(signature.returns().isUnion());
  }

  @Test
  void totalBuiltinCount() {
    assertEquals(102, BuiltinRegistry.all().size());
  }
}
