package io.safelang;

import io.safelang.analyzer.SemanticException;
import io.safelang.ast.ASTPrinter;
import io.safelang.bytecode.*;
import io.safelang.compiler.CompilerException;
import io.safelang.compiler.CompilerFrontEnd;
import io.safelang.interpreter.InterpreterException;
import io.safelang.parser.ParserException;
import io.safelang.parser.SAFEParser;
import io.safelang.parser.generated.SAFEGrammarLexer;
import java.io.File;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import org.antlr.v4.runtime.Token;

/**
 * Main driver class for the SAFE programming language toolchain.
 *
 * <p>Usage: safe <command> <file> [options]
 *
 * <p>Strict mode (purity checking) is opt-in via {@code --strict} or {@code --deterministic}. The
 * CLI defaults to permissive because the IO builtins ({@code print}, {@code println}, etc.) are
 * themselves classified as impure — flipping the default would reject every "hello world" style
 * program. Strict mode is intended for the {@code compile} / {@code build} / {@code wasm} paths
 * where the user is producing an artifact whose purity they want to validate explicitly.
 */
public class SafeMain {

  public static void main(final String[] args) {
    // Scan for --strict / --deterministic / --native / --bytecode / --wasm / --module-path flags.
    var strict = false;
    var native_ = false;
    var bytecode_ = false;
    var wasm_ = false;
    var jvm_ = false;
    String allowSpec = null;
    String denySpec = null;
    String fsRoot = null;
    String netAllow = null;
    String execAllow = null;
    final var entries = new ArrayList<String>();
    final var filtered = new ArrayList<String>();
    for (var i = 0; i < args.length; i++) {
      final var arg = args[i];
      if (arg == null) continue;
      switch (arg) {
        case "--strict", "--deterministic":
          strict = true;
          break;
        case "--allow":
          if (i + 1 >= args.length) {
            System.err.println("Error: --allow requires an argument (e.g. fs,net,proc)");
            System.exit(1);
          }
          allowSpec = args[++i];
          break;
        case "--deny":
          if (i + 1 >= args.length) {
            System.err.println("Error: --deny requires an argument (e.g. net,proc)");
            System.exit(1);
          }
          denySpec = args[++i];
          break;
        case "--fs-root":
          if (i + 1 >= args.length) {
            System.err.println("Error: --fs-root requires a directory argument");
            System.exit(1);
          }
          fsRoot = args[++i];
          break;
        case "--net-allow":
          if (i + 1 >= args.length) {
            System.err.println("Error: --net-allow requires a host/CIDR list argument");
            System.exit(1);
          }
          netAllow = args[++i];
          break;
        case "--exec-allow":
          if (i + 1 >= args.length) {
            System.err.println("Error: --exec-allow requires a command list argument");
            System.exit(1);
          }
          execAllow = args[++i];
          break;
        case "--native":
          native_ = true;
          break;
        case "--bytecode":
          bytecode_ = true;
          break;
        case "--wasm":
          wasm_ = true;
          break;
        case "--jvm":
          jvm_ = true;
          break;
        case "--module-path":
          if (i + 1 >= args.length) {
            System.err.println("Error: --module-path requires an argument");
            System.exit(1);
          }
          entries.add(args[++i]);
          break;
        default:
          if (arg.startsWith("--module-path=")) {
            entries.add(arg.substring("--module-path=".length()));
          } else {
            filtered.add(arg);
          }
      }
    }
    // SAFE_MODULE_PATH environment variable supplies additional entries
    // appended after any explicit --module-path flags. The flag wins over
    // the env var (it's listed first in the search order).
    final var envPath = System.getenv("SAFE_MODULE_PATH");
    if (envPath != null && !envPath.isBlank()) {
      entries.add(envPath);
    }
    final var modulePath = new ArrayList<Path>();
    for (final var entry : entries) {
      for (final var part : entry.split(File.pathSeparator)) {
        if (!part.isBlank()) {
          modulePath.add(Path.of(part));
        }
      }
    }

    // Host capability policy. The CLI runs trusted local code, so it grants all by default;
    // --allow restricts to exactly the listed set, --deny removes from the full set.
    var capabilities = io.safelang.runtime.Capabilities.all();
    try {
      if (allowSpec != null) {
        capabilities = io.safelang.runtime.Capabilities.parse(allowSpec);
      }
      if (denySpec != null) {
        for (final var token : denySpec.split(",")) {
          if (!token.isBlank()) {
            capabilities = capabilities.without(io.safelang.runtime.Capability.parse(token));
          }
        }
      }
    } catch (final IllegalArgumentException badCapability) {
      System.err.println("Error: " + badCapability.getMessage());
      System.exit(1);
    }

    // The richer host policy (filesystem jail, egress allowlist, exec allowlist) is enforced on the
    // interpreter/VM run paths; the compile commands only consult policy.capabilities().
    final var policyBuilder =
        io.safelang.runtime.HostPolicy.trusted().toBuilder().capabilities(capabilities);
    if (fsRoot != null) {
      policyBuilder.fsRoot(Path.of(fsRoot));
    }
    if (netAllow != null) {
      policyBuilder.netAllow(List.of(netAllow.split(",")));
    }
    if (execAllow != null) {
      policyBuilder.execAllow(List.of(execAllow.split(",")));
    }
    final var policy = policyBuilder.build();

    if (filtered.size() < 2) {
      printUsage();
      System.exit(1);
    }

    final var command = filtered.get(0);
    final var filename = filtered.get(1);

    // Collect extra args after the filename as program arguments
    final var arguments = new ArrayList<String>();
    for (int i = 2; i < filtered.size(); i++) {
      arguments.add(filtered.get(i));
    }

    try {
      switch (command) {
        case "run":
          run(read(filename), filename, arguments, strict, modulePath, policy);
          break;
        case "compile":
          compile(read(filename), filename, strict, modulePath, capabilities);
          break;
        case "build":
          build(read(filename), filename, strict, modulePath, capabilities);
          break;
        case "bytecode":
          bytecode(read(filename), filename, strict, modulePath, capabilities);
          break;
        case "vm":
          vm(filename, arguments, policy);
          break;
        case "disassemble":
          disassemble(filename);
          break;
        case "assemble":
          assemble(read(filename), filename);
          break;
        case "tokens":
          tokens(read(filename));
          break;
        case "ast":
          ast(read(filename), filename, strict, modulePath);
          break;
        case "wasm":
          wasm(read(filename), filename, strict, modulePath, capabilities);
          break;
        case "jvm":
          jvm(read(filename), filename, strict, modulePath, capabilities);
          break;
        case "test":
          final var runner = new TestRunner(strict, native_, bytecode_, wasm_, jvm_);
          System.exit(runner.execute(filename));
          break;
        default:
          System.err.println("Unknown command: " + command);
          printUsage();
          System.exit(1);
      }
    } catch (io.safelang.interpreter.ExitException e) {
      System.exit(e.code());
    } catch (StackOverflowError e) {
      System.err.println("Error: Stack overflow (possible infinite recursion)");
      System.exit(1);
    } catch (OutOfMemoryError e) {
      System.err.println("Error: Out of memory");
      System.exit(1);
    } catch (Exception e) {
      System.err.println("Error: " + e.getMessage());
      if (e.getMessage() == null || e.getMessage().isEmpty()) {
        e.printStackTrace();
      }
      System.exit(1);
    }
  }

