package io.safelang;

import static org.junit.jupiter.api.Assertions.*;

import io.safelang.compiler.wasm.ModuleSymbols;
import io.safelang.compiler.wasm.SymbolKey;
import java.util.OptionalInt;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class ModuleSymbolsTests {

  private ModuleSymbols symbols;

  @BeforeEach
  void setup() {
    symbols = new ModuleSymbols();
  }

  // === Function Resolution ===

  @Nested
  class FunctionResolution {

    @Test
    void localFunction() {
      symbols.addLocal("helper", 5, 2);
      assertEquals(OptionalInt.of(5), symbols.function("helper"));
      assertEquals(OptionalInt.of(2), symbols.arity("helper"));
    }

    @Test
    void exportedFunction() {
      symbols.addExport("sqrt", 10, 1);
      assertEquals(OptionalInt.of(10), symbols.function("sqrt"));
      assertEquals(OptionalInt.of(1), symbols.arity("sqrt"));
    }

    @Test
    void importedFunction() {
      symbols.addImport("math", "sqrt", 3, 1);
      assertEquals(OptionalInt.of(3), symbols.function("sqrt"));
      assertEquals(OptionalInt.of(1), symbols.arity("sqrt"));
    }

    @Test
    void localBeforeExported() {
      symbols.addLocal("f", 1, 0);
      symbols.addExport("f", 2, 0);
      // Local takes priority
      assertEquals(OptionalInt.of(1), symbols.function("f"));
    }

    @Test
    void exportedBeforeImported() {
      symbols.addExport("f", 2, 0);
      symbols.addImport("other", "f", 3, 0);
      // Exported takes priority
      assertEquals(OptionalInt.of(2), symbols.function("f"));
    }

    @Test
    void localBeforeImported() {
      symbols.addLocal("f", 1, 0);
      symbols.addImport("other", "f", 3, 0);
      assertEquals(OptionalInt.of(1), symbols.function("f"));
    }

    @Test
    void unknownFunction() {
      assertEquals(OptionalInt.empty(), symbols.function("missing"));
      assertEquals(OptionalInt.empty(), symbols.arity("missing"));
    }

    @Test
    void hasFunction() {
      symbols.addLocal("a", 1, 0);
      symbols.addExport("b", 2, 0);
      symbols.addImport("m", "c", 3, 0);
      assertTrue(symbols.hasFunction("a"));
      assertTrue(symbols.hasFunction("b"));
      assertTrue(symbols.hasFunction("c"));
      assertFalse(symbols.hasFunction("d"));
    }

    @Test
    void importedFunctionDetails() {
      symbols.addImport("math", "sqrt", 3, 1);
      final var imp = symbols.importedFunction("sqrt");
      assertNotNull(imp);
      assertEquals("math", imp.module());
      assertEquals("sqrt", imp.name());
      assertEquals(3, imp.index());
    }

    @Test
    void importedFunctionNullForLocal() {
      symbols.addLocal("helper", 5, 2);
      assertNull(symbols.importedFunction("helper"));
    }

    @Test
    void importWithAlias() {
      symbols.addImport("math", "sqrt", "root", 3, 1);
      assertEquals(OptionalInt.of(3), symbols.function("root"));
      assertEquals(OptionalInt.empty(), symbols.function("sqrt"));
      final var imp = symbols.importedFunction("root");
      assertNotNull(imp);
      assertEquals("math", imp.module());
      assertEquals("sqrt", imp.name());
    }
  }

  // === Variable Type Tracking ===

  @Nested
  class VariableTypeTracking {

    @Test
    void declare() {
      final var key = new SymbolKey("option", "Option");
      symbols.declare("result", key);
      assertEquals(key, symbols.declared("result"));
    }

    @Test
    void undeclaredReturnsNull() {
      assertNull(symbols.declared("unknown"));
    }

    @Test
    void overwrite() {
      symbols.declare("x", new SymbolKey("a", "T1"));
      symbols.declare("x", new SymbolKey("b", "T2"));
      assertEquals(new SymbolKey("b", "T2"), symbols.declared("x"));
    }
  }

  // === Export/Import Queries ===

  @Nested
  class ExportImportQueries {

    @Test
    void isExported() {
      symbols.addExport("sqrt", 1, 1);
      symbols.addLocal("helper", 2, 0);
      assertTrue(symbols.isExported("sqrt"));
      assertFalse(symbols.isExported("helper"));
      assertFalse(symbols.isExported("missing"));
    }

    @Test
    void exportNames() {
      symbols.addExport("sqrt", 1, 1);
      symbols.addExport("pow", 2, 2);
      symbols.addLocal("helper", 3, 0);
      final var exports = symbols.exports();
      assertEquals(2, exports.size());
      assertTrue(exports.contains("sqrt"));
      assertTrue(exports.contains("pow"));
      assertFalse(exports.contains("helper"));
    }

    @Test
    void importEntries() {
      symbols.addImport("math", "sqrt", 0, 1);
      symbols.addImport("io", "print", 1, 1);
      final var imports = symbols.imports();
      assertEquals(2, imports.size());
    }
  }
}
