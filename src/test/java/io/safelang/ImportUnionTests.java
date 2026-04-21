package io.safelang;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

/**
 * Phase 1 (fourth-round audit) regression tests for additive selective imports.
 *
 * <p>Before this phase, the overwrite pattern `selective.put(module, symbols)` dropped any prior
 * set, so {@code import math { PI }; import math { sqrt };} rejected {@code math.PI}. The fix
 * unions selective imports and lets any non-selective import grant full access (absorbing
 * subsequent selective imports of the same module).
 */
class ImportUnionTests {

  private static final String SETUP =
      """
      program test;
      import math { PI };
      import math { sqrt };
      import io;

      const float pi = math.PI;
      const float root = math:sqrt(4.0);
      io:println(`${pi} ${root}`);
      """;

  @Test
  void selectiveUnionsInterpreter() {
    assertEquals("3.14159265358979 2.0", TestHelper.run(SETUP));
  }

  @Test
  void selectiveUnionsBytecode() {
    assertEquals("3.14159265358979 2.0", TestHelper.bytecode(SETUP));
  }

  @Test
  void fullAfterSelectiveGrantsFull() {
    final var source =
        """
        program test;
        import math { PI };
        import math;
        import io;

        const float root = math:sqrt(4.0);
        io:println(`${math.PI} ${root}`);
        """;
    assertEquals("3.14159265358979 2.0", TestHelper.run(source));
    assertEquals("3.14159265358979 2.0", TestHelper.bytecode(source));
  }

  @Test
  void selectiveAfterFullGrantsFull() {
    final var source =
        """
        program test;
        import math;
        import math { PI };
        import io;

        const float root = math:sqrt(4.0);
        io:println(`${math.PI} ${root}`);
        """;
    assertEquals("3.14159265358979 2.0", TestHelper.run(source));
    assertEquals("3.14159265358979 2.0", TestHelper.bytecode(source));
  }

  @Test
  void duplicateSymbolsIdempotent() {
    final var source =
        """
        program test;
        import math { PI };
        import math { PI };
        import io;

        io:println(`${math.PI}`);
        """;
    assertEquals("3.14159265358979", TestHelper.run(source));
    assertEquals("3.14159265358979", TestHelper.bytecode(source));
  }

  @Test
  void unionRejectsUnimportedSymbol() {
    // pow is not in either selective set → still rejected
    final var source =
        """
        program test;
        import math { PI };
        import math { sqrt };

        const float x = math:pow(2.0, 3.0);
        """;
    assertThrows(Exception.class, () -> TestHelper.run(source));
  }
}
