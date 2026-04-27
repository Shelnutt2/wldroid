#!/bin/bash
# build-proot.sh — Cross-compile proot for Android arm64.
#
# Builds the Termux proot fork and its dependency (libtalloc) as static
# binaries for aarch64-android, then copies the results into jniLibs/ so
# AGP packages them into the APK (named lib*.so for W^X compliance).
#
# Usage:
#   bash proot/native/build-proot.sh           # incremental build
#   bash proot/native/build-proot.sh --clean   # wipe build artifacts
#
# Gradle calls this via the buildNative task.
set -euo pipefail

# ── Paths ──
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROOT_MODULE_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
PROJECT_ROOT="$(cd "$PROOT_MODULE_DIR/.." && pwd)"
JNILIBS_DIR="$PROOT_MODULE_DIR/src/main/jniLibs/arm64-v8a"
BUILD_DIR="$PROOT_MODULE_DIR/build/native"

# Source directories (git submodules in external/)
PROOT_SRC="${PROJECT_ROOT}/external/proot"
TALLOC_SRC="${PROJECT_ROOT}/external/talloc"

# ── Locate Android NDK ──
# Prefer ANDROID_NDK_HOME, then ANDROID_NDK, then highest version in SDK.
if [ -n "${ANDROID_NDK_HOME:-}" ]; then
    NDK="$ANDROID_NDK_HOME"
elif [ -n "${ANDROID_NDK:-}" ]; then
    NDK="$ANDROID_NDK"
else
    SDK_ROOT="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-/opt/android-sdk}}"
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
export AR="$TOOLCHAIN/bin/llvm-ar"
export RANLIB="$TOOLCHAIN/bin/llvm-ranlib"
export STRIP="$TOOLCHAIN/bin/llvm-strip"
export OBJCOPY="$TOOLCHAIN/bin/llvm-objcopy"
export OBJDUMP="$TOOLCHAIN/bin/llvm-objdump"
export CFLAGS="-fPIC --sysroot=$SYSROOT"
export LDFLAGS="--sysroot=$SYSROOT"

echo "=== Proot build ==="
echo "NDK:         $NDK"
echo "Proot src:   $PROOT_SRC"
echo "Talloc src:  $TALLOC_SRC"
echo "Build dir:   $BUILD_DIR"

# ── Handle --clean ──
if [ "${1:-}" = "--clean" ]; then
    echo "  [clean] Wiping build artifacts..."
    rm -rf "$BUILD_DIR"
    rm -f "$JNILIBS_DIR/libproot.so"
    rm -f "$JNILIBS_DIR/libproot-loader.so"
    echo "  [clean] Done."
    exit 0
fi

# ── Validate source directories ──
if [ ! -d "$PROOT_SRC/src" ]; then
    echo "ERROR: proot source not found at $PROOT_SRC" >&2
    echo "  Did you run: git submodule update --init external/proot?" >&2
    exit 1
fi

if [ ! -d "$TALLOC_SRC" ]; then
    echo "ERROR: talloc source not found at $TALLOC_SRC" >&2
    echo "  Did you run: git submodule update --init external/talloc?" >&2
    exit 1
fi

mkdir -p "$BUILD_DIR"

