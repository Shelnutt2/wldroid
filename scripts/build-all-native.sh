#!/usr/bin/env bash
# build-all-native.sh — Build all WLDroid native components in correct order.
#
# This script orchestrates the full native build:
#   1. Setup Meson subproject symlinks
#   2. Compositor (Meson/Ninja → libwldroid-compositor.so)
#   3. Proot (Make → libproot.so, libproot-loader.so)
#   4. Virgl (Meson/Ninja → libvirgl-test-server.so)
#   5. Shims (GCC cross-compile → drm-shim, gbm-shim, egl-override, netstub, drm-wrapper)
#
# Usage:
#   bash scripts/build-all-native.sh [options]
#
# Options:
#   --skip-compositor   Skip compositor build
#   --skip-proot        Skip proot build
#   --skip-virgl        Skip virgl build
#   --skip-shims        Skip shims build
#   --clean             Clean all build artifacts before building
#   --help              Show this help message
#
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

# ── Defaults ──
SKIP_COMPOSITOR=false
SKIP_PROOT=false
SKIP_VIRGL=false
SKIP_SHIMS=false
CLEAN=false

# ── Parse arguments ──
while [[ $# -gt 0 ]]; do
    case "$1" in
        --skip-compositor) SKIP_COMPOSITOR=true ;;
        --skip-proot)      SKIP_PROOT=true ;;
        --skip-virgl)      SKIP_VIRGL=true ;;
        --skip-shims)      SKIP_SHIMS=true ;;
        --clean)           CLEAN=true ;;
        --help|-h)
            head -20 "$0" | grep '^#' | sed 's/^# \?//'
            exit 0
            ;;
        *)
            echo "Unknown option: $1" >&2
            echo "Run with --help for usage." >&2
            exit 1
            ;;
    esac
    shift
done

# ── Detect Android NDK ──
detect_ndk() {
    if [ -n "${ANDROID_NDK_HOME:-}" ]; then
        echo "$ANDROID_NDK_HOME"
        return
    fi
    if [ -n "${ANDROID_NDK:-}" ]; then
        echo "$ANDROID_NDK"
        return
    fi
    # Try well-known SDK locations
    for SDK_ROOT in "${ANDROID_SDK_ROOT:-}" "${ANDROID_HOME:-}" "$HOME/Android/Sdk" "/opt/android-sdk" "/opt/android-sdk-update-manager"; do
        if [ -n "$SDK_ROOT" ] && [ -d "$SDK_ROOT/ndk" ]; then
            local latest
            latest="$(ls -1 "$SDK_ROOT/ndk" 2>/dev/null | sort -V | tail -1)"
            if [ -n "$latest" ]; then
                echo "$SDK_ROOT/ndk/$latest"
                return
            fi
        fi
    done
    return 1
}

NDK=""
if ! NDK="$(detect_ndk)"; then
    echo "WARNING: Android NDK not found. Set ANDROID_NDK_HOME." >&2
    echo "         Builds targeting Android will fail." >&2
fi

echo "╔══════════════════════════════════════════════════╗"
echo "║     WLDroid — Full Native Build                  ║"
echo "╚══════════════════════════════════════════════════╝"
echo ""
echo "Project root:  $PROJECT_ROOT"
echo "NDK:           ${NDK:-<not found>}"
echo "Skip:          compositor=$SKIP_COMPOSITOR proot=$SKIP_PROOT virgl=$SKIP_VIRGL shims=$SKIP_SHIMS"
echo ""

# Track results for summary
declare -A BUILD_RESULTS

# ── Step 0: Setup Meson subproject symlinks ──
echo "=== [0/4] Setting up Meson subproject symlinks ==="
bash "$PROJECT_ROOT/scripts/setup-meson-subprojects.sh"
echo ""

# ── Step 1: Compositor ──
if [ "$SKIP_COMPOSITOR" = true ]; then
    echo "=== [1/4] Compositor — SKIPPED ==="
    BUILD_RESULTS[compositor]="skipped"
