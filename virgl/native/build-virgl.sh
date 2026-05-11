#!/bin/bash
# build-virgl.sh — Cross-compile virgl_test_server for Android arm64.
#
# Builds libepoxy (dependency) and virglrenderer from the project's
# git submodules, then copies the virgl_test_server binary into
# jniLibs/ (named lib*.so for W^X compliance on Android).
#
# Submodule paths:
#   ${PROJECT_ROOT}/external/virglrenderer/
# libepoxy is downloaded automatically during build (not a submodule).
#
# Usage:
#   bash virgl/native/build-virgl.sh           # incremental build
#   bash virgl/native/build-virgl.sh --clean   # wipe build artifacts
#
# Gradle calls this via the buildNative task in virgl/build.gradle.kts.
#
# Patches:
#   The upstream virglrenderer patches that were previously in
#   proot-gpu/virgl/patches/ are now applied to the wldroid fork at
#   external/virglrenderer/ (i.e. baked into the fork's commit history).
#   Patches included in the fork:
#     0010-android-egl-display.patch
#     0020-android-gbm-shim.patch
#     0030-android-gbm-alloc-preference.patch
#     0040-android-venus-memfd.patch
#     0045-android-skip-memfd-seals.patch
#     0046-android-venus-gbm-fallback.patch
#     0050-android-ahb-egl-image.patch
#     0060-android-ahb-export-fd.patch
#     0070-android-ahb-vtest-export.patch
#     0080-android-ahb-registry.patch
#
#   If any patches are NOT yet in the fork, they should be applied
#   manually to external/virglrenderer/ via `git am`.
set -euo pipefail

# ── Paths ──
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
VIRGL_MODULE_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
PROJECT_ROOT="$(cd "$VIRGL_MODULE_DIR/.." && pwd)"
JNILIBS_DIR="$VIRGL_MODULE_DIR/src/main/jniLibs/arm64-v8a"
BUILD_DIR="$VIRGL_MODULE_DIR/native/build"

# Submodule source directories
VIRGL_SRC="$PROJECT_ROOT/external/virglrenderer"

# libepoxy is not a git submodule — download it if not already present.
EPOXY_VERSION="1.5.10"
EPOXY_SRC="$BUILD_DIR/libepoxy-src"

# Validate virglrenderer submodule exists
if [ ! -d "$VIRGL_SRC" ]; then
    echo "ERROR: Submodule not found at $VIRGL_SRC" >&2
    echo "  Run: git submodule update --init --recursive" >&2
    exit 1
fi

# ── Locate Android NDK ──
if [ -n "${ANDROID_NDK_HOME:-}" ]; then
    NDK="$ANDROID_NDK_HOME"
elif [ -n "${ANDROID_NDK:-}" ]; then
    NDK="$ANDROID_NDK"
else
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
SYSROOT="$TOOLCHAIN/sysroot"

# Cross-compilation toolchain variables
export CC="$TOOLCHAIN/bin/${TARGET}-clang"
export CXX="$TOOLCHAIN/bin/${TARGET}-clang++"
export AR="$TOOLCHAIN/bin/llvm-ar"
export RANLIB="$TOOLCHAIN/bin/llvm-ranlib"
export STRIP="$TOOLCHAIN/bin/llvm-strip"
export CFLAGS="-fPIC --sysroot=$SYSROOT"
export LDFLAGS="--sysroot=$SYSROOT"

echo "=== Virgl build ==="
echo "NDK:         $NDK"
echo "Build dir:   $BUILD_DIR"
echo "Virgl src:   $VIRGL_SRC"
echo "Epoxy src:   $EPOXY_SRC"

# ── Handle --clean ──
if [ "${1:-}" = "--clean" ]; then
    echo "  [clean] Wiping build artifacts..."
    rm -rf "$BUILD_DIR"
    rm -f "$JNILIBS_DIR/libvirgl-test-server.so"
    rm -f "$JNILIBS_DIR/libvirgl-render-server.so"
    echo "  [clean] Done."
    exit 0
fi

mkdir -p "$BUILD_DIR"

