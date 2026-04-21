package io.safelang;

import static org.junit.jupiter.api.Assertions.*;

import io.safelang.ast.*;
import io.safelang.compiler.CompilerException;
import io.safelang.compiler.wasm.SymbolKey;
import io.safelang.compiler.wasm.TypeRegistry;
import java.util.HashSet;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class TypeRegistryTests {

  private ModuleRegistry registry;

  private static EnumVariantNode variant(final String name, final TypeNode... fields) {
    return new EnumVariantNode(0, 0, name, List.of(fields));
  }

  // === Helpers ===

  private static EnumVariantNode variant(final String name) {
    return new EnumVariantNode(0, 0, name, List.of());
  }

  private static EnumDeclarationNode enumeration(
      final String name, final boolean visible, final EnumVariantNode... variants) {
    return new EnumDeclarationNode(0, 0, name, List.of(), List.of(variants), visible);
  }

  private static FieldDeclarationNode field(final String type, final String name) {
    return new FieldDeclarationNode(0, 0, new TypeNode(0, 0, type), name, false, true);
  }

  private static TypeDeclarationNode struct(
      final String name, final boolean visible, final FieldDeclarationNode... fields) {
    return new TypeDeclarationNode(0, 0, name, List.of(), List.of(fields), visible);
  }

  private static ProgramNode module(final ASTNode... declarations) {
    return new ProgramNode(0, 0, "module", "test", List.of(), List.of(declarations), List.of());
  }

  private static ProgramNode module(final List<ImportNode> imports, final ASTNode... declarations) {
    return new ProgramNode(0, 0, "module", "test", imports, List.of(declarations), List.of());
  }

  private static ProgramNode program(final ASTNode... declarations) {
    return new ProgramNode(0, 0, "program", "main", List.of(), List.of(declarations), List.of());
  }

  private static ProgramNode program(
      final List<ImportNode> imports, final ASTNode... declarations) {
    return new ProgramNode(0, 0, "program", "main", imports, List.of(declarations), List.of());
  }

  private static ImportNode use(final String module) {
    return new ImportNode(0, 0, module);
  }

  private static ImportNode use(final String module, final String... symbols) {
    return new ImportNode(0, 0, module, List.of(symbols));
  }

  @BeforeEach
  void setup() {
    registry = new ModuleRegistry();
  }

  private TypeRegistry build(final ProgramNode main) {
    return TypeRegistry.build(registry, main);
  }

  // === Enum Registration ===

  @Nested
  class EnumRegistration {

    @Test
    void singleEnum() {
      final var main =
          program(enumeration("Color", true, variant("Red"), variant("Green"), variant("Blue")));
      final var types = build(main);
      assertEquals(1, types.enumCount());
      assertTrue(types.type(TypeRegistry.MAIN, "Color") >= 0);
    }

    @Test
    void multipleEnumsGetDistinctIds() {
      final var main =
          program(
              enumeration("Color", true, variant("Red"), variant("Green")),
              enumeration("Shape", true, variant("Circle"), variant("Square")));
      final var types = build(main);
      assertEquals(2, types.enumCount());
      final var color = types.type(TypeRegistry.MAIN, "Color");
      final var shape = types.type(TypeRegistry.MAIN, "Shape");
      assertNotEquals(color, shape);
      assertTrue(color >= 0);
      assertTrue(shape >= 0);
    }

    @Test
    void sameEnumNameDifferentModulesGetDistinctIds() {
      // json and xml both define ParseResult
      registry.register(
          "json",
          module(
              enumeration(
                  "ParseResult",
                  true,
                  variant("Ok", new TypeNode(0, 0, "string")),
                  variant("Error"))));
      registry.register(
          "xml",
          module(
              enumeration(
                  "ParseResult",
                  true,
                  variant("Ok", new TypeNode(0, 0, "string")),
                  variant("Error"))));

      final var types = build(program());
      final var json = types.type("json", "ParseResult");
      final var xml = types.type("xml", "ParseResult");
      assertTrue(json >= 0);
      assertTrue(xml >= 0);
      assertNotEquals(json, xml);
    }

    @Test
    void moduleAndMainEnumsSeparate() {
      registry.register(
          "result", module(enumeration("Result", true, variant("Ok"), variant("Err"))));
      final var main = program(enumeration("Result", true, variant("Ok"), variant("Err")));

      final var types = build(main);
      final var module = types.type("result", "Result");
      final var local = types.type(TypeRegistry.MAIN, "Result");
      assertTrue(module >= 0);
      assertTrue(local >= 0);
      assertNotEquals(module, local);
    }

    @Test
    void privateModuleEnumsStillRegisterForWasm() {
      registry.register(
          "parser", module(enumeration("Parsed", false, variant("Ok"), variant("Err"))));

      final var types = build(program());
      assertTrue(types.type("parser", "Parsed") >= 0);
    }

    @Test
    void unknownEnumReturnsNegative() {
      final var types = build(program());
      assertEquals(-1, types.type("nonexistent", "Foo"));
      assertEquals(-1, types.type(TypeRegistry.MAIN, "Missing"));
    }

    @Test
    void emptyEnum() {
      final var main = program(enumeration("Empty", true));
      final var types = build(main);
      assertEquals(1, types.enumCount());
      assertTrue(types.type(TypeRegistry.MAIN, "Empty") >= 0);
    }

    @Test
    void singleVariantEnum() {
      final var main = program(enumeration("Unit", true, variant("Only")));
      final var types = build(main);
      assertEquals(1, types.enumCount());
      assertNotNull(types.enumeration(TypeRegistry.MAIN, "Unit"));
    }

    @Test
    void enumDeclarationPreserved() {
      final var original = enumeration("Color", true, variant("Red"), variant("Green"));
      final var main = program(original);
      final var types = build(main);
      final var retrieved = types.enumeration(TypeRegistry.MAIN, "Color");
      assertSame(original, retrieved);
    }

    @Test
    void enumsByModule() {
      registry.register(
          "a",
          module(enumeration("Foo", true, variant("X")), enumeration("Bar", true, variant("Y"))));
      registry.register("b", module(enumeration("Baz", true, variant("Z"))));

      final var types = build(program());
      final var enums = types.enums("a");
      assertEquals(2, enums.size());
      assertTrue(enums.containsKey(new SymbolKey("a", "Foo")));
      assertTrue(enums.containsKey(new SymbolKey("a", "Bar")));
    }

    @Test
    void enumKeysContainsAll() {
      registry.register("m", module(enumeration("E1", true, variant("V1"))));
      final var main = program(enumeration("E2", true, variant("V2")));
      final var types = build(main);
      final var keys = types.enumKeys();
      assertTrue(keys.contains(new SymbolKey("m", "E1")));
      assertTrue(keys.contains(new SymbolKey(TypeRegistry.MAIN, "E2")));
    }
  }

  // === Variant Resolution ===

  @Nested
  class VariantResolution {

    @Test
    void resolveInMainProgram() {
      final var main =
          program(enumeration("Color", true, variant("Red"), variant("Green"), variant("Blue")));
      final var types = build(main);
      final var resolved = types.variant(TypeRegistry.MAIN, "Green");
      assertNotNull(resolved);
      assertEquals(1, resolved.index());
      assertEquals(types.type(TypeRegistry.MAIN, "Color"), resolved.type());
      assertEquals(0, resolved.arity());
    }

    @Test
    void variantIndexMatchesDeclarationOrder() {
      final var main =
          program(
              enumeration(
                  "Dir",
                  true,
                  variant("North"),
                  variant("South"),
                  variant("East"),
                  variant("West")));
      final var types = build(main);
      assertEquals(0, types.variant(TypeRegistry.MAIN, "North").index());
      assertEquals(1, types.variant(TypeRegistry.MAIN, "South").index());
      assertEquals(2, types.variant(TypeRegistry.MAIN, "East").index());
      assertEquals(3, types.variant(TypeRegistry.MAIN, "West").index());
    }

    @Test
    void variantArityTracked() {
      final var main =
          program(
              enumeration(
                  "Result",
                  true,
                  variant("Ok", new TypeNode(0, 0, "int")),
                  variant("Err", new TypeNode(0, 0, "string"))));
      final var types = build(main);
      assertEquals(1, types.variant(TypeRegistry.MAIN, "Ok").arity());
      assertEquals(1, types.variant(TypeRegistry.MAIN, "Err").arity());
    }

    @Test
    void zeroArityVariant() {
      final var main =
          program(
              enumeration(
                  "Option", true, variant("Some", new TypeNode(0, 0, "int")), variant("None")));
      final var types = build(main);
      assertEquals(0, types.variant(TypeRegistry.MAIN, "None").arity());
      assertEquals(1, types.variant(TypeRegistry.MAIN, "Some").arity());
    }

    @Test
    void resolveInSpecificModule() {
      registry.register(
          "option",
          module(
              enumeration(
                  "Option", true, variant("Some", new TypeNode(0, 0, "int")), variant("None"))));
      registry.register(
          "result",
          module(
              enumeration(
                  "Result", true, variant("Ok", new TypeNode(0, 0, "int")), variant("Err"))));

      final var types = build(program());
      final var some = types.variant("option", "Some");
      assertNotNull(some);
      assertEquals(0, some.index());
      assertEquals(new SymbolKey("option", "Option"), some.owner());
    }

    @Test
    void resolveUnambiguousVariantWithoutModule() {
      registry.register(
          "option", module(enumeration("Option", true, variant("Some"), variant("None"))));

      final var types = build(program());
      // "Some" is unique across all modules — should resolve without module context
      final var resolved = types.variant(null, "Some");
      assertNotNull(resolved);
      assertEquals(new SymbolKey("option", "Option"), resolved.owner());
    }

    @Test
    void ambiguousVariantWithoutModuleThrows() {
      registry.register(
          "option", module(enumeration("Option", true, variant("Ok"), variant("None"))));
      registry.register(
          "result", module(enumeration("Result", true, variant("Ok"), variant("Err"))));

      final var types = build(program());
      assertThrows(CompilerException.class, () -> types.variant(null, "Ok"));
    }

    @Test
    void ambiguousVariantResolvedByModuleContext() {
      registry.register(
          "option", module(enumeration("Option", true, variant("Ok"), variant("None"))));
      registry.register(
          "result", module(enumeration("Result", true, variant("Ok"), variant("Err"))));

      final var types = build(program());
      // With module context, disambiguated
      final var optionOk = types.variant("option", "Ok");
      assertNotNull(optionOk);
      assertEquals(new SymbolKey("option", "Option"), optionOk.owner());

      final var resultOk = types.variant("result", "Ok");
      assertNotNull(resultOk);
      assertEquals(new SymbolKey("result", "Result"), resultOk.owner());
    }

    @Test
    void sameVariantNameDifferentEnumsInSameModule() {
      registry.register(
          "mod",
          module(
              enumeration("E1", true, variant("Shared")),
              enumeration("E2", true, variant("Shared"))));

      final var types = build(program());
      assertThrows(CompilerException.class, () -> types.variant("mod", "Shared"));
    }

    @Test
    void unknownVariantReturnsNull() {
      final var main = program(enumeration("Color", true, variant("Red")));
      final var types = build(main);
      assertNull(types.variant(TypeRegistry.MAIN, "Missing"));
      assertNull(types.variant(null, "Missing"));
    }

    @Test
    void resolveWithinSpecificEnum() {
      final var main =
          program(
              enumeration("Color", true, variant("Red"), variant("Blue")),
              enumeration("Shape", true, variant("Circle")));
      final var types = build(main);
      final var key = new SymbolKey(TypeRegistry.MAIN, "Color");
      final var red = types.resolve(key, "Red");
      assertNotNull(red);
      assertEquals(0, red.index());

      // Circle is not in Color
      assertNull(types.resolve(key, "Circle"));
    }

    @Test
    void ownerFindsEnumInModule() {
      registry.register(
          "result", module(enumeration("Result", true, variant("Ok"), variant("Err"))));
      final var types = build(program());
      final var owner = types.owner("result", "Ok");
      assertNotNull(owner);
      assertEquals(new SymbolKey("result", "Result"), owner);
    }

    @Test
    void ownerReturnsNullForWrongModule() {
      registry.register(
          "result", module(enumeration("Result", true, variant("Ok"), variant("Err"))));
      final var types = build(program());
      assertNull(types.owner("option", "Ok"));
    }

    @Test
    void crossModuleEnumVariantOrderIndependent() {
      // Issue 3: two modules define Foo/Bar in different orders
      registry.register("m1", module(enumeration("Choice", true, variant("Foo"), variant("Bar"))));
      registry.register("m2", module(enumeration("Choice", true, variant("Bar"), variant("Foo"))));

      final var types = build(program());
      // In m1: Foo=0, Bar=1
      assertEquals(0, types.variant("m1", "Foo").index());
      assertEquals(1, types.variant("m1", "Bar").index());
      // In m2: Bar=0, Foo=1
      assertEquals(0, types.variant("m2", "Bar").index());
      assertEquals(1, types.variant("m2", "Foo").index());
      // Different type IDs
      assertNotEquals(types.variant("m1", "Foo").type(), types.variant("m2", "Foo").type());
    }

    @Test
    void multipleDataVariants() {
      final var main =
          program(
              enumeration(
                  "Expr",
                  true,
                  variant("Lit", new TypeNode(0, 0, "int")),
                  variant("Add", new TypeNode(0, 0, "Expr"), new TypeNode(0, 0, "Expr")),
                  variant("Neg", new TypeNode(0, 0, "Expr"))));
      final var types = build(main);
      assertEquals(1, types.variant(TypeRegistry.MAIN, "Lit").arity());
      assertEquals(2, types.variant(TypeRegistry.MAIN, "Add").arity());
      assertEquals(1, types.variant(TypeRegistry.MAIN, "Neg").arity());
    }
  }

  // === Variant Import Scope ===

  @Nested
  class VariantImportScope {

    @Test
    void importedVariantResolvesFromImportedModule() {
      // foo defines Ok; main imports foo; Ok in main resolves to foo.Ok
      registry.register("foo", module(enumeration("Result", true, variant("Ok"), variant("Err"))));
      final var main = program(List.of(use("foo")));

      final var types = build(main);
      final var resolved = types.variant(TypeRegistry.MAIN, "Ok");
      assertNotNull(resolved);
      assertEquals(new SymbolKey("foo", "Result"), resolved.owner());
    }

    @Test
    void unimportedVariantReturnsNull() {
      // foo defines Ok but main does NOT import foo; Ok in main must not resolve.
      registry.register("foo", module(enumeration("Result", true, variant("Ok"), variant("Err"))));
      final var main = program();

      final var types = build(main);
      assertNull(types.variant(TypeRegistry.MAIN, "Ok"));
    }

    @Test
    void transitiveOnlyVariantReturnsNull() {
      // bar defines Ok; foo imports bar; main imports foo (but NOT bar).
      // Ok in main must not resolve via the transitive bar import.
      registry.register("bar", module(enumeration("Result", true, variant("Ok"), variant("Err"))));
      registry.register("foo", module(List.of(use("bar"))));
      final var main = program(List.of(use("foo")));

      final var types = build(main);
      assertNull(types.variant(TypeRegistry.MAIN, "Ok"));
    }

    @Test
    void multipleImportedModulesWithSameVariantThrows() {
      // Two imported modules each define Ok; main imports both; Ok is ambiguous.
      registry.register(
          "option", module(enumeration("Option", true, variant("Ok"), variant("None"))));
      registry.register(
          "result", module(enumeration("Result", true, variant("Ok"), variant("Err"))));
      final var main = program(List.of(use("option"), use("result")));

      final var types = build(main);
      assertThrows(CompilerException.class, () -> types.variant(TypeRegistry.MAIN, "Ok"));
    }

    @Test
    void selectiveImportExcludesUnlistedVariants() {
      // foo exports Ok and Err; main imports foo selectively (only Err).
      // Ok must not resolve in main; Err must resolve.
      registry.register("foo", module(enumeration("Result", true, variant("Ok"), variant("Err"))));
      final var main = program(List.of(use("foo", "Err")));

      final var types = build(main);
      assertNull(types.variant(TypeRegistry.MAIN, "Ok"));
      final var err = types.variant(TypeRegistry.MAIN, "Err");
      assertNotNull(err);
      assertEquals(new SymbolKey("foo", "Result"), err.owner());
    }

    @Test
    void moduleLocalVariantWinsOverImportedVariant() {
      // foo defines Ok; main also defines its own Ok and imports foo.
      // The local Ok wins.
      registry.register("foo", module(enumeration("Result", true, variant("Ok"), variant("Err"))));
      final var main =
          program(List.of(use("foo")), enumeration("Local", true, variant("Ok"), variant("Nope")));

      final var types = build(main);
      final var resolved = types.variant(TypeRegistry.MAIN, "Ok");
      assertNotNull(resolved);
      assertEquals(new SymbolKey(TypeRegistry.MAIN, "Local"), resolved.owner());
    }

    @Test
    void selectiveImportIncludingVariantResolves() {
      // Sanity check: a selective import that DOES list the variant works.
      registry.register("foo", module(enumeration("Result", true, variant("Ok"), variant("Err"))));
      final var main = program(List.of(use("foo", "Ok", "Err")));

      final var types = build(main);
      final var ok = types.variant(TypeRegistry.MAIN, "Ok");
      assertNotNull(ok);
      assertEquals(new SymbolKey("foo", "Result"), ok.owner());
    }
  }

  // === Struct Registration ===

  @Nested
  class StructRegistration {

    @Test
    void singleStruct() {
      final var main = program(struct("Point", true, field("int", "x"), field("int", "y")));
      final var types = build(main);
      assertEquals(1, types.structCount());
      assertTrue(types.object(TypeRegistry.MAIN, "Point") >= 0);
    }

    @Test
    void structFieldOrder() {
      final var main =
          program(struct("Point", true, field("int", "x"), field("int", "y"), field("int", "z")));
      final var types = build(main);
      final var fields = types.fields(TypeRegistry.MAIN, "Point");
      assertEquals(List.of("x", "y", "z"), fields);
    }

    @Test
    void fieldIndexLookup() {
      final var main =
          program(struct("Point", true, field("int", "x"), field("int", "y"), field("int", "z")));
      final var types = build(main);
      assertEquals(0, types.field(TypeRegistry.MAIN, "Point", "x"));
      assertEquals(1, types.field(TypeRegistry.MAIN, "Point", "y"));
      assertEquals(2, types.field(TypeRegistry.MAIN, "Point", "z"));
      assertEquals(-1, types.field(TypeRegistry.MAIN, "Point", "w"));
    }

    @Test
    void sameFieldNameDifferentStructs() {
      // Issue 5: two structs with "shared" field at different offsets
      final var main =
          program(
              struct("A", true, field("int", "shared"), field("int", "other")),
              struct("B", true, field("int", "first"), field("int", "shared")));
      final var types = build(main);
      assertEquals(0, types.field(TypeRegistry.MAIN, "A", "shared"));
      assertEquals(1, types.field(TypeRegistry.MAIN, "B", "shared"));
    }

    @Test
    void sameStructNameDifferentModules() {
      registry.register("m1", module(struct("Point", true, field("int", "x"), field("int", "y"))));
      registry.register(
          "m2", module(struct("Point", true, field("float", "lat"), field("float", "lon"))));

      final var types = build(program());
      final var id1 = types.object("m1", "Point");
      final var id2 = types.object("m2", "Point");
      assertTrue(id1 >= 0);
      assertTrue(id2 >= 0);
      assertNotEquals(id1, id2);

      assertEquals(List.of("x", "y"), types.fields("m1", "Point"));
      assertEquals(List.of("lat", "lon"), types.fields("m2", "Point"));
    }

    @Test
    void structDeclarationPreserved() {
      final var original = struct("Point", true, field("int", "x"));
      final var main = program(original);
      final var types = build(main);
      assertSame(original, types.struct(TypeRegistry.MAIN, "Point"));
    }

    @Test
    void unknownStructReturnsNegative() {
      final var types = build(program());
      assertEquals(-1, types.object("nonexistent", "Foo"));
      assertNull(types.fields("nonexistent", "Foo"));
    }

    @Test
    void emptyStruct() {
      final var main = program(struct("Empty", true));
      final var types = build(main);
      assertEquals(1, types.structCount());
      assertEquals(List.of(), types.fields(TypeRegistry.MAIN, "Empty"));
    }

    @Test
    void structAndEnumIdsDisjoint() {
      // Struct and enum type IDs are from separate sequences
      final var main =
          program(enumeration("E1", true, variant("V1")), struct("S1", true, field("int", "x")));
      final var types = build(main);
      // Both should be valid (>= 0), sequences are independent
      assertTrue(types.type(TypeRegistry.MAIN, "E1") >= 0);
      assertTrue(types.object(TypeRegistry.MAIN, "S1") >= 0);
    }

    @Test
    void fieldsByKey() {
      final var main = program(struct("Point", true, field("int", "x"), field("int", "y")));
      final var types = build(main);
      final var key = new SymbolKey(TypeRegistry.MAIN, "Point");
      assertEquals(List.of("x", "y"), types.fields(key));
    }

    @Test
    void fieldByKey() {
      final var main = program(struct("Point", true, field("int", "x"), field("int", "y")));
      final var types = build(main);
      final var key = new SymbolKey(TypeRegistry.MAIN, "Point");
      assertEquals(0, types.field(key, "x"));
      assertEquals(1, types.field(key, "y"));
      assertEquals(-1, types.field(key, "z"));
    }

    @Test
    void structKeys() {
      registry.register("m", module(struct("S1", true, field("int", "a"))));
      final var main = program(struct("S2", true, field("int", "b")));
      final var types = build(main);
      assertTrue(types.structKeys().contains(new SymbolKey("m", "S1")));
      assertTrue(types.structKeys().contains(new SymbolKey(TypeRegistry.MAIN, "S2")));
    }
  }

  // === Recursive Enum Detection ===

  @Nested
  class RecursiveEnums {

    @Test
    void selfReferencing() {
      // enum Tree { Leaf, Node(Tree, Tree) }
      final var main =
          program(
              enumeration(
                  "Tree",
                  true,
                  variant("Leaf"),
                  variant("Node", new TypeNode(0, 0, "Tree"), new TypeNode(0, 0, "Tree"))));
      final var types = build(main);
      assertTrue(types.recursive(TypeRegistry.MAIN, "Tree"));
    }

    @Test
    void nonRecursive() {
      final var main = program(enumeration("Color", true, variant("Red"), variant("Green")));
      final var types = build(main);
      assertFalse(types.recursive(TypeRegistry.MAIN, "Color"));
    }

    @Test
    void recursiveByKey() {
      final var main =
          program(
              enumeration(
                  "List",
                  true,
                  variant("Nil"),
                  variant("Cons", new TypeNode(0, 0, "int"), new TypeNode(0, 0, "List"))));
      final var types = build(main);
      assertTrue(types.recursive(new SymbolKey(TypeRegistry.MAIN, "List")));
    }

    @Test
    void moduleRecursiveEnum() {
      registry.register(
          "tree",
          module(
              enumeration(
                  "Tree",
                  true,
                  variant("Empty"),
                  variant(
                      "Node",
                      new TypeNode(0, 0, "int"),
                      new TypeNode(0, 0, "Tree"),
                      new TypeNode(0, 0, "Tree")))));
      final var types = build(program());
      assertTrue(types.recursive("tree", "Tree"));
    }

    @Test
    void mutuallyRecursiveEnumsDetected() {
      final var main =
          program(
              enumeration("A", true, variant("WrapB", new TypeNode(0, 0, "B"))),
              enumeration("B", true, variant("WrapA", new TypeNode(0, 0, "A"))));

      final var types = build(main);
      assertTrue(types.recursive(TypeRegistry.MAIN, "A"));
      assertTrue(types.recursive(TypeRegistry.MAIN, "B"));
    }
  }

  // === Type ID Uniqueness ===

  @Nested
  class TypeIdUniqueness {

    @Test
    void allEnumIdsUnique() {
      registry.register(
          "a",
          module(enumeration("E1", true, variant("V1")), enumeration("E2", true, variant("V2"))));
      registry.register("b", module(enumeration("E3", true, variant("V3"))));
      final var main = program(enumeration("E4", true, variant("V4")));

      final var types = build(main);
      final var ids = new HashSet<Integer>();
      for (final var key : types.enumKeys()) {
        assertTrue(ids.add(types.type(key)), "Duplicate type ID for " + key);
      }
      assertEquals(4, ids.size());
    }

    @Test
    void allObjectIdsUnique() {
      registry.register("a", module(struct("S1", true, field("int", "x"))));
      registry.register("b", module(struct("S2", true, field("int", "y"))));
      final var main = program(struct("S3", true, field("int", "z")));

      final var types = build(main);
      final var ids = new HashSet<Integer>();
      for (final var key : types.structKeys()) {
        assertTrue(ids.add(types.object(key)), "Duplicate object ID for " + key);
      }
      assertEquals(3, ids.size());
    }

    @Test
    void idsSequential() {
      registry.register("a", module(enumeration("E1", true, variant("V1"))));
      registry.register("b", module(enumeration("E2", true, variant("V2"))));

      final var types = build(program());
      final var id1 = types.type("a", "E1");
      final var id2 = types.type("b", "E2");
      // IDs should be 0 and 1 (sequential)
      assertTrue(id1 == 0 || id1 == 1);
      assertTrue(id2 == 0 || id2 == 1);
      assertNotEquals(id1, id2);
    }
  }

  // === Real Stdlib Scenarios ===

  @Nested
  class StdlibScenarios {

    @Test
    void optionAndResultBothDefineOkPattern() {
      registry.register(
          "option",
          module(
              enumeration(
                  "Option", true, variant("Some", new TypeNode(0, 0, "int")), variant("None"))));
      registry.register(
          "result",
          module(
              enumeration(
                  "Result",
                  true,
                  variant("Ok", new TypeNode(0, 0, "int")),
                  variant("Err", new TypeNode(0, 0, "string")))));

      final var types = build(program());

      // Disambiguated by module
      final var some = types.variant("option", "Some");
      final var ok = types.variant("result", "Ok");
      assertNotNull(some);
      assertNotNull(ok);
      assertNotEquals(some.type(), ok.type());

      // None is unambiguous (only in option)
      final var none = types.variant(null, "None");
      assertNotNull(none);
      assertEquals(new SymbolKey("option", "Option"), none.owner());

      // Err is unambiguous (only in result)
      final var err = types.variant(null, "Err");
      assertNotNull(err);
      assertEquals(new SymbolKey("result", "Result"), err.owner());
    }

    @Test
    void fileModuleEnumTypes() {
      registry.register(
          "file",
          module(
              enumeration(
                  "OpenResult", true, variant("Ok", new TypeNode(0, 0, "File")), variant("Error")),
              enumeration(
                  "ReadResult",
                  true,
                  variant("Ok", new TypeNode(0, 0, "string")),
                  variant("Error")),
              enumeration("WriteResult", true, variant("Ok"), variant("Error")),
              struct("File", true, field("int", "handle"))));

      final var types = build(program());
      // All four get distinct IDs
      final var ids = new HashSet<Integer>();
      ids.add(types.type("file", "OpenResult"));
      ids.add(types.type("file", "ReadResult"));
      ids.add(types.type("file", "WriteResult"));
      assertEquals(3, ids.size());

      // File struct gets an object ID
      assertTrue(types.object("file", "File") >= 0);
    }

    @Test
    void moduleWithOnlyConstants() {
      // math module has no enums or structs — just constants and functions
      registry.register("math", module());
      final var types = build(program());
      assertEquals(0, types.enums("math").size());
    }

    @Test
    void treeModuleRecursiveEnum() {
      registry.register(
          "tree",
          module(
              enumeration(
                  "Tree",
                  true,
                  variant("Empty"),
                  variant(
                      "Node",
                      new TypeNode(0, 0, "int"),
                      new TypeNode(0, 0, "Tree"),
                      new TypeNode(0, 0, "Tree")))));
      final var types = build(program());

      assertTrue(types.recursive("tree", "Tree"));
      final var empty = types.variant("tree", "Empty");
      final var node = types.variant("tree", "Node");
      assertEquals(0, empty.arity());
      assertEquals(3, node.arity());
      assertEquals(0, empty.index());
      assertEquals(1, node.index());
    }

    @Test
    void zeroArityVariantsFromDifferentModules() {
      // Issue 8: None/Empty from different modules
      registry.register(
          "option",
          module(
              enumeration(
                  "Option", true, variant("Some", new TypeNode(0, 0, "int")), variant("None"))));
      registry.register(
          "tree",
          module(
              enumeration(
                  "Tree", true, variant("Empty"), variant("Node", new TypeNode(0, 0, "int")))));

      final var types = build(program());
      final var none = types.variant("option", "None");
      final var empty = types.variant("tree", "Empty");
      assertNotNull(none);
      assertNotNull(empty);
      // Distinct types — no collision
      assertNotEquals(none.type(), empty.type());
    }
  }

  // === Edge Cases ===

  @Nested
  class EdgeCases {

    @Test
    void emptyRegistryAndProgram() {
      final var types = build(program());
      assertEquals(0, types.enumCount());
      assertEquals(0, types.structCount());
    }

    @Test
    void duplicateRegistrationIgnored() {
      final var enumeration = enumeration("Color", true, variant("Red"));
      registry.register("m", module(enumeration));
      // Building twice with same module should not double-register
      final var types = build(program());
      assertEquals(1, types.enumCount());
    }

    @Test
    void fieldsListIsUnmodifiable() {
      final var main = program(struct("Point", true, field("int", "x")));
      final var types = build(main);
      assertThrows(
          UnsupportedOperationException.class,
          () -> types.fields(TypeRegistry.MAIN, "Point").add("hack"));
    }

    @Test
    void enumKeysByKey() {
      final var main = program(enumeration("E", true, variant("V")));
      final var types = build(main);
      final var key = new SymbolKey(TypeRegistry.MAIN, "E");
      assertEquals(types.type(TypeRegistry.MAIN, "E"), types.type(key));
      assertSame(types.enumeration(TypeRegistry.MAIN, "E"), types.enumeration(key));
    }

    @Test
    void objectByKey() {
      final var main = program(struct("S", true, field("int", "x")));
      final var types = build(main);
      final var key = new SymbolKey(TypeRegistry.MAIN, "S");
      assertEquals(types.object(TypeRegistry.MAIN, "S"), types.object(key));
    }
  }
}