  static CompilerFrontEnd.ParseResult parse(
      final String source, final String filename, final boolean strict) {
    return SafeRuntime.parse(source, filename, strict);
  }

  static CompilerFrontEnd.ParseResult parse(
      final String source,
      final String filename,
      final boolean strict,
      final List<Path> modulePath) {
    return SafeRuntime.parse(source, filename, strict, modulePath);
  }

  private static void run(
      final String source,
      final String filename,
      final List<String> arguments,
      final boolean strict,
      final List<Path> modulePath,
      final io.safelang.runtime.HostPolicy policy) {
    try {
      SafeRuntime.run(source, filename, arguments, strict, modulePath, policy);
    } catch (Exception e) {
      error("Interpreter", e);
    }
  }

  private static void compile(
      final String source,
      final String filename,
      final boolean strict,
      final List<Path> modulePath,
      final io.safelang.runtime.Capabilities capabilities) {
    try {
      final var result = SafeRuntime.compile(source, filename, strict, modulePath, capabilities);
      System.out.println("Compiled to: " + result.output());
    } catch (Exception e) {
      error("Compiler", e);
    }
  }

  private static void build(
      final String source,
      final String filename,
      final boolean strict,
      final List<Path> modulePath,
      final io.safelang.runtime.Capabilities capabilities) {
    try {
      final var binary = SafeRuntime.build(source, filename, strict, modulePath, capabilities);
      System.out.println("Built: " + binary);
    } catch (Exception e) {
      error("Build", e);
    }
  }

