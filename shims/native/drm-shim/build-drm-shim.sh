#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../../.." && pwd)"
OUT="${1:-$PROJECT_ROOT/shims/build/outputs/native/drm-shim/libdrm-shim.so}"

SRC="$SCRIPT_DIR/src/drm_shim.c"
INCLUDES="-I$SCRIPT_DIR/include -I$SCRIPT_DIR/vendor-include -I$SCRIPT_DIR/vendor-include/libdrm"
CFLAGS="-shared -fPIC -O2 -Wall -Wextra -Wno-unused-parameter -DNDEBUG"
LDFLAGS="-ldl -lpthread"

# Docker fallback
if [ "${DRM_SHIM_DOCKER:-false}" = "true" ]; then
    echo "==> Building drm-shim in Docker..."
    mkdir -p "$(dirname "$OUT")"
    docker run --rm \
        -v "$SCRIPT_DIR:/src:ro" \
        -v "$(dirname "$OUT"):/out" \
        debian:bookworm-slim \
        bash -c "
            apt-get update -qq && apt-get install -y --no-install-recommends gcc-aarch64-linux-gnu >/dev/null 2>&1
            aarch64-linux-gnu-gcc $CFLAGS -o /out/$(basename "$OUT") /src/src/drm_shim.c \
                -I/src/include -I/src/vendor-include -I/src/vendor-include/libdrm $LDFLAGS
            aarch64-linux-gnu-strip /out/$(basename "$OUT")
        "
    echo "==> drm-shim built (Docker): $OUT"
    exit 0
fi

# Native cross-compiler
CC="${CROSS_CC:-aarch64-linux-gnu-gcc}"
if ! command -v "$CC" &>/dev/null; then
    echo "ERROR: Cross-compiler '$CC' not found." >&2
    echo "Install with: sudo apt-get install gcc-aarch64-linux-gnu" >&2
    echo "Or set DRM_SHIM_DOCKER=true to build in Docker." >&2
    exit 1
fi

STRIP="${CC/gcc/strip}"
if ! command -v "$STRIP" &>/dev/null; then
    STRIP=""
fi

mkdir -p "$(dirname "$OUT")"
echo "==> Compiling drm-shim with $CC..."
$CC $CFLAGS -o "$OUT" "$SRC" $INCLUDES $LDFLAGS
if [[ -n "$STRIP" ]]; then
    $STRIP "$OUT"
fi
echo "==> drm-shim built: $OUT ($(stat -c%s "$OUT" 2>/dev/null || stat -f%z "$OUT") bytes)"
