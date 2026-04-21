package io.safelang.runtime;

import java.util.LinkedHashMap;
import java.util.Map;

public final class BuiltinExecutors {

  private final Map<String, BuiltinFunction> executors = new LinkedHashMap<>();

  public void register(final String name, final BuiltinFunction function) {
    executors.put(name, function);
  }

  public BuiltinFunction get(final String name) {
    return executors.get(name);
  }
}
