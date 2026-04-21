package io.safelang.compiler.wasm;

record WasmBuiltinSupport(
    int stringify,
    int stringLength,
    int stringConcat,
    int listCreate,
    int listLength,
    int listGet,
    int listAppend,
    int listRemoveAt,
    int listSlice,
    int listReverse,
    int mapLength,
    int mapContains,
    int mapKeys,
    int mapValues) {}
