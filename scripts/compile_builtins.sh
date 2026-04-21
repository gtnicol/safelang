#!/bin/sh
# Compiles safe_wasm_builtins.c to safe_wasm_builtins.wasm
# Uses clang with wasm32-wasi target. Tries common locations in order.

set -e

SRC="src/main/resources/safe_wasm_builtins.c"
OUT="src/main/resources/safe_wasm_builtins.wasm"

WASI_SYSROOT=""
for dir in \
    /opt/homebrew/share/wasi-sysroot \
    /usr/share/wasi-sysroot \
    /opt/wasi-sdk/share/wasi-sysroot \
    "$HOME/wasi-sdk/share/wasi-sysroot"; do
  if [ -d "$dir" ]; then
    WASI_SYSROOT="$dir"
    break
  fi
done

if [ -z "$WASI_SYSROOT" ]; then
  echo "ERROR: wasi-sysroot not found. Install wasi-sysroot: brew install wasi-sysroot" >&2
  exit 1
fi

# Ensure wasm32 clang_rt placeholder exists (homebrew llvm may lack it)
LLVM_CLANG=""
for dir in \
    /opt/homebrew/opt/llvm/bin \
    /usr/local/opt/llvm/bin \
    /usr/lib/llvm-*/bin; do
  if [ -x "$dir/clang" ]; then
    LLVM_CLANG="$dir/clang"
    break
  fi
done

# If not found, try cc/gcc (may work on Linux where gcc supports wasm32)
if [ -z "$LLVM_CLANG" ]; then
  for bin in gcc clang cc; do
    if command -v "$bin" >/dev/null 2>&1; then
      if "$bin" --target=wasm32-wasip1 -x c /dev/null -o /dev/null 2>/dev/null; then
        LLVM_CLANG="$bin"
        break
      fi
    fi
  done
fi

if [ -z "$LLVM_CLANG" ]; then
  echo "ERROR: no wasm32-capable compiler found. Install: brew install llvm" >&2
  exit 1
fi

# Ensure libclang_rt.builtins.a stub exists (our code doesn't need it, but
# the linker looks for it). Create in a temp directory rather than modifying
# the system LLVM installation.
RT_STUB_DIR=$(mktemp -d)
trap 'rm -rf "$RT_STUB_DIR"' EXIT
CLANG_VERSION=$("$LLVM_CLANG" --version 2>&1 | grep -o 'version [0-9]*' | head -1 | awk '{print $2}')
EXTRA_LINK_FLAGS=""
if [ -n "$CLANG_VERSION" ]; then
  RT_SYS_DIR=$(dirname "$(dirname "$LLVM_CLANG")")/lib/clang/$CLANG_VERSION/lib/wasm32-unknown-wasip1
  if [ ! -f "$RT_SYS_DIR/libclang_rt.builtins.a" ]; then
    mkdir -p "$RT_STUB_DIR/lib"
    AR_BIN=$(dirname "$LLVM_CLANG")/llvm-ar
    [ -x "$AR_BIN" ] || AR_BIN=ar
    "$AR_BIN" rc "$RT_STUB_DIR/lib/libclang_rt.builtins.a" 2>/dev/null || true
    "$AR_BIN" rc "$RT_STUB_DIR/lib/libclang_rt.builtins-wasm32.a" 2>/dev/null || true
    EXTRA_LINK_FLAGS="-Wl,-L,$RT_STUB_DIR/lib"
  fi
fi

echo "Compiling $SRC -> $OUT using $LLVM_CLANG"

"$LLVM_CLANG" --target=wasm32-wasip1 \
  --sysroot="$WASI_SYSROOT" \
  -O2 -nostartfiles -nodefaultlibs \
  -Wall -Wextra -Werror \
  $EXTRA_LINK_FLAGS \
  -Wl,--no-entry \
  -Wl,--export-dynamic \
  -Wl,--allow-undefined \
  -Wl,--stack-first \
  -Wl,-z,stack-size=65536 \
  -Wl,--global-base=1048576 \
  -Wl,--initial-memory=67108864 \
  -o "$OUT" \
  "$SRC" \
  -lc

echo "Done: $OUT"
