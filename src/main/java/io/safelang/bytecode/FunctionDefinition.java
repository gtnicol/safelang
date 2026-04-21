package io.safelang.bytecode;

import java.util.Arrays;
import java.util.Objects;

/** Metadata and bytecode for a compiled function. */
public record FunctionDefinition(
    String name,
    int index,
    int parameters,
    int locals,
    byte[] bytecode,
    byte[] requires,
    byte[] ensures,
    byte[] decreases) {

  public boolean hasRequires() {
    return requires != null;
  }

  public boolean hasEnsures() {
    return ensures != null;
  }

  public boolean hasDecreases() {
    return decreases != null;
  }

  @Override
  public boolean equals(final Object o) {
    if (this == o) return true;
    if (!(o
        instanceof
        FunctionDefinition(
            String name1,
            int index1,
            int parameters1,
            int locals1,
            byte[] bytecode1,
            byte[] requires1,
            byte[] ensures1,
            byte[] decreases1))) return false;
    return index == index1
        && parameters == parameters1
        && locals == locals1
        && Objects.equals(name, name1)
        && Arrays.equals(bytecode, bytecode1)
        && Arrays.equals(requires, requires1)
        && Arrays.equals(ensures, ensures1)
        && Arrays.equals(decreases, decreases1);
  }

  @Override
  public int hashCode() {
    int result = Objects.hash(name, index, parameters, locals);
    result = 31 * result + Arrays.hashCode(bytecode);
    result = 31 * result + Arrays.hashCode(requires);
    result = 31 * result + Arrays.hashCode(ensures);
    result = 31 * result + Arrays.hashCode(decreases);
    return result;
  }
}
