#!/usr/bin/env bash
# Verify the WLDroid development environment.
set -uo pipefail

PASS="✅"
FAIL="❌"
WARN="⚠️ "
STATUS=0

check() {
    local label="$1"
    local cmd="$2"
    local version
    if version=$(eval "$cmd" 2>/dev/null); then
        printf "  %s  %-20s %s\n" "$PASS" "$label" "$version"
    else
        printf "  %s  %-20s not found\n" "$FAIL" "$label"
        STATUS=1
    fi
}

check_optional() {
    local label="$1"
    local cmd="$2"
    local version
    if version=$(eval "$cmd" 2>/dev/null); then
        printf "  %s  %-20s %s\n" "$PASS" "$label" "$version"
    else
        printf "  %s %-20s not found (optional)\n" "$WARN" "$label"
    fi
}

echo "WLDroid Development Environment Check"
echo "======================================"
echo ""

echo "Build tools:"
check "Java"   "java -version 2>&1 | head -1"
check "Gradle" "./gradlew --version 2>/dev/null | grep '^Gradle ' | head -1"
check "Meson"  "meson --version"
check "Ninja"  "ninja --version"
echo ""

echo "Android SDK:"
ANDROID_HOME="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
if [ -n "$ANDROID_HOME" ]; then
    printf "  %s  %-20s %s\n" "$PASS" "ANDROID_HOME" "$ANDROID_HOME"
else
    printf "  %s  %-20s not set\n" "$FAIL" "ANDROID_HOME"
    STATUS=1
fi

NDK_VERSION="28.0.13004108"
if [ -n "$ANDROID_HOME" ] && [ -d "$ANDROID_HOME/ndk/$NDK_VERSION" ]; then
    printf "  %s  %-20s %s\n" "$PASS" "NDK" "$NDK_VERSION"
else
    printf "  %s  %-20s r28 (%s) not found\n" "$FAIL" "NDK" "$NDK_VERSION"
    STATUS=1
fi

if [ -n "$ANDROID_HOME" ] && [ -x "$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" ]; then
    printf "  %s  %-20s installed\n" "$PASS" "cmdline-tools"
else
    printf "  %s %-20s not found (needed for emulator setup)\n" "$WARN" "cmdline-tools"
fi

if [ -n "$ANDROID_HOME" ] && [ -x "$ANDROID_HOME/emulator/emulator" ]; then
    printf "  %s  %-20s installed\n" "$PASS" "Emulator"
else
    printf "  %s %-20s not found (run 'make setup-emulator')\n" "$WARN" "Emulator"
fi
echo ""

echo "Optional tools:"
check_optional "Docker"  "docker --version"
check_optional "adb"     "adb --version | head -1"
echo ""

echo "Git submodules:"
TOTAL=$(git submodule status 2>/dev/null | wc -l)
INIT=$(git submodule status 2>/dev/null | grep -cv '^-' || true)
if [ "$TOTAL" -gt 0 ]; then
    if [ "$INIT" -eq "$TOTAL" ]; then
        printf "  %s  %-20s %s/%s initialized\n" "$PASS" "Submodules" "$INIT" "$TOTAL"
    else
        printf "  %s  %-20s %s/%s initialized (run 'make setup-submodules')\n" "$FAIL" "Submodules" "$INIT" "$TOTAL"
        STATUS=1
    fi
else
    printf "  %s %-20s could not determine status\n" "$WARN" "Submodules"
fi
echo ""

if [ $STATUS -eq 0 ]; then
    echo "All required tools found! 🎉"
else
    echo "Some required tools are missing. See above for details."
fi
exit $STATUS
