package io.safelang;

import static org.junit.jupiter.api.Assertions.*;

import io.safelang.bytecode.BytecodeVM;
import io.safelang.compiler.bytecode.BytecodeCompiler;
import io.safelang.interpreter.Interpreter;
import io.safelang.runtime.Capabilities;
import io.safelang.runtime.Capability;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Host capability enforcement: dangerous builtins are denied unless the embedder grants the
 * matching capability, enforced at runtime on the interpreter and — crucially — the bytecode VM
 * (which bypasses source-level strict analysis).
 */
class CapabilityTests {

  private static final String WRITES_A_FILE =
      """
      program test;
      import io;
      import file;
      file:write("/tmp/safe-capability-probe.txt", "x");
      io:println("done");
      """;

  private static final String PURE =
      """
      program test;
      import io;
      io:println("hello");
      """;

  private static void interpret(final String source, final Capabilities capabilities) {
    final var parsed = SafeFrontend.bootstrap(source, SafeFrontend.Options.defaults());
    final var interpreter = new Interpreter(List.of(), capabilities);
    interpreter.setRegistry(parsed.registry());
    interpreter.interpret(parsed.program());
  }

  private static void runOnVm(final String source, final Capabilities capabilities) {
    final var parsed = SafeFrontend.bootstrap(source, SafeFrontend.Options.defaults());
    final var compiler = new BytecodeCompiler();
    compiler.setRegistry(parsed.registry());
    final var module = compiler.compile(parsed.program());
    new BytecodeVM(module, List.of(), capabilities).execute();
  }

  @Test
  void testInterpreterDeniesFileWithoutCapability() {
    final var error =
        assertThrows(RuntimeException.class, () -> interpret(WRITES_A_FILE, Capabilities.none()));
    assertTrue(error.getMessage().contains("capability denied"));
    assertTrue(error.getMessage().contains("FILESYSTEM"));
  }

  @Test
  void testInterpreterAllowsFileWhenGranted() {
    assertDoesNotThrow(() -> interpret(WRITES_A_FILE, Capabilities.of(Capability.FILESYSTEM)));
  }

  @Test
  void testWrongCapabilityStillDenies() {
    // Granting NETWORK does not grant FILESYSTEM.
    assertThrows(
        RuntimeException.class,
        () -> interpret(WRITES_A_FILE, Capabilities.of(Capability.NETWORK)));
  }

  @Test
  void testPureProgramUnaffectedByDenyAll() {
    assertDoesNotThrow(() -> interpret(PURE, Capabilities.none()));
  }

  private static final String READS_OS =
      """
      program test;
      import io;
      io:println(OS);
      """;

  private static final String HTTP_BAD_URL =
      """
      program test;
      import io;
      import http;
      HttpResult r = http:get("ht!tp://no where/x");
      io:println("survived");
      """;

  @Test
  void testInterpreterDeniesHostGlobalWithoutEnvironment() {
    // OS/ARCH/OS_VERSION/PLATFORM require ENVIRONMENT; ungranted, they are not defined at all.
    final var error =
        assertThrows(RuntimeException.class, () -> interpret(READS_OS, Capabilities.none()));
    assertTrue(error.getMessage().contains("OS"));
  }

  @Test
  void testInterpreterAllowsHostGlobalWithEnvironment() {
    assertDoesNotThrow(() -> interpret(READS_OS, Capabilities.of(Capability.ENVIRONMENT)));
  }

  @Test
  void testVmDeniesHostGlobalWithoutEnvironment() {
    assertThrows(RuntimeException.class, () -> runOnVm(READS_OS, Capabilities.none()));
  }

  @Test
  void testMalformedUrlYieldsErrNotCrash() {
    // A malformed URL must return HttpResult.Err (the program keeps running), not throw.
    assertDoesNotThrow(() -> interpret(HTTP_BAD_URL, Capabilities.of(Capability.NETWORK)));
  }

  @Test
  void testBytecodeVmEnforcesAtRuntime() {
    // The .safeb path runs no source analysis, so the VM's runtime gate is the only protection.
    final var error =
        assertThrows(RuntimeException.class, () -> runOnVm(WRITES_A_FILE, Capabilities.none()));
    assertTrue(error.getMessage().contains("capability denied"));
  }

  @Test
  void testBytecodeVmAllowsWhenGranted() {
    assertDoesNotThrow(() -> runOnVm(WRITES_A_FILE, Capabilities.of(Capability.FILESYSTEM)));
  }

