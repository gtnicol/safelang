package io.safelang.compiler.wasm;

import java.util.List;

record WasmLambdaPlan(List<String> captures, int index, int table) {}
