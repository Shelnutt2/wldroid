#!/usr/bin/env bash
# Setup an Android emulator AVD for WLDroid instrumented testing.
set -euo pipefail

ANDROID_HOME="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
if [ -z "$ANDROID_HOME" ]; then
    echo "Error: ANDROID_HOME (or ANDROID_SDK_ROOT) is not set."
    echo "Install Android Studio or the command-line tools and export ANDROID_HOME."
    exit 1
fi

SDKMANAGER="$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager"
AVDMANAGER="$ANDROID_HOME/cmdline-tools/latest/bin/avdmanager"

# Ensure cmdline-tools are available
if [ ! -x "$SDKMANAGER" ]; then
    echo "Error: sdkmanager not found at $SDKMANAGER"
    echo "Install cmdline-tools via Android Studio SDK Manager or download from:"
    echo "  https://developer.android.com/studio#command-line-tools-only"
    echo "Then place them at \$ANDROID_HOME/cmdline-tools/latest/"
    exit 1
fi

SYSTEM_IMAGE="system-images;android-34;google_apis;x86_64"
AVD_NAME="wldroid-test"

echo "==> Installing system image and platform tools..."
yes | "$SDKMANAGER" "$SYSTEM_IMAGE" "platform-tools" "platforms;android-34" "emulator" || true

echo ""
echo "==> Accepting Android SDK licenses..."
yes | "$SDKMANAGER" --licenses > /dev/null 2>&1 || true

echo ""
echo "==> Creating AVD '$AVD_NAME'..."
echo "no" | "$AVDMANAGER" create avd \
    --name "$AVD_NAME" \
    --package "$SYSTEM_IMAGE" \
    --device "pixel_6" \
    --force

echo ""
echo "✅ Emulator setup complete!"
echo ""
echo "Run the emulator with:"
echo "  make emulator-start"
echo ""
echo "Then run instrumented tests with:"
echo "  make test-instrumented"