  @Test
  void testScriptEngineDeniesByDefault() {
    final var engine = new javax.script.ScriptEngineManager().getEngineByName("safe");
    assertThrows(javax.script.ScriptException.class, () -> engine.eval(WRITES_A_FILE));
  }

  @Test
  void testScriptEngineGrantsViaBinding() throws Exception {
    final var engine = new javax.script.ScriptEngineManager().getEngineByName("safe");
    engine.put("safe.capabilities", "filesystem");
    assertDoesNotThrow(() -> engine.eval(WRITES_A_FILE));
  }

  @Test
  void testScriptEnginePureWorksWithoutGrant() {
    final var engine = new javax.script.ScriptEngineManager().getEngineByName("safe");
    assertDoesNotThrow(() -> engine.eval(PURE));
  }

  private static final String CALLS_HTTP =
      """
      program test;
      import io;
      import http { get, HttpResult };
      HttpResult r = http:get("http://example.com");
      io:println("done");
      """;

  @Test
  void testJvmAotGateRefusesUngrantedBuiltin() throws Exception {
    // A self-executing jar built with NETWORK denied must not emit the network builtin.
    final var file = java.nio.file.Files.createTempFile("cap", ".safe");
    final var error =
        assertThrows(
            Exception.class,
            () ->
                io.safelang.SafeRuntime.jvm(
                    CALLS_HTTP,
                    file.toString(),
                    false,
                    List.of(),
                    Capabilities.all().without(Capability.NETWORK)));
    assertTrue(error.getMessage().contains("NETWORK"));
  }

  @Test
  void testJvmAotEmitsWhenGranted() throws Exception {
    final var file = java.nio.file.Files.createTempFile("cap", ".safe");
    assertDoesNotThrow(
        () ->
            io.safelang.SafeRuntime.jvm(
                CALLS_HTTP, file.toString(), false, List.of(), Capabilities.all()));
  }

  private static final String WRITES_A_FILE_PROGRAM =
      """
      program test;
      import io;
      import file;
      WriteResult w = file:write("/tmp/safe-cap-wasm.txt", "x");
      io:println("done");
      """;

  @Test
  void testWasmAotGateRefusesUngrantedFilesystem() throws Exception {
    final var file = java.nio.file.Files.createTempFile("cap", ".safe");
    final var error =
        assertThrows(
            Exception.class,
            () ->
                io.safelang.SafeRuntime.wasm(
                    WRITES_A_FILE_PROGRAM,
                    file.toString(),
                    false,
                    List.of(),
                    Capabilities.all().without(Capability.FILESYSTEM)));
    assertTrue(error.getMessage().contains("FILESYSTEM"));
  }

  @Test
  void testWasmAotEmitsWhenFilesystemGranted() throws Exception {
    final var file = java.nio.file.Files.createTempFile("cap", ".safe");
    assertDoesNotThrow(
        () ->
            io.safelang.SafeRuntime.wasm(
                WRITES_A_FILE_PROGRAM, file.toString(), false, List.of(), Capabilities.all()));
  }

  @Test
  void testSafeRuntimeDeniesByDefault() {
    // The programmatic API fails safe: a no-policy run of a host-using program is denied.
    final var error =
        assertThrows(
            RuntimeException.class,
            () -> io.safelang.SafeRuntime.run(WRITES_A_FILE, "deny.safe", List.of(), false));
    assertTrue(error.getMessage().contains("capability denied"));
  }

  @Test
  void testSafeRuntimeRunsWhenPolicyGranted() {
    assertDoesNotThrow(
        () ->
            io.safelang.SafeRuntime.run(
                WRITES_A_FILE,
                "grant.safe",
                List.of(),
                false,
                List.of(),
                io.safelang.runtime.HostPolicy.trusted()));
  }

  @Test
  void testParseAndAliases() {
    final var caps = Capabilities.parse("fs, net");
    assertTrue(caps.granted(Capability.FILESYSTEM));
    assertTrue(caps.granted(Capability.NETWORK));
    assertFalse(caps.granted(Capability.PROCESS));
    assertTrue(Capabilities.parse("all").granted(Capability.PROCESS));
    assertFalse(Capabilities.parse("").granted(Capability.FILESYSTEM));
    assertFalse(Capabilities.all().without(Capability.PROCESS).granted(Capability.PROCESS));
  }
}
