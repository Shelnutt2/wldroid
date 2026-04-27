#!/usr/bin/env bash
# Start the WLDroid test emulator headless and wait for boot.
set -euo pipefail

ANDROID_HOME="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
if [ -z "$ANDROID_HOME" ]; then
    echo "Error: ANDROID_HOME is not set."
    exit 1
fi

AVD_NAME="wldroid-test"
EMULATOR="$ANDROID_HOME/emulator/emulator"

if [ ! -x "$EMULATOR" ]; then
    echo "Error: emulator not found at $EMULATOR"
    echo "Run 'make setup-emulator' first."
    exit 1
fi

# Check if AVD exists
if ! "$EMULATOR" -list-avds 2>/dev/null | grep -q "^${AVD_NAME}$"; then
    echo "Error: AVD '$AVD_NAME' not found."
    echo "Run 'make setup-emulator' first."
    exit 1
fi

echo "Starting emulator '$AVD_NAME' (headless)..."
"$EMULATOR" -avd "$AVD_NAME" \
    -no-window -no-audio -no-boot-anim \
    -gpu swiftshader_indirect \
    -no-snapshot &

EMULATOR_PID=$!
echo "Emulator PID: $EMULATOR_PID"

echo "Waiting for device..."
adb wait-for-device

echo "Waiting for boot to complete..."
TIMEOUT=120
ELAPSED=0
while [ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" != "1" ]; do
    if [ $ELAPSED -ge $TIMEOUT ]; then
        echo "Error: Emulator failed to boot within ${TIMEOUT}s"
        kill "$EMULATOR_PID" 2>/dev/null || true
        exit 1
    fi
    sleep 2
    ELAPSED=$((ELAPSED + 2))
done

echo "✅ Emulator booted! (${ELAPSED}s)"