  private static void bytecode(
      final String source,
      final String filename,
      final boolean strict,
      final List<Path> modulePath,
      final io.safelang.runtime.Capabilities capabilities) {
    try {
      final var result = SafeRuntime.bytecode(source, filename, strict, modulePath, capabilities);
      System.out.println("Compiled bytecode to: " + result.output());
    } catch (Exception e) {
      error("Bytecode Compiler", e);
    }
  }

  private static void vm(
      final String filename, final List<String> args, final io.safelang.runtime.HostPolicy policy) {
    try {
      SafeRuntime.vm(filename, args, policy);
    } catch (Exception e) {
      error("VM", e);
    }
  }

  private static void disassemble(final String filename) {
    try {
      final var reader = new BytecodeReader();
      final var module = reader.load(filename);
      final var decompiler = new Decompiler();
      final var assembly = decompiler.decompile(module);
      System.out.print(assembly);
    } catch (Exception e) {
      error("Decompiler", e);
    }
  }

  private static void assemble(final String source, final String filename) {
    try {
      final var assembler = new Assembler();
      final var module = assembler.assemble(source);
      final var output = filename.replaceAll("\\.safea$", ".safeb");
      final var writer = new BytecodeWriter();
      writer.save(module, output);
      System.out.println("Assembled to: " + output);
    } catch (Exception e) {
      error("Assembler", e);
    }
  }

  private static void tokens(final String source) {
    try {
      final var tokens = SAFEParser.tokenize(source);
      final var vocabulary = SAFEGrammarLexer.VOCABULARY;
      System.out.println("=== TOKENS ===");
      for (final var token : tokens.getTokens()) {
        if (token.getType() == Token.EOF) continue;
        if (token.getChannel() != Token.DEFAULT_CHANNEL) continue;
        final var name = vocabulary.getSymbolicName(token.getType());
        final var display = name != null ? name : vocabulary.getDisplayName(token.getType());
        System.out.printf(
            "%s: %s (line %d, col %d)%n",
            display, token.getText(), token.getLine(), token.getCharPositionInLine());
      }
    } catch (Exception e) {
      error("Lexer", e);
    }
  }

  private static void ast(
      final String source,
      final String filename,
      final boolean strict,
      final List<Path> modulePath) {
    try {
      final var parsed = parse(source, filename, strict, modulePath);
      for (final var warning : parsed.warnings()) {
        System.err.println("WARNING: " + warning);
      }
      System.out.println("=== AST ===");
      final var printer = new ASTPrinter();
      final var result = parsed.program().accept(printer);
      System.out.println(result);
    } catch (Exception e) {
      error("Parser", e);
    }
  }

