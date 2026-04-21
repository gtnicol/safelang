package io.safelang.compiler;

/** Performs backend-specific compilation of a parsed SAFE program. */
public interface SafeCompiler {

  /** Compile the given request and return the compilation artifacts. */
  SafeCompileResult compile(CompileRequest request) throws Exception;
}
