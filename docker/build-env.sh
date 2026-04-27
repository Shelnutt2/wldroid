#!/usr/bin/env bash
# build-env.sh — Enter an interactive Docker build environment for WLDroid.
#
# Usage:
#   bash docker/build-env.sh              # interactive shell
#   bash docker/build-env.sh <command>    # run a specific command
#
# The project root is mounted at /project inside the container.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

IMAGE_NAME="wldroid-builder"

# Build the image if it doesn't exist
if ! docker image inspect "$IMAGE_NAME" &>/dev/null; then
    echo "Building Docker image: $IMAGE_NAME"
    docker build -t "$IMAGE_NAME" "$SCRIPT_DIR"
fi

if [ $# -eq 0 ]; then
    echo "Entering WLDroid build environment..."
    echo "  Project mounted at: /project"
    echo "  NDK at: /opt/android/ndk"
    echo ""
    exec docker run --rm -it \
        -v "$PROJECT_ROOT:/project" \
        -w /project \
        "$IMAGE_NAME" \
        /bin/bash
else
    exec docker run --rm \
        -v "$PROJECT_ROOT:/project" \
        -w /project \
        "$IMAGE_NAME" \
        "$@"
fi
