#!/usr/bin/env bash
set -euo pipefail

# Build all shim libraries for Android arm64.
# This script is intended to run inside the Docker container or on a host
# with aarch64-linux-gnu-gcc available.

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
NATIVE_DIR="$PROJECT_ROOT/shims/native"
OUTPUT_DIR="${OUTPUT_DIR:-$PROJECT_ROOT/shims/build/outputs/native}"

echo "============================================"
echo "  WLDroid Shim Libraries — Cross Build"
echo "============================================"
echo "Project root: $PROJECT_ROOT"
echo "Output dir:   $OUTPUT_DIR"
echo ""

# 1. DRM Shim
echo "--- [1/5] Building drm-shim ---"
bash "$NATIVE_DIR/drm-shim/build-drm-shim.sh" "$OUTPUT_DIR/drm-shim/libdrm-shim.so"

# 2. DRM Wrapper (depends on drm-shim)
echo ""
echo "--- [2/5] Building drm-wrapper ---"
bash "$NATIVE_DIR/drm-shim/build-drm-wrapper.sh" "$OUTPUT_DIR/drm-shim/libdrm-wrapper.so"

# 3. GBM Shim
echo ""
echo "--- [3/5] Building gbm-shim ---"
bash "$NATIVE_DIR/gbm-shim/build-gbm-shim.sh" "$OUTPUT_DIR/gbm-shim/libgbm.so.1"

# 4. EGL Override
echo ""
echo "--- [4/5] Building egl-override ---"
bash "$NATIVE_DIR/egl-override/build-egl-override.sh" "$OUTPUT_DIR/egl-override/libegl_override.so"

# 5. Netstub
echo ""
echo "--- [5/5] Building netstub ---"
bash "$NATIVE_DIR/netstub/build-netstub.sh" "$OUTPUT_DIR/netstub/libnetstub.so"

echo ""
echo "============================================"
echo "  All shim libraries built successfully!"
echo "============================================"
echo ""
echo "Output files:"
find "$OUTPUT_DIR" -name "*.so*" -exec ls -lh {} \;
