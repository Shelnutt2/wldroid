#!/bin/bash
# build.sh — Build the WLDroid Wayland compositor for Android arm64.
#
# Dependencies are managed as git submodules in external/ at the project root.
# Meson resolves them via --subproject-dir pointing to external/.
#
# Usage:
#   bash compositor/native/scripts/build.sh           # incremental build
#   bash compositor/native/scripts/build.sh --clean   # wipe build dir
#
# Gradle calls this via the buildCompositor task.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
NATIVE_DIR="$SCRIPT_DIR/.."
# Project root is three levels up: compositor/native/scripts/ -> project root
PROJECT_ROOT="$NATIVE_DIR/../../.."
EXTERNAL_DIR="$PROJECT_ROOT/external"
JNILIBS_DIR="$PROJECT_ROOT/compositor/src/main/jniLibs/arm64-v8a"
BUILD_DIR="$NATIVE_DIR/builddir"

# ── Locate Android NDK ──
# Prefer ANDROID_NDK_HOME, then ANDROID_NDK, then a well-known SDK location.
if [ -n "${ANDROID_NDK_HOME:-}" ]; then
    NDK="$ANDROID_NDK_HOME"
elif [ -n "${ANDROID_NDK:-}" ]; then
    NDK="$ANDROID_NDK"
else
    # Search for the highest NDK version in the SDK
    SDK_ROOT="${ANDROID_SDK_ROOT:-/opt/android-sdk-update-manager}"
    NDK_DIR="$SDK_ROOT/ndk"
    if [ -d "$NDK_DIR" ]; then
        NDK="$NDK_DIR/$(ls -1 "$NDK_DIR" | sort -V | tail -1)"
    else
        echo "ERROR: Cannot find Android NDK. Set ANDROID_NDK_HOME." >&2
        exit 1
    fi
fi
HOST_OS=$(uname -s | tr 'A-Z' 'a-z')
HOST_ARCH=$(uname -m)
TOOLCHAIN="$NDK/toolchains/llvm/prebuilt/${HOST_OS}-${HOST_ARCH}"
TARGET="aarch64-linux-android29"

echo "=== WLDroid Compositor build ==="
echo "NDK:         $NDK"
echo "Build dir:   $BUILD_DIR"
echo "External:    $EXTERNAL_DIR"

# ── Handle --clean ──
if [ "${1:-}" = "--clean" ]; then
    echo "  [clean] Wiping build dir..."
    rm -rf "$BUILD_DIR"
    rm -rf "$NATIVE_DIR/builddir-native"
fi

# ── Generate cross-file (NDK paths are machine-specific) ──
CROSS_DIR="$NATIVE_DIR/builddir-cross"
mkdir -p "$CROSS_DIR"

CROSS_FILE="$CROSS_DIR/android-aarch64.ini"
cat > "$CROSS_FILE" <<EOF
[binaries]
c = '$TOOLCHAIN/bin/${TARGET}-clang'
cpp = '$TOOLCHAIN/bin/${TARGET}-clang++'
ar = '$TOOLCHAIN/bin/llvm-ar'
strip = '$TOOLCHAIN/bin/llvm-strip'
ranlib = '$TOOLCHAIN/bin/llvm-ranlib'
pkg-config = 'pkg-config'

[built-in options]
c_args = ['-fPIC']
c_link_args = ['-llog', '-landroid', '-lEGL', '-lGLESv2', '-lnativewindow']

[properties]
# Only search our subprojects for cross deps — block host system pkg-config
# from leaking host libraries (e.g. ICU) into the Android cross build.
pkg_config_libdir = ['$BUILD_DIR/meson-uninstalled']

[host_machine]
system = 'android'
cpu_family = 'aarch64'
cpu = 'aarch64'
endian = 'little'
EOF


