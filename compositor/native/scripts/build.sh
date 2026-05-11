#!/bin/bash
# build.sh — Build the WLDroid Wayland compositor for Android arm64.
#
# Dependencies are managed as git submodules in external/ at the project root.
# Meson resolves them via --subproject-dir pointing to external/.
#
# Usage:
#   bash compositor/native/scripts/build.sh                # incremental build
#   bash compositor/native/scripts/build.sh --clean        # wipe build dir
#   bash compositor/native/scripts/build.sh --setup-only   # configure only, skip build
#
# Gradle calls this via the buildCompositor task.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
NATIVE_DIR="$SCRIPT_DIR/.."
# Project root is two levels up from native dir: compositor/native/ -> project root
PROJECT_ROOT="$NATIVE_DIR/../.."
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

SETUP_ONLY=false
if [ "${1:-}" = "--setup-only" ]; then
    SETUP_ONLY=true
    echo "Setup-only mode: will configure but skip build"
fi

# ── Ensure Meson subproject symlinks exist (forked deps) ──
bash "$PROJECT_ROOT/scripts/setup-meson-subprojects.sh"

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
SCANNER_BIN=""
if command -v wayland-scanner >/dev/null 2>&1; then
    _candidate="$(command -v wayland-scanner)"
    # wayland-scanner >= 1.24.0 required — wayland 1.24 protocol XML uses the
    # deprecated-since attribute which older scanners (e.g. 1.22.0) reject.
    _ver=$("$_candidate" --version 2>&1 | grep -oE '[0-9]+\.[0-9]+\.[0-9]+' || echo "0.0.0")
    _major=$(echo "$_ver" | cut -d. -f1)
    _minor=$(echo "$_ver" | cut -d. -f2)
    if [ "$_major" -gt 1 ] || { [ "$_major" -eq 1 ] && [ "$_minor" -ge 24 ]; }; then
        SCANNER_BIN="$_candidate"
        echo "Host wayland-scanner: $SCANNER_BIN (version $_ver)"
    else
        echo "System wayland-scanner $_ver too old (need >= 1.24.0), will build from source"
    fi
fi

if [ -z "$SCANNER_BIN" ]; then
    NATIVE_BUILD_DIR="$NATIVE_DIR/builddir-native"
    SCANNER_BIN="$NATIVE_BUILD_DIR/src/wayland-scanner"

    if [ ! -x "$SCANNER_BIN" ]; then
        echo ""
        echo "=== Building wayland-scanner for host ==="
        WAYLAND_SRC="$SCRIPT_DIR/../subprojects/wayland-1.24.0"
        if [ ! -d "$WAYLAND_SRC" ]; then
            echo "ERROR: Wayland source not found at $WAYLAND_SRC" >&2
            echo "Run 'meson subprojects download wayland' in compositor/native/ or install wayland-scanner >= 1.24.0 on the system." >&2
            exit 1
        fi
        meson setup "$NATIVE_BUILD_DIR" "$WAYLAND_SRC" \
            --default-library=static \
            -Dtests=false -Ddocumentation=false -Ddtd_validation=false
        ninja -C "$NATIVE_BUILD_DIR" src/wayland-scanner
    fi
    echo "Built wayland-scanner: $SCANNER_BIN"
fi

# ── Create wayland-scanner pkg-config override ──
# Meson's dependency('wayland-scanner', native: true) does a pkg-config lookup.
# The system wayland-scanner may be too old (e.g. 1.22.0 on Ubuntu) but our
# scanner binary (system or self-built) is adequate. Create a .pc file that
# reports version 1.24.0 (matching the wayland subproject) so the version check
# passes, and point PKG_CONFIG_PATH at it for the configure step.
SCANNER_PC_DIR="$CROSS_DIR/pkgconfig"
mkdir -p "$SCANNER_PC_DIR"
cat > "$SCANNER_PC_DIR/wayland-scanner.pc" <<EOF
wayland_scanner=$SCANNER_BIN

Name: wayland-scanner
Description: Wayland scanner (provided by build system)
Version: 1.24.0
EOF
echo "Scanner .pc override: $SCANNER_PC_DIR/wayland-scanner.pc"

# ── Generate native file (tells cross build where host scanner lives) ──
# This is essential: --wrap-mode=forcefallback would otherwise try to build
# wayland-scanner from the wayland subproject for the cross target (aarch64),
# producing a binary that can't execute on the build host.
#
# pkg_config_path is set here (not via env var) so Meson reliably finds the
# wayland-scanner .pc override during native dependency lookups in cross-build
# mode. Meson's cross-build pkg-config does not always inherit shell env vars.
NATIVE_FILE="$CROSS_DIR/native.ini"
cat > "$NATIVE_FILE" <<EOF
[binaries]
wayland-scanner = '$SCANNER_BIN'

[built-in options]
pkg_config_path = ['$SCANNER_PC_DIR']
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

if [ "$SETUP_ONLY" = true ]; then
    echo ""
    echo "=== Setup-only mode: skipping build ==="
    exit 0
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
