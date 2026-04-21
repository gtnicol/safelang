package io.safelang.compiler.c;

import io.safelang.SafeMain;
import io.safelang.compiler.CompileRequest;
import io.safelang.compiler.SafeCompileResult;
import io.safelang.compiler.SafeCompiler;
import java.nio.file.Files;
import java.util.List;

/** Compiles SAFE programs to C source files. */
public final class CCompiler implements SafeCompiler {

  @Override
  public SafeCompileResult compile(final CompileRequest request) throws Exception {
    final var generator = new CCodeGenerator();
    generator.setRegistry(request.registry());
    final var code = generator.generate(request.program());
    final var output = request.withExtension(".c");
    Files.writeString(output, code);
    SafeMain.extractRuntime(output.getParent());
    return new SafeCompileResult(output, List.of(output));
  }
}
