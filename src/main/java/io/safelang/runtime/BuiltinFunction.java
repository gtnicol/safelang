package io.safelang.runtime;

import java.util.List;

@FunctionalInterface
public interface BuiltinFunction {
  SAFEValue execute(List<SAFEValue> args);
}
