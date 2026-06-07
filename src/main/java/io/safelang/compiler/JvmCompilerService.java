package io.safelang.compiler;

import io.safelang.compiler.jvm.JvmBackend;
import java.util.List;

/** Compiles SAFE programs to a self-contained executable JVM jar. */
public final class JvmCompilerService implements SafeCompiler {

  @Override
  public SafeCompileResult compile(final CompileRequest request) throws Exception {
    final var output = request.withExtension(".jar");
    final var main =
        JvmBackend.emit(request.program(), request.registry(), request.baseName(), output);
    final var run = "java -jar " + output;
    return new SafeCompileResult(output, List.of(output), run);
  }
}
