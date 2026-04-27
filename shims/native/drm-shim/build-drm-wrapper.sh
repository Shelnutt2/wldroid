#!/usr/bin/env bash
set -euo pipefail

# libdrm SOVERSION — must match the versioned filename in the target rootfs.
# Passed from Gradle via LIBDRM_SOVERSION env var.
LIBDRM_SOVERSION="${LIBDRM_SOVERSION:-2.124.0}"

# Build a wrapper .so that combines our drm-shim (17 overridden functions)
# with the real libdrm (250+ other functions Mesa needs).
#
# Chromium uses dlopen("libdrm.so.2"), so LD_PRELOAD can't intercept it.
# The wrapper is placed at /opt/coder/lib/libdrm.so.2 (first in LD_LIBRARY_PATH)
# so Chromium picks it up. Its DT_NEEDED entries pull in both our shim (priority)
# and real libdrm (via standard library search path at runtime).
#
# Since we don't have the real libdrm on the host build machine,
# we create a minimal stub .so to satisfy the linker. At runtime the real
# Debian libdrm will be found via the standard library search path.

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../../.." && pwd)"
OUT="${1:-$PROJECT_ROOT/shims/build/outputs/native/drm-shim/libdrm-wrapper.so}"

SHIM_SO="$PROJECT_ROOT/shims/build/outputs/native/drm-shim/libdrm-shim.so"

if [ ! -f "$SHIM_SO" ]; then
    echo "ERROR: libdrm-shim.so not found at $SHIM_SO" >&2
    echo "Run build-drm-shim.sh first." >&2
    exit 1
fi

CC="${CROSS_CC:-aarch64-linux-gnu-gcc}"
if ! command -v "$CC" &>/dev/null; then
    echo "ERROR: Cross-compiler '$CC' not found." >&2
    echo "Install with: sudo apt-get install gcc-aarch64-linux-gnu" >&2
    exit 1
fi

STRIP="${CC/gcc/strip}"
if ! command -v "$STRIP" &>/dev/null; then
    STRIP="strip"
fi

mkdir -p "$(dirname "$OUT")"

# Create a temporary stub for libdrm.so.${LIBDRM_SOVERSION} to satisfy the linker.
STUB_DIR="$(mktemp -d)"
trap 'rm -rf "$STUB_DIR"' EXIT
echo "" | $CC -shared -fPIC -x c - \
    -o "$STUB_DIR/libdrm.so.${LIBDRM_SOVERSION}" \
    -Wl,-soname,libdrm.so.${LIBDRM_SOVERSION}

SHIM_DIR="$(dirname "$SHIM_SO")"

echo "==> Compiling drm-wrapper with $CC..."
echo "" | $CC -shared -fPIC -x c - \
    -x none \
    -o "$OUT" \
    -Wl,-soname,libdrm.so.2 \
    -Wl,--no-as-needed \
    -L"$SHIM_DIR" -l:libdrm-shim.so \
    -L"$STUB_DIR" -l:libdrm.so.${LIBDRM_SOVERSION}

$STRIP "$OUT" 2>/dev/null || true
echo "==> drm-wrapper built: $OUT ($(stat -c%s "$OUT" 2>/dev/null || stat -f%z "$OUT") bytes)"
