#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../../.." && pwd)"
SRC="$SCRIPT_DIR/egl_override.c"
OUT="${1:-$PROJECT_ROOT/shims/build/outputs/native/egl-override/libegl_override.so}"

CC="${CC:-aarch64-linux-gnu-gcc}"
STRIP="${STRIP:-aarch64-linux-gnu-strip}"

mkdir -p "$(dirname "$OUT")"

echo "[build-egl-override] Compiling $SRC -> $OUT"
$CC -shared -fPIC -O2 -Wall -Wextra \
    -Wno-unused-parameter \
    -Wl,-soname,libEGL.so.1 \
    -o "$OUT" "$SRC" -ldl

# Strip if cross-strip is available
if command -v "$STRIP" &>/dev/null; then
    echo "[build-egl-override] Stripping $OUT"
    $STRIP --strip-unneeded "$OUT"
else
    echo "[build-egl-override] Cross-strip not available, skipping"
fi

echo "[build-egl-override] Done: $(ls -lh "$OUT" | awk '{print $5}')"