# ── Locate wayland-scanner for the host ──
# wayland-scanner is a code-generation tool that must run on the build machine
# (x86_64), not the target (aarch64-android). Prefer the system-installed binary;
# fall back to building from the wayland subproject source if not available.
if command -v wayland-scanner >/dev/null 2>&1; then
    SCANNER_BIN="$(command -v wayland-scanner)"
    echo "Host wayland-scanner: $SCANNER_BIN"
else
    NATIVE_BUILD_DIR="$NATIVE_DIR/builddir-native"
    SCANNER_BIN="$NATIVE_BUILD_DIR/src/wayland-scanner"

    if [ ! -x "$SCANNER_BIN" ]; then
        echo ""
        echo "=== Building wayland-scanner for host ==="
        WAYLAND_SRC="$EXTERNAL_DIR/wayland-1.24.0"
        if [ ! -d "$WAYLAND_SRC" ]; then
            echo "ERROR: wayland source not found in $WAYLAND_SRC" >&2
            echo "Ensure external/ submodules are initialized." >&2
            exit 1
        fi
        meson setup "$NATIVE_BUILD_DIR" "$WAYLAND_SRC" \
            --default-library=static \
            -Dtests=false -Ddocumentation=false -Ddtd_validation=false \
            2>/dev/null || true
        ninja -C "$NATIVE_BUILD_DIR" src/wayland-scanner
    fi
    echo "Built wayland-scanner: $SCANNER_BIN"
fi

# ── Generate native file (tells cross build where host scanner lives) ──
# This is essential: --wrap-mode=forcefallback would otherwise try to build
# wayland-scanner from the wayland subproject for the cross target (aarch64),
# producing a binary that can't execute on the build host.
NATIVE_FILE="$CROSS_DIR/native.ini"
cat > "$NATIVE_FILE" <<EOF
[binaries]
wayland-scanner = '$SCANNER_BIN'
EOF

# ── Detect stale builddir (missing native file in saved config) ──
# If builddir was created before the native-file fix, ninja's auto-regeneration
# will fail because the saved config doesn't include --native-file. Wipe it.
if [ -f "$BUILD_DIR/build.ninja" ] && \
   ! grep -q "native-file" "$BUILD_DIR/meson-private/cmd_line.txt" 2>/dev/null; then
    echo ""
    echo "=== Wiping stale build dir (missing native file config) ==="
    rm -rf "$BUILD_DIR"
fi

# ── Configure ──
# Use --subproject-dir to point Meson at external/ for dependency subprojects.
if [ ! -f "$BUILD_DIR/build.ninja" ]; then
    echo ""
    echo "=== Configuring meson build ==="
    meson setup "$BUILD_DIR" "$NATIVE_DIR" \
        --cross-file "$CROSS_FILE" \
        --native-file "$NATIVE_FILE" \
        --default-library=static \
        --buildtype=release \
        --strip \
        --wrap-mode=forcefallback
else
    # Reconfigure if meson.build or wrap files changed
    echo ""
    echo "=== Reconfiguring (incremental) ==="
    meson setup --reconfigure "$BUILD_DIR" "$NATIVE_DIR" \
        --cross-file "$CROSS_FILE" \
        --native-file "$NATIVE_FILE" \
        --default-library=static \
        --buildtype=release \
        --strip \
        --wrap-mode=forcefallback
fi

# ── Build ──
echo ""
echo "=== Building ==="
ninja -C "$BUILD_DIR"

# ── Copy .so to jniLibs ──
SO_FILE="$BUILD_DIR/src/libwldroid-compositor.so"
if [ ! -f "$SO_FILE" ]; then
    echo "ERROR: $SO_FILE not found after build" >&2
    exit 1
fi

mkdir -p "$JNILIBS_DIR"
cp "$SO_FILE" "$JNILIBS_DIR/"
echo ""
echo "=== Build complete ==="
echo "Installed: $JNILIBS_DIR/libwldroid-compositor.so"
ls -la "$JNILIBS_DIR/libwldroid-compositor.so"