# ── Apply patches if present ──
PATCH_DIR="$SCRIPT_DIR/patches"
if [ -d "$PATCH_DIR" ] && ls "$PATCH_DIR"/*.patch &>/dev/null; then
    PATCH_MARKER="$BUILD_DIR/.patches_applied"
    if [ ! -f "$PATCH_MARKER" ]; then
        echo ""
        echo "=== Applying patches to proot source ==="
        for patch in "$PATCH_DIR"/*.patch; do
            echo "    Applying $(basename "$patch")"
            patch -d "$PROOT_SRC" -p1 < "$patch" || true
        done
        touch "$PATCH_MARKER"
    fi
fi

# ── Build talloc (static library) ──
TALLOC_BUILD="$BUILD_DIR/talloc"
TALLOC_INSTALL="$BUILD_DIR/talloc-install"

if [ ! -f "$TALLOC_INSTALL/lib/libtalloc.a" ]; then
    echo ""
    echo "=== Building talloc (static) ==="
    mkdir -p "$TALLOC_BUILD"

    # talloc uses a waf-based build system. For cross-compilation we need
    # a cross-answers file to avoid running target binaries on the host.
    CROSS_ANSWERS="$TALLOC_BUILD/cross-answers.txt"
    cat > "$CROSS_ANSWERS" <<'ANSWERS'
Checking uname sysname type: "Linux"
Checking uname machine type: "dontcare"
Checking uname release type: "dontcare"
Checking uname version type: "dontcare"
Checking simple C program: OK
building library support: OK
Checking for large file support: OK
Checking for -D_FILE_OFFSET_BITS=64: OK
Checking for WORDS_BIGENDIAN: OK
Checking for C99 vsnprintf: OK
Checking for HAVE_SECURE_MKSTEMP: OK
rpath library support: OK
-Wl,--version-script support: FAIL
Checking correct behavior of strtoll: OK
Checking correct behavior of strptime: OK
Checking for HAVE_IFACE_GETIFADDRS: OK
Checking for HAVE_IFACE_IFCONF: OK
Checking for HAVE_IFACE_IFREQ: OK
Checking getconf LFS_CFLAGS: OK
Checking for large file support without additional flags: OK
Checking for working strptime: OK
Checking for HAVE_SHARED_MMAP: OK
Checking for HAVE_MREMAP: OK
Checking for HAVE_INCOHERENT_MMAP: OK
Checking getconf large file support flags work: OK
ANSWERS

    cd "$TALLOC_SRC"
    ./configure \
        --prefix="$TALLOC_INSTALL" \
        --disable-rpath \
        --disable-python \
        --cross-compile \
        --cross-answers="$CROSS_ANSWERS" \
        CC="$CC" \
        AR="$AR" \
        RANLIB="$RANLIB" \
        CFLAGS="$CFLAGS" \
        LDFLAGS="$LDFLAGS"

    make -j"$(nproc)"

    mkdir -p "$TALLOC_INSTALL/lib" "$TALLOC_INSTALL/include"
    cp talloc.h "$TALLOC_INSTALL/include/"
    # Create static archive from built objects (matches Termux's post_make_install)
    cd bin/default
    "$AR" rcu "$TALLOC_INSTALL/lib/libtalloc.a" talloc*.o
    cd "$SCRIPT_DIR"
fi

echo "  talloc installed: $TALLOC_INSTALL"

# ── Build proot ──
PROOT_BUILD="$BUILD_DIR/proot"
PROOT_BIN="$PROOT_BUILD/proot"
if [ ! -f "$PROOT_BIN" ]; then
    echo ""
    echo "=== Building proot ==="
    mkdir -p "$PROOT_BUILD"

    # proot's Makefile lives in src/
    PROOT_MAKEFILE_DIR="$PROOT_SRC/src"

    make -C "$PROOT_MAKEFILE_DIR" \
        CC="$CC" \
        AR="$AR" \
        STRIP="$STRIP" \
        OBJCOPY="$OBJCOPY" \
        OBJDUMP="$OBJDUMP" \
        CFLAGS="$CFLAGS -I$TALLOC_INSTALL/include" \
        LDFLAGS="$LDFLAGS -L$TALLOC_INSTALL/lib -ltalloc -static-libgcc -Wl,-z,noexecstack" \
        V=1 \
        -j"$(nproc)"

    cp "$PROOT_MAKEFILE_DIR/proot" "$PROOT_BIN"
fi

# ── Copy proot loader (already built by main make) ──
LOADER_BIN="$PROOT_BUILD/loader"
LOADER_SRC="$PROOT_SRC/src/loader/loader"
if [ ! -f "$LOADER_BIN" ] && [ -f "$LOADER_SRC" ]; then
    echo ""
    echo "=== Copying proot loader ==="
    cp "$LOADER_SRC" "$LOADER_BIN"
fi

# ── Strip binaries ──
echo ""
echo "=== Stripping binaries ==="
"$STRIP" "$PROOT_BIN" 2>/dev/null || true
if [ -f "$LOADER_BIN" ]; then
    "$STRIP" "$LOADER_BIN" 2>/dev/null || true
fi

# ── Copy to jniLibs ──
# Named lib*.so so Android extracts them to a W^X-compliant path at install.
echo ""
echo "=== Installing to jniLibs ==="
mkdir -p "$JNILIBS_DIR"

cp "$PROOT_BIN" "$JNILIBS_DIR/libproot.so"
echo "Installed: $JNILIBS_DIR/libproot.so"
ls -la "$JNILIBS_DIR/libproot.so"

if [ -f "$LOADER_BIN" ]; then
    cp "$LOADER_BIN" "$JNILIBS_DIR/libproot-loader.so"
    echo "Installed: $JNILIBS_DIR/libproot-loader.so"
    ls -la "$JNILIBS_DIR/libproot-loader.so"
fi

echo ""
echo "=== Proot build complete ==="
