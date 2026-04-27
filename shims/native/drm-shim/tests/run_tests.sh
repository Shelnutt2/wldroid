#!/usr/bin/env bash
# run_tests.sh — Run all drm-shim test binaries and report results
#
# Usage: ./run_tests.sh [build-dir]
#   build-dir: meson build directory (default: ./builddir)

set -euo pipefail

BUILD_DIR="${1:-./builddir}"
PASS=0
FAIL=0
TOTAL=0

TESTS=(
    test_drm_devices
    test_drm_version
    test_drm_prime
)

echo "================================================"
echo " drm-shim test runner"
echo "================================================"
echo ""

for t in "${TESTS[@]}"; do
    TOTAL=$((TOTAL + 1))
    BIN="${BUILD_DIR}/${t}"
    if [ ! -x "$BIN" ]; then
        echo "SKIP  ${t} (binary not found: ${BIN})"
        continue
    fi

    echo "--- ${t} ---"
    if "$BIN"; then
        PASS=$((PASS + 1))
        echo ">>> ${t}: PASS"
    else
        FAIL=$((FAIL + 1))
        echo ">>> ${t}: FAIL"
    fi
    echo ""
done

echo "================================================"
echo " Results: ${PASS}/${TOTAL} passed"
if [ "$FAIL" -gt 0 ]; then
    echo " ${FAIL} FAILED"
    exit 1
fi
echo " All tests passed!"
exit 0