  private static void error(final String phase, final Exception exception) {
    final var label =
        switch (exception) {
          case ParserException ignored -> "Parser";
          case SemanticException ignored -> "Semantic";
          case BytecodeException ignored -> "Bytecode";
          case CompilerException ignored -> "Compiler";
          case InterpreterException ignored -> "Interpreter";
          case ModuleException ignored -> "Module";
          case SAFEException ignored -> "SAFE";
          case null, default -> phase;
        };
    assert exception != null;
    System.err.println(label + " Error: " + exception.getMessage());
    System.exit(1);
  }

  private static void wasm(
      final String source,
      final String filename,
      final boolean strict,
      final List<Path> modulePath,
      final io.safelang.runtime.Capabilities capabilities) {
    try {
      final var result = SafeRuntime.wasm(source, filename, strict, modulePath, capabilities);
      System.out.println("Compiled to: " + result.output());
      result.runInstruction().ifPresent(command -> System.out.println("Run with: " + command));
    } catch (Exception exception) {
      error("Wasm Compiler", exception);
    }
  }

  private static void jvm(
      final String source,
      final String filename,
      final boolean strict,
      final List<Path> modulePath,
      final io.safelang.runtime.Capabilities capabilities) {
    try {
      final var result = SafeRuntime.jvm(source, filename, strict, modulePath, capabilities);
      System.out.println("Compiled to: " + result.output());
      result.runInstruction().ifPresent(command -> System.out.println("Run with: " + command));
    } catch (Exception exception) {
      error("JVM Compiler", exception);
    }
  }

  public static void extractWasmBuiltins(final Path directory) throws IOException {
    final var target =
        (directory != null ? directory : Path.of(".")).resolve("safe_wasm_builtins.wasm");
    try (var stream = SafeMain.class.getResourceAsStream("/safe_wasm_builtins.wasm")) {
      if (stream != null) {
        Files.write(target, stream.readAllBytes());
      }
    }
  }

  public static void extractRuntime(final Path directory) throws IOException {
    final var base = directory != null ? directory : Path.of(".");
    for (final var name : new String[] {"safe_runtime.h", "safe_refcount.h"}) {
      final var target = base.resolve(name);
      try (var stream = SafeMain.class.getResourceAsStream("/" + name)) {
        if (stream != null) {
          Files.write(target, stream.readAllBytes());
        }
      }
    }
  }

  private static String read(final String filename) {
    return SafeRuntime.read(filename);
  }

  private static void printUsage() {
    System.err.print(
        """
                Usage: safe <command> <file> [options]
                Commands:
                  run            Interpret and execute a SAFE program
                  compile        Compile a SAFE program to C code (.c file)
                  build          Compile to C and invoke gcc for native binary
                  wasm           Compile to WebAssembly (.wasm file)
                  jvm            Compile to a self-contained executable JVM jar (.jar)
                  bytecode       Compile a SAFE program to bytecode (.safeb file)
                  vm             Execute a compiled bytecode file (.safeb)
                  disassemble    Decompile bytecode to assembly text
                  assemble       Assemble bytecode from assembly text (.safea)
                  tokens         Show lexer output (debugging)
                  ast            Show AST structure (debugging)
                  test           Run SAFE test files (.safe) with test assertions
                Options:
                  --strict           Enable strict mode (purity checking — rejects calls
                                     to impure builtins like time, rand, file:, IO)
                  --deterministic    Alias for --strict
                  --native           Use C backend for test execution
                  --wasm             Use WebAssembly backend for test execution
                  --jvm              Use the JVM bytecode backend for test execution
                  --bytecode         Use bytecode VM for test execution
                  --module-path DIR  Extra directory to search for module imports.
                                     May be repeated, or use the system path separator
                                     (':' on Unix, ';' on Windows). The SAFE_MODULE_PATH
                                     environment variable supplies additional entries
                                     after any explicit --module-path flags.
                """);
  }
}