else
    echo "=== [1/4] Building Compositor ==="
    CLEAN_ARG=""
    [ "$CLEAN" = true ] && CLEAN_ARG="--clean"
    if bash "$PROJECT_ROOT/compositor/native/scripts/build.sh" $CLEAN_ARG; then
        BUILD_RESULTS[compositor]="success"
    else
        BUILD_RESULTS[compositor]="FAILED"
        echo "ERROR: Compositor build failed!" >&2
    fi
fi
echo ""

# ── Step 2: Proot ──
if [ "$SKIP_PROOT" = true ]; then
    echo "=== [2/4] Proot — SKIPPED ==="
    BUILD_RESULTS[proot]="skipped"
else
    echo "=== [2/4] Building Proot ==="
    CLEAN_ARG=""
    [ "$CLEAN" = true ] && CLEAN_ARG="--clean"
    if bash "$PROJECT_ROOT/proot/native/build-proot.sh" $CLEAN_ARG; then
        BUILD_RESULTS[proot]="success"
    else
        BUILD_RESULTS[proot]="FAILED"
        echo "ERROR: Proot build failed!" >&2
    fi
fi
echo ""

# ── Step 3: Virgl ──
if [ "$SKIP_VIRGL" = true ]; then
    echo "=== [3/4] Virgl — SKIPPED ==="
    BUILD_RESULTS[virgl]="skipped"
else
    echo "=== [3/4] Building Virgl ==="
    CLEAN_ARG=""
    [ "$CLEAN" = true ] && CLEAN_ARG="--clean"
    if bash "$PROJECT_ROOT/virgl/native/build-virgl.sh" $CLEAN_ARG; then
        BUILD_RESULTS[virgl]="success"
    else
        BUILD_RESULTS[virgl]="FAILED"
        echo "ERROR: Virgl build failed!" >&2
    fi
fi
echo ""

# ── Step 4: Shims ──
if [ "$SKIP_SHIMS" = true ]; then
    echo "=== [4/4] Shims — SKIPPED ==="
    BUILD_RESULTS[shims]="skipped"
else
    echo "=== [4/4] Building Shims ==="
    if [ "$CLEAN" = true ]; then
        rm -rf "$PROJECT_ROOT/shims/build/outputs/native"
    fi
    if bash "$PROJECT_ROOT/shims/docker/build-all.sh"; then
        BUILD_RESULTS[shims]="success"
    else
        BUILD_RESULTS[shims]="FAILED"
        echo "ERROR: Shims build failed!" >&2
    fi
fi
echo ""

# ── Summary ──
echo "╔══════════════════════════════════════════════════╗"
echo "║     Build Summary                                ║"
echo "╚══════════════════════════════════════════════════╝"
echo ""

FAILED=0
for component in compositor proot virgl shims; do
    status="${BUILD_RESULTS[$component]:-unknown}"
    case "$status" in
        success)  printf "  ✅ %-15s %s\n" "$component" "SUCCESS" ;;
        skipped)  printf "  ⏭️  %-15s %s\n" "$component" "SKIPPED" ;;
        FAILED)   printf "  ❌ %-15s %s\n" "$component" "FAILED"
                  FAILED=$((FAILED + 1)) ;;
        *)        printf "  ❓ %-15s %s\n" "$component" "UNKNOWN" ;;
    esac
done
echo ""

# ── List built artifacts ──
echo "Built artifacts:"
for dir in \
    "$PROJECT_ROOT/compositor/src/main/jniLibs/arm64-v8a" \
    "$PROJECT_ROOT/proot/src/main/jniLibs/arm64-v8a" \
    "$PROJECT_ROOT/virgl/src/main/jniLibs/arm64-v8a" \
    "$PROJECT_ROOT/shims/build/outputs/native"; do
    if [ -d "$dir" ]; then
        find "$dir" -type f \( -name "*.so" -o -name "*.so.*" \) -exec ls -lh {} \;
    fi
done
echo ""

if [ "$FAILED" -gt 0 ]; then
    echo "⚠️  $FAILED component(s) failed. Check logs above."
    exit 1
fi

echo "✅ All native builds completed successfully!"
