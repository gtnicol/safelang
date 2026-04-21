package io.safelang.compiler.wasm;

/**
 * Instance-owned runtime state for one WASM compilation.
 *
 * <p>Replaces the static mutable fields in {@link WasmRuntime}. Each call to the WASM code
 * generator creates a fresh context — no {@code reset()} needed, reentrant, thread-safe by
 * construction.
 *
 * <p>Tag constants and memory layout constants remain on {@link WasmRuntime} as static finals (they
 * never change). This class holds the per-compilation function indices and global indices that are
 * assigned during module setup.
 */
public final class WasmRuntimeContext {

  // Global indices
  public int heapPointer = -1;

  // Core runtime function indices
  public int alloc = -1;
  public int tag = -1;
  public int untagInt = -1;
  public int untagPointer = -1;
  public int untagFloat = -1;
  public int tagInt = -1;
  public int tagFloat = -1;
  public int tagBool = -1;
  public int tagString = -1;
  public int tagVoid = -1;

  // Print functions
  public int printRaw = -1;
  public int printTagged = -1;
  public int printlnTagged = -1;

  // Contract failure trap (writes a SAFE string to stderr + proc_exit(1))
  public int trapWithMessage = -1;

  // String functions
  public int strConcat = -1;
  public int strEqual = -1;
  public int strLength = -1;
  public int strFromInt = -1;
  public int strFromFloat = -1;
  public int strFromBool = -1;
  public int toString = -1;

  // String data offsets
  public int trueOffset = 0;
  public int falseOffset = 0;

  // Comparison
  public int valuesEqual = -1;

  // Refcounting (tag-dispatched, safe on any tagged i64)
  public int rcAlloc = -1;
  public int retainTagged = -1;
  public int releaseTagged = -1;

  // List functions
  public int listNew = -1;
  public int listAppend = -1;
  public int listGet = -1;
  public int listSet = -1;
  public int listLength = -1;
  public int listRemoveAt = -1;
  public int listSlice = -1;
  public int listConcat = -1;
  public int listReverse = -1;

  // Map functions
  public int mapNew = -1;
  public int mapPut = -1;
  public int mapGet = -1;
  public int mapLength = -1;
  public int mapContains = -1;
  public int mapKeys = -1;
  public int mapValues = -1;
  public int mapRemove = -1;

  // WASI imports
  public int wasiWrite = -1;
  public int wasiRead = -1;
  public int wasiExit = -1;
  public int wasiClockTimeGet = -1;
  public int wasiRandomGet = -1;
  public int wasiArgsGet = -1;
  public int wasiArgsSizesGet = -1;
  public int wasiEnvironGet = -1;
  public int wasiEnvironSizesGet = -1;
}
