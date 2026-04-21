package io.safelang;

import static org.junit.jupiter.api.Assertions.*;

import io.safelang.compiler.c.CBuildDriver;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Smoke test for {@link CBuildDriver} — the shared driver both {@link SafeRuntime#build} and {@link
 * TestRunner} now route through.
 */
class CBuildDriverTests {

  @Test
  void buildsTrivialProgram(@TempDir final Path directory) throws Exception {
    final var source = directory.resolve("tiny.c");
    Files.writeString(
        source,
        """
        #include <stdio.h>
        int main(void) { printf("ok\\n"); return 0; }
        """);
    final var binary = directory.resolve("tiny");
    assertDoesNotThrow(() -> CBuildDriver.build(source, binary));
    assertTrue(Files.isExecutable(binary), "binary must be produced and executable");
  }

  @Test
  void throwsWithCapturedOutputOnFailure(@TempDir final Path directory) throws Exception {
    final var source = directory.resolve("broken.c");
    Files.writeString(source, "int main(void) { nonexistent_fn(); }\n");
    final var binary = directory.resolve("broken");
    final var exception =
        assertThrows(SAFEException.class, () -> CBuildDriver.build(source, binary));
    assertTrue(
        exception.getMessage().contains("exited with code"),
        "exception message must mention the non-zero exit, got: " + exception.getMessage());
  }

  @Test
  void resolverHonoursSafeCcProperty() {
    final var original = System.getProperty("safe.cc");
    try {
      System.setProperty("safe.cc", "customcc");
      assertEquals("customcc", CBuildDriver.resolveCompiler());
    } finally {
      if (original == null) {
        System.clearProperty("safe.cc");
      } else {
        System.setProperty("safe.cc", original);
      }
    }
  }
}
