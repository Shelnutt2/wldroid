#!/usr/bin/env bash
# setup-meson-subprojects.sh — Create symlinks from compositor/native/subprojects/
# to external/ git submodules for forked dependencies.
#
# Upstream dependencies are handled by Meson .wrap files committed in
# compositor/native/subprojects/ — only the 3 forks need symlinks.
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

# Only forked dependencies need symlinks.
# All upstream deps are managed by Meson WrapDB .wrap files.
FORK_DEPS=(wlroots virglrenderer proot)

echo "Setting up Meson subproject symlinks for forked deps..."
echo "  Subprojects dir: $SUBPROJECTS_DIR"
echo "  External dir:    $EXTERNAL_DIR"
echo ""

mkdir -p "$SUBPROJECTS_DIR"

created=0
skipped=0
errors=0

for dep in "${FORK_DEPS[@]}"; do
    target="$RELATIVE_PREFIX/$dep"
    link="$SUBPROJECTS_DIR/$dep"

    if [ -L "$link" ]; then
        # Symlink already exists — verify it points to the right place
        existing_target="$(readlink "$link")"
        if [ "$existing_target" = "$target" ]; then
            echo "  OK: $dep (already exists)"
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
