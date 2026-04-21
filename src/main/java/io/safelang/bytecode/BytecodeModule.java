package io.safelang.bytecode;

import java.util.*;

/**
 * In-memory representation of a compiled SAFE program. Contains all constants, metadata, and
 * bytecode needed for execution. This is the "shaded JAR" equivalent — a self-contained bytecode
 * package.
 */
public class BytecodeModule {

  public static final byte[] MAGIC = {0x53, 0x41, 0x46, 0x45}; // "SAFE"
  public static final int VERSION = 2;
  private final List<TypeDefinition> types;
  private final List<EnumInfo> enums;
  private final List<FunctionDefinition> functions;
  private final List<GlobalVarInfo> globals;
  private ConstantPool pool;
  private byte[] main;
  private int locals = 256;

  // Cache: variant name → owning enum. Lazily built on first lookup to keep
  // construction O(1); invalidated whenever the enum list changes via add().
  private Map<String, EnumInfo> variantOwners;

  public BytecodeModule() {
    this.pool = new ConstantPool();
    this.types = new ArrayList<>();
    this.enums = new ArrayList<>();
    this.functions = new ArrayList<>();
    this.globals = new ArrayList<>();
    this.main = new byte[0];
  }

  // Constant pool
  public ConstantPool pool() {
    return pool;
  }

  public void setPool(final ConstantPool pool) {
    this.pool = pool;
  }

  // Types
  public List<TypeDefinition> types() {
    return Collections.unmodifiableList(types);
  }

  public void add(final TypeDefinition type) {
    types.add(type);
  }

  public TypeDefinition type(final int index) {
    return types.get(index);
  }

  /** Find type index by name, returns -1 if not found */
  public int type(final String name) {
    for (int i = 0; i < types.size(); i++) {
      if (types.get(i).name().equals(name)) return i;
    }
    return -1;
  }

  // Enums
  public List<EnumInfo> enums() {
    return Collections.unmodifiableList(enums);
  }

  public void add(final EnumInfo enumInfo) {
    enums.add(enumInfo);
    variantOwners = null; // invalidate cache
  }

  public EnumInfo enumeration(final int index) {
    return enums.get(index);
  }

  /** Find enum index by name, returns -1 if not found */
  public int enumeration(final String name) {
    for (int i = 0; i < enums.size(); i++) {
      if (enums.get(i).name().equals(name)) return i;
    }
    return -1;
  }

  /**
   * Find the enum that contains a variant with the given name. Returns the first match if multiple
   * enums share the same variant name; the cache is built lazily on first call and invalidated
   * whenever a new enum is added.
   */
  public EnumInfo variant(final String variant) {
    if (variantOwners == null) {
      variantOwners = new HashMap<>();
      for (final var enumeration : enums) {
        for (final var info : enumeration.variants()) {
          variantOwners.putIfAbsent(info.name(), enumeration);
        }
      }
    }
    return variantOwners.get(variant);
  }

  // Functions
  public List<FunctionDefinition> functions() {
    return Collections.unmodifiableList(functions);
  }

  public void add(final FunctionDefinition function) {
    functions.add(function);
  }

  /** Reserve a slot for a function to be filled in later. */
  public int reserve() {
    final var index = functions.size();
    functions.add(null);
    return index;
  }

  /** Set a previously reserved function slot. */
  public void setFunction(final int index, final FunctionDefinition function) {
    functions.set(index, function);
  }

  public FunctionDefinition function(final int index) {
    return functions.get(index);
  }

  /** Find function index by name, returns -1 if not found */
  public int function(final String name) {
    for (int i = 0; i < functions.size(); i++) {
      final var f = functions.get(i);
      if (f != null && f.name().equals(name)) return i;
    }
    return -1;
  }

  // Globals
  public List<GlobalVarInfo> globals() {
    return Collections.unmodifiableList(globals);
  }

  public void add(final GlobalVarInfo global) {
    globals.add(global);
  }

  // Main bytecode
  public byte[] main() {
    return main;
  }

  public void setMain(final byte[] bytecode) {
    this.main = bytecode;
  }

  public int locals() {
    return locals;
  }

  public void setLocals(final int count) {
    this.locals = count;
  }

  /** Simple global variable info */
  public record GlobalVarInfo(String name, int index, boolean isConst) {}
}
