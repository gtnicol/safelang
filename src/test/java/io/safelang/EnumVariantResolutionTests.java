package io.safelang;

import static org.junit.jupiter.api.Assertions.*;

import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Phase 3 (fourth-round audit) regression tests for module-aware enum variant resolution.
 *
 * <p>Before this phase:
 *
 * <ul>
 *   <li>Qualified variant construction {@code mod:Ok(42)} was rejected by the analyzer as
 *       "Undefined function", even though the WASM backend already supported it.
 *   <li>Unqualified variant construction with colliding names (two imported enums both exposing
 *       {@code Ok}) silently picked the first-registered enum, so {@code mod_b.Outcome s =
 *       Ok("hello")} failed with "expected int" because {@code mod_a.Status.Ok} was picked.
 *   <li>Pattern matching compared only variant names, not the subject's owning enum, so branches
 *       could match the wrong variant in ambiguous cases.
 * </ul>
 *
 * <p>The fix: analyzer/interpreter/bytecode/C backend qualified dispatch recognizes enum variants;
 * unqualified construction prefers the declared target enum's variant; case-branch variant lookup
 * uses the subject's resolved enum type.
 */
class EnumVariantResolutionTests {

  private Path workDirectory;

  @BeforeEach
  void setup() throws Exception {
    workDirectory = Files.createTempDirectory("safe_enum_resolution_");
    Files.writeString(
        workDirectory.resolve("mod_a.safe"),
        """
        module mod_a;

        public enum Status {
          Ok(int),
          Err(string)
        }
        """);
    Files.writeString(
        workDirectory.resolve("mod_b.safe"),
        """
        module mod_b;

        public enum Outcome {
          Ok(string),
          Err(int)
        }
        """);
    Files.writeString(
        workDirectory.resolve("collision_mod.safe"),
        """
        module collision_mod;

        public enum MyResult {
          Ok(int),
          Err(string)
        }
        """);
  }

  @AfterEach
  void teardown() throws Exception {
    if (workDirectory != null) {
      try (var walk = Files.walk(workDirectory)) {
        walk.sorted((a, b) -> b.compareTo(a))
            .forEach(
                path -> {
                  try {
                    Files.deleteIfExists(path);
                  } catch (final Exception ignored) {
                  }
                });
      }
    }
  }

  private String run(final String source) throws Exception {
    final var main = workDirectory.resolve("main.safe");
    Files.writeString(main, source);
    final var options =
        SafeFrontend.Options.defaults()
            .withSource(main)
            .withPreloads(SafeFrontend.stdlibModules(), true)
            .withModulePath(List.of(workDirectory));
    final var result = SafeFrontend.bootstrap(source, options);
    final var capture = new StringWriter();
    final var interpreter = new io.safelang.interpreter.Interpreter();
    interpreter.setRegistry(result.registry());
    interpreter.setOutput(capture);
    interpreter.interpret(result.program());
    return capture.toString().stripTrailing();
  }

  @Test
  void qualifiedVariantConstruction() throws Exception {
    assertEquals(
        "got result",
        run(
            """
            program test;
            import collision_mod;
            import io;

            const MyResult r = collision_mod:Ok(42);
            io:println(`got result`);
            """));
  }

  @Test
  void qualifiedVariantWithSelectiveImport() throws Exception {
    // Qualified variant construction must work even when MyResult isn't in the selective set —
    // the user is being explicit by qualifying.
    assertEquals(
        "got result",
        run(
            """
            program test;
            import collision_mod { MyResult };
            import io;

            const MyResult r = collision_mod:Ok(42);
            io:println(`got result`);
            """));
  }

  @Test
  void collisionResolvedBySubjectType() throws Exception {
    // mod_a.Status.Ok takes int; mod_b.Outcome.Ok takes string. Before the fix this picked
    // mod_a first-match and errored "expected int got string".
    assertEquals(
        "matched hello",
        run(
            """
            program test;
            import mod_a;
            import mod_b;
            import io;

            const Outcome s = Ok("hello");
            case s of {
              Ok(x): io:println(`matched ${x}`);
              Err(m): io:println(`err`);
            };
            """));
  }

  @Test
  void collisionResolvedByQualifiedTypeName() throws Exception {
    // Same as above but with the `mod_b.Outcome` module-qualified type name.
    assertEquals(
        "matched hello",
        run(
            """
            program test;
            import mod_a;
            import mod_b;
            import io;

            const mod_b.Outcome s = Ok("hello");
            case s of {
              Ok(x): io:println(`matched ${x}`);
              Err(m): io:println(`err`);
            };
            """));
  }

  @Test
  void patternMatchUsesSubjectEnumType() throws Exception {
    // With a specifically-typed subject, Err branch must match mod_b.Outcome.Err (int),
    // not mod_a.Status.Err (string).
    assertEquals(
        "err 7",
        run(
            """
            program test;
            import mod_a;
            import mod_b;
            import io;

            const Outcome s = Err(7);
            case s of {
              Ok(x): io:println(`ok ${x}`);
              Err(n): io:println(`err ${n}`);
            };
            """));
  }
}
