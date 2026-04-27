#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../../.." && pwd)"
OUT="${1:-$PROJECT_ROOT/shims/build/outputs/native/netstub/libnetstub.so}"

SRC="$SCRIPT_DIR/netstub.c"
CFLAGS="-shared -fPIC -O2 -DNDEBUG"
LDFLAGS=""

# Docker fallback
if [ "${NETSTUB_DOCKER:-false}" = "true" ]; then
    echo "==> Building netstub in Docker..."
    mkdir -p "$(dirname "$OUT")"
    docker run --rm \
        -v "$SCRIPT_DIR:/src:ro" \
        -v "$(dirname "$OUT"):/out" \
        debian:bookworm-slim \
        bash -c "
            apt-get update -qq && apt-get install -y --no-install-recommends gcc-aarch64-linux-gnu >/dev/null 2>&1
            aarch64-linux-gnu-gcc $CFLAGS -o /out/$(basename "$OUT") /src/netstub.c $LDFLAGS
            aarch64-linux-gnu-strip /out/$(basename "$OUT")
        "
    echo "==> netstub built (Docker): $OUT"
    exit 0
fi

# Native cross-compiler
CC="${CROSS_CC:-aarch64-linux-gnu-gcc}"
if ! command -v "$CC" &>/dev/null; then
    echo "ERROR: Cross-compiler '$CC' not found." >&2
    echo "Install with: sudo apt-get install gcc-aarch64-linux-gnu" >&2
    echo "Or set NETSTUB_DOCKER=true to build in Docker." >&2
    exit 1
fi

STRIP="${CC/gcc/strip}"
if ! command -v "$STRIP" &>/dev/null; then
    STRIP=""
fi

mkdir -p "$(dirname "$OUT")"
echo "==> Compiling netstub with $CC..."
$CC $CFLAGS -o "$OUT" "$SRC" $LDFLAGS
if [[ -n "$STRIP" ]]; then
    $STRIP "$OUT"
fi
echo "==> netstub built: $OUT ($(stat -c%s "$OUT" 2>/dev/null || stat -f%z "$OUT") bytes)"
