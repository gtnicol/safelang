package io.safelang.compiler;

import io.safelang.bytecode.BytecodeWriter;
import io.safelang.compiler.bytecode.BytecodeCompiler;
import java.util.List;

/** Compiles SAFE programs to bytecode artifacts. */
public final class BytecodeCompilerService implements SafeCompiler {

  @Override
  public SafeCompileResult compile(final CompileRequest request) throws Exception {
    final var compiler = new BytecodeCompiler();
    compiler.setRegistry(request.registry());
    final var module = compiler.compile(request.program());
    final var output = request.withExtension(".safeb");
    final var writer = new BytecodeWriter();
    writer.save(module, output.toString());
    return new SafeCompileResult(output, List.of(output));
  }
}
