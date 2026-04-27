#!/usr/bin/env bash
# setup-meson-subprojects.sh — Create symlinks from compositor/native/subprojects/
# to external/ git submodules so Meson can find them.
#
# This script is idempotent and safe to re-run.
# Call before `meson setup` in the compositor native build.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

SUBPROJECTS_DIR="$PROJECT_ROOT/compositor/native/subprojects"
EXTERNAL_DIR="$PROJECT_ROOT/external"

# Relative path from subprojects dir to external dir
# compositor/native/subprojects/ → ../../../external/
RELATIVE_PREFIX="../../../external"

# Dependencies used by the compositor Meson build.
# These map: subprojects/<name> → external/<name>
COMPOSITOR_DEPS=(
    wlroots
    wayland
    wayland-protocols
    libdrm
    pixman
    libxkbcommon
    libffi
    expat
    xcb-proto
    libxcb
    libxau
    xorgproto
    xcb-util-wm
    libepoxy
)

# Dependencies used by the virgl Meson build (virglrenderer + its deps).
# The virgl module has its own native build, but shares the same external/ deps.
VIRGL_DEPS=(
    virglrenderer
    libepoxy
    libdrm
)

# Dependencies used by the proot build.
PROOT_DEPS=(
    proot
    talloc
)

# Combine all unique deps
ALL_DEPS=($(printf '%s\n' "${COMPOSITOR_DEPS[@]}" "${VIRGL_DEPS[@]}" "${PROOT_DEPS[@]}" | sort -u))

echo "Setting up Meson subproject symlinks..."
echo "  Subprojects dir: $SUBPROJECTS_DIR"
echo "  External dir:    $EXTERNAL_DIR"
echo ""

mkdir -p "$SUBPROJECTS_DIR"

created=0
skipped=0
errors=0

for dep in "${ALL_DEPS[@]}"; do
    target="$RELATIVE_PREFIX/$dep"
    link="$SUBPROJECTS_DIR/$dep"

    if [ -L "$link" ]; then
        # Symlink already exists — verify it points to the right place
        existing_target="$(readlink "$link")"
        if [ "$existing_target" = "$target" ]; then
            skipped=$((skipped + 1))
            continue
        else
            echo "  WARNING: $link points to $existing_target, expected $target — updating"
            rm "$link"
        fi
    elif [ -e "$link" ]; then
        echo "  WARNING: $link exists and is not a symlink — skipping"
        errors=$((errors + 1))
        continue
    fi

    ln -s "$target" "$link"
    echo "  Created: $dep → $target"
    created=$((created + 1))
done

echo ""
echo "Done: $created created, $skipped already existed, $errors errors"

# Also create a packagefiles symlink if source packagefiles exist
# (for Meson wrap overlay files like meson.build overrides)
PACKAGEFILES_DIR="$SUBPROJECTS_DIR/packagefiles"
if [ ! -e "$PACKAGEFILES_DIR" ]; then
    mkdir -p "$PACKAGEFILES_DIR"
    echo "Created packagefiles directory: $PACKAGEFILES_DIR"
fi