# ── Download libepoxy if needed ──
if [ ! -d "$EPOXY_SRC" ]; then
    echo ""
    echo "=== Downloading libepoxy $EPOXY_VERSION ==="
    EPOXY_TAR="$BUILD_DIR/libepoxy-${EPOXY_VERSION}.tar.gz"
    curl -L -o "$EPOXY_TAR" \
        "https://github.com/anholt/libepoxy/archive/refs/tags/${EPOXY_VERSION}.tar.gz"
    tar xzf "$EPOXY_TAR" -C "$BUILD_DIR"
    mv "$BUILD_DIR/libepoxy-${EPOXY_VERSION}" "$EPOXY_SRC"
    rm -f "$EPOXY_TAR"
    echo "  Downloaded to: $EPOXY_SRC"
fi

# ── Install paths ──
EPOXY_INSTALL="$BUILD_DIR/libepoxy-install"

# ── Generate meson cross-file ──
CROSS_DIR="$BUILD_DIR/cross"
CROSS_FILE="$CROSS_DIR/android-aarch64.ini"

echo ""
echo "=== Generating meson cross-file ==="
mkdir -p "$CROSS_DIR"
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
c_link_args = ['-llog', '-landroid', '-lEGL', '-lGLESv2']

[properties]
pkg_config_libdir = ['$EPOXY_INSTALL/lib/pkgconfig']

[host_machine]
system = 'android'
cpu_family = 'aarch64'
cpu = 'aarch64'
endian = 'little'
EOF
echo "  Cross-file: $CROSS_FILE"

# ── Build libepoxy (static, cross-compiled) ──
EPOXY_BUILD="$BUILD_DIR/libepoxy"

if [ ! -f "$EPOXY_INSTALL/lib/pkgconfig/epoxy.pc" ]; then
    echo ""
    echo "=== Building libepoxy (static) ==="

    rm -rf "$EPOXY_BUILD"
    meson setup "$EPOXY_BUILD" "$EPOXY_SRC" \
        --cross-file "$CROSS_FILE" \
        --prefix="$EPOXY_INSTALL" \
        --default-library=static \
        --buildtype=release \
        --strip \
        -Degl=yes \
        -Dglx=no \
        -Dx11=false \
        -Dtests=false

    ninja -C "$EPOXY_BUILD"
    ninja -C "$EPOXY_BUILD" install
fi

echo "  libepoxy installed: $EPOXY_INSTALL"

# ── Build virglrenderer ──
VIRGL_BUILD="$BUILD_DIR/virglrenderer"
VIRGL_BIN="$VIRGL_BUILD/vtest/virgl_test_server"

if [ ! -f "$VIRGL_BIN" ]; then
    echo ""
    echo "=== Building virglrenderer ==="

    rm -rf "$VIRGL_BUILD"
    PKG_CONFIG_PATH="$EPOXY_INSTALL/lib/pkgconfig" \
    meson setup "$VIRGL_BUILD" "$VIRGL_SRC" \
        --cross-file "$CROSS_FILE" \
        --default-library=static \
        --buildtype=release \
        --strip \
        -Dplatforms=egl \
        -Dtests=false \
        -Dvenus=false \
        -Drender-server=true \
        -Drender-server-worker=thread

    ninja -C "$VIRGL_BUILD"
fi

# ── Strip binary ──
echo ""
echo "=== Stripping binary ==="
"$STRIP" "$VIRGL_BIN" 2>/dev/null || true

# ── Copy to jniLibs ──
echo ""
echo "=== Installing to jniLibs ==="
mkdir -p "$JNILIBS_DIR"

cp "$VIRGL_BIN" "$JNILIBS_DIR/libvirgl-test-server.so"
echo "Installed: $JNILIBS_DIR/libvirgl-test-server.so"
ls -la "$JNILIBS_DIR/libvirgl-test-server.so"

# Copy render server binary if present
RENDER_SERVER_BIN="$VIRGL_BUILD/server/virgl_render_server"
if [ -f "$RENDER_SERVER_BIN" ]; then
    "$STRIP" "$RENDER_SERVER_BIN" 2>/dev/null || true
    cp "$RENDER_SERVER_BIN" "$JNILIBS_DIR/libvirgl-render-server.so"
    echo "Installed: $JNILIBS_DIR/libvirgl-render-server.so"
    ls -la "$JNILIBS_DIR/libvirgl-render-server.so"
fi

echo ""
echo "=== Virgl build complete ==="
