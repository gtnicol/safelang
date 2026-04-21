package io.safelang.bytecode;

import java.util.*;

/** Metadata for an enum type declaration. */
public record EnumInfo(String name, int index, List<VariantInfo> variants) {

  public EnumInfo {
    variants = List.copyOf(variants);
  }

  /** Find variant index by name, or -1 */
  public int getVariantIndex(final String variant) {
    for (int i = 0; i < variants.size(); i++) {
      if (variants.get(i).name().equals(variant)) return i;
    }
    return -1;
  }

  public record VariantInfo(String name, int index, List<Integer> tags) {

    public boolean hasFields() {
      return !tags.isEmpty();
    }
  }
}
