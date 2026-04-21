package io.safelang;

import static io.safelang.compiler.wasm.WasmOpcode.*;
import static org.junit.jupiter.api.Assertions.*;

import io.safelang.compiler.CompilerException;
import io.safelang.compiler.wasm.WasmFunction;
import io.safelang.compiler.wasm.WasmModule;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

class WasmCodeGeneratorTests {

  private static boolean wasmtime() {
    try {
      final var process = new ProcessBuilder("wasmtime", "--version").start();
      return process.waitFor() == 0;
    } catch (Exception exception) {
      return false;
    }
  }

  private static String execute(final byte[] wasm) throws Exception {
    final var temp = Files.createTempFile("safe_test_", ".wasm");
    try {
      Files.write(temp, wasm);
      // Extract builtins module for load-time linking
      final var builtins = temp.getParent().resolve("safe_wasm_builtins.wasm");
      SafeMain.extractWasmBuiltins(temp.getParent());
      final var process =
          new ProcessBuilder(
                  "wasmtime",
                  "run",
                  "--preload",
                  "builtins=" + builtins,
                  temp.toAbsolutePath().toString())
              .redirectErrorStream(true)
              .start();
      final var output = new ByteArrayOutputStream();
      process.getInputStream().transferTo(output);
      final var exit = process.waitFor();
      final var result = output.toString(StandardCharsets.UTF_8);
      if (exit != 0) {
        throw new RuntimeException("wasmtime exited with code " + exit + ": " + result);
      }
      return result.stripTrailing();
    } finally {
      Files.deleteIfExists(temp);
    }
  }

  @Test
  void moduleHeader() {
    final var module = new WasmModule();
    final var binary = module.assemble();
    // Magic: \0asm
    assertEquals(0x00, binary[0] & 0xFF);
    assertEquals(0x61, binary[1] & 0xFF);
    assertEquals(0x73, binary[2] & 0xFF);
    assertEquals(0x6D, binary[3] & 0xFF);
    // Version: 1
    assertEquals(0x01, binary[4] & 0xFF);
    assertEquals(0x00, binary[5] & 0xFF);
    assertEquals(0x00, binary[6] & 0xFF);
    assertEquals(0x00, binary[7] & 0xFF);
  }

  @Test
  void typeDeduplication() {
    final var module = new WasmModule();
    final var first = module.addType(new int[] {TYPE_I32}, new int[] {TYPE_I32});
    final var second = module.addType(new int[] {TYPE_I32}, new int[] {TYPE_I32});
    final var different = module.addType(new int[] {TYPE_I64}, new int[] {TYPE_I64});
    assertEquals(first, second, "identical types should share an index");
    assertNotEquals(first, different, "different types should have different indices");
  }

  @Test
  void helloWorld() throws Exception {
    Assumptions.assumeTrue(wasmtime(), "wasmtime not available");

    final var module = new WasmModule();
    final var message = "Hello, World!\n";
    final var messageBytes = message.getBytes(StandardCharsets.UTF_8);

    // Type for fd_write: (i32, i32, i32, i32) -> i32
    final var fdWriteType =
        module.addType(new int[] {TYPE_I32, TYPE_I32, TYPE_I32, TYPE_I32}, new int[] {TYPE_I32});

    // Type for _start: () -> ()
    final var startType = module.addType(new int[] {}, new int[] {});

    // Import fd_write from WASI
    final var fdWrite = module.importFunction("wasi_snapshot_preview1", "fd_write", fdWriteType);

    // Add _start function
    final var start = module.addFunction(startType);

    // Export _start and memory
    module.exportFunction("_start", start);
    module.exportMemory("memory", 0);

    // Data section: iovec struct at offset 0, message at offset 16
    // iovec: [i32 pointer][i32 length]
    final var messageOffset = 16;
    final var iovecOffset = 0;
    final var writtenOffset = 12; // where fd_write stores bytes written

    // iovec struct: pointer to message, length of message
    final var iovec = new byte[8];
    iovec[0] = (byte) (messageOffset & 0xFF);
    iovec[1] = (byte) ((messageOffset >> 8) & 0xFF);
    iovec[2] = (byte) ((messageOffset >> 16) & 0xFF);
    iovec[3] = (byte) ((messageOffset >> 24) & 0xFF);
    iovec[4] = (byte) (messageBytes.length & 0xFF);
    iovec[5] = (byte) ((messageBytes.length >> 8) & 0xFF);
    iovec[6] = (byte) ((messageBytes.length >> 16) & 0xFF);
    iovec[7] = (byte) ((messageBytes.length >> 24) & 0xFF);

    module.addData(iovecOffset, iovec);
    module.addData(messageOffset, messageBytes);

    // Build _start function body
    final var function = new WasmFunction(start, startType);

    // fd_write(fd=1, iovs=0, iovs_len=1, nwritten=12)
    function.emitI32Const(1); // fd: stdout
    function.emitI32Const(iovecOffset); // iovs pointer
    function.emitI32Const(1); // iovs count
    function.emitI32Const(writtenOffset); // nwritten pointer
    function.emitCall(fdWrite);
    function.emit(DROP); // discard return value

    module.addCode(function.encode(module));

    // Assemble and run
    final var binary = module.assemble();
    assertTrue(binary.length > 8, "module should have content beyond header");

    final var output = execute(binary);
    assertEquals("Hello, World!", output);
  }

  @Test
  void emptyStartFunction() throws Exception {
    Assumptions.assumeTrue(wasmtime(), "wasmtime not available");

    final var module = new WasmModule();
    final var startType = module.addType(new int[] {}, new int[] {});
    final var start = module.addFunction(startType);
    module.exportFunction("_start", start);
    module.exportMemory("memory", 0);

    final var function = new WasmFunction(start, startType);
    // Empty body — just returns
    module.addCode(function.encode(module));

    final var output = execute(module.assemble());
    assertEquals("", output);
  }

  @Test
  void globalVariables() throws Exception {
    Assumptions.assumeTrue(wasmtime(), "wasmtime not available");

    final var module = new WasmModule();
    final var message = "42\n";
    final var messageBytes = message.getBytes(StandardCharsets.UTF_8);

    // Types
    final var fdWriteType =
        module.addType(new int[] {TYPE_I32, TYPE_I32, TYPE_I32, TYPE_I32}, new int[] {TYPE_I32});
    final var startType = module.addType(new int[] {}, new int[] {});

    // Import fd_write
    final var fdWrite = module.importFunction("wasi_snapshot_preview1", "fd_write", fdWriteType);

    // Global: mutable i32 counter
    module.addGlobal(TYPE_I32, true, 0);

    // Add and export _start
    final var start = module.addFunction(startType);
    module.exportFunction("_start", start);
    module.exportMemory("memory", 0);

    // Data: iovec at 0, message at 16
    final var iovec = new byte[8];
    iovec[0] = 16;
    iovec[4] = (byte) messageBytes.length;
    module.addData(0, iovec);
    module.addData(16, messageBytes);

    // Build _start: set global to 42, then print
    final var function = new WasmFunction(start, startType);
    function.emitI32Const(42);
    function.emitGlobalSet(0);

    // Print the message
    function.emitI32Const(1);
    function.emitI32Const(0);
    function.emitI32Const(1);
    function.emitI32Const(12);
    function.emitCall(fdWrite);
    function.emit(DROP);

    module.addCode(function.encode(module));

    final var output = execute(module.assemble());
    assertEquals("42", output);
  }

  @Test
  void stackValidatorRejectsUnderflow() {
    final var module = new WasmModule();
    final var startType = module.addType(new int[] {}, new int[] {});
    final var start = module.addFunction(startType);
    final var function = new WasmFunction(start, startType);
    function.emit(DROP);

    final var exception =
        assertThrows(CompilerException.class, () -> module.addCode(function.encode(module)));
    assertTrue(exception.getMessage().contains("stack underflow"));
  }

  @Test
  void stackValidatorRejectsValuesLeftOnStack() {
    final var module = new WasmModule();
    final var startType = module.addType(new int[] {}, new int[] {});
    final var start = module.addFunction(startType);
    final var function = new WasmFunction(start, startType);
    function.emitI32Const(1);

    final var exception =
        assertThrows(CompilerException.class, () -> module.addCode(function.encode(module)));
    assertTrue(exception.getMessage().contains("expected stack height 0 but found 1"));
  }
}
