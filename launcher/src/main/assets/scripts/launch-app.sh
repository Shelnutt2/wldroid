#!/bin/bash
# launch-app.sh — Application launch script for WLDroid.
# Sets up LD_PRELOAD, LD_LIBRARY_PATH, and waits for the Wayland socket
# before exec'ing the user's command.
#
# Required env vars:
#   WLDROID_GPU_MODE — one of: SOFTWARE, VIRGL_GLES, VIRGL_ZINK, TURNIP_DIRECT, VENUS
#   XDG_RUNTIME_DIR — Wayland runtime directory (host path, identity-bound by proot)
#   WAYLAND_DISPLAY — Wayland socket name (e.g., wayland-0)
#
# Optional env vars:
#   WLDROID_DEBUG — set to "1" for verbose output and Mesa debug logging
#
# Usage: launch-app.sh <command> [args...]

set -euo pipefail

SHIM_BASE="/opt/wldroid"
LIB_DIR="/usr/lib/aarch64-linux-gnu"

log() {
    echo "[launch-app] $*"
}

debug() {
    if [ "${WLDROID_DEBUG:-0}" = "1" ]; then
        echo "[launch-app][debug] $*"
    fi
}

die() {
    echo "[launch-app][ERROR] $*" >&2
    exit 1
}

# --- Validate required env ---
if [ -z "${WLDROID_GPU_MODE:-}" ]; then
    die "WLDROID_GPU_MODE is not set"
fi
if [ $# -eq 0 ]; then
    die "No command specified"
fi

log "GPU mode: ${WLDROID_GPU_MODE}"
log "Command: $*"

# --- Set LD_LIBRARY_PATH ---
export LD_LIBRARY_PATH="${SHIM_BASE}/lib:${LIB_DIR}${LD_LIBRARY_PATH:+:${LD_LIBRARY_PATH}}"
debug "LD_LIBRARY_PATH=${LD_LIBRARY_PATH}"

# --- Set LD_PRELOAD based on GPU mode ---
LD_PRELOAD_PARTS=""

# drm-shim: all GPU modes (not SOFTWARE)
case "${WLDROID_GPU_MODE}" in
    VIRGL_GLES|VIRGL_ZINK|TURNIP_DIRECT|VENUS)
        LD_PRELOAD_PARTS="${LIB_DIR}/libdrm-shim.so"
        ;;
esac

# netstub: all modes (including SOFTWARE) — works around Android SELinux
if [ -f "${SHIM_BASE}/netstub/libnetstub.so" ]; then
    if [ -n "${LD_PRELOAD_PARTS}" ]; then
        LD_PRELOAD_PARTS="${LD_PRELOAD_PARTS}:${SHIM_BASE}/netstub/libnetstub.so"
    else
        LD_PRELOAD_PARTS="${SHIM_BASE}/netstub/libnetstub.so"
    fi
fi

if [ -n "${LD_PRELOAD_PARTS}" ]; then
    export LD_PRELOAD="${LD_PRELOAD_PARTS}"
    debug "LD_PRELOAD=${LD_PRELOAD}"
fi

# --- Set LIBGL_DRIVERS_PATH ---
export LIBGL_DRIVERS_PATH="${LIB_DIR}/dri"
debug "LIBGL_DRIVERS_PATH=${LIBGL_DRIVERS_PATH}"

# --- Vulkan ICD file discovery ---
# The host may set VK_DRIVER_FILES to a specific ICD path that doesn't exist
# in the guest rootfs (e.g., different naming between Debian versions).
# If the configured file is missing, search for the actual file.
if [ -n "${VK_DRIVER_FILES:-}" ] && [ ! -f "${VK_DRIVER_FILES}" ]; then
    ICD_BASENAME=$(basename "${VK_DRIVER_FILES}" | sed 's/\.[^.]*\.json$/*.json/')
    ICD_DIR=$(dirname "${VK_DRIVER_FILES}")
    FOUND_ICD=""
    for f in "${ICD_DIR}"/${ICD_BASENAME}; do
        if [ -f "$f" ]; then
            FOUND_ICD="$f"
            break
        fi
    done
    if [ -n "${FOUND_ICD}" ]; then
        log "VK_DRIVER_FILES: ${VK_DRIVER_FILES} not found, using ${FOUND_ICD}"
        export VK_DRIVER_FILES="${FOUND_ICD}"
    else
        debug "VK_DRIVER_FILES: ${VK_DRIVER_FILES} not found, no alternative discovered in ${ICD_DIR}"
    fi
fi

debug "VK_DRIVER_FILES=${VK_DRIVER_FILES:-<unset>}"

# --- Browser/toolkit GPU workarounds ---
# Firefox/Gecko: enable native Wayland backend and disable WebRender
# (WebRender crashes with VirGL translation layers).
export MOZ_ENABLE_WAYLAND=1
case "${WLDROID_GPU_MODE}" in
    SOFTWARE)
        export MOZ_WEBRENDER=0
        export LIBGL_ALWAYS_SOFTWARE=1
        ;;
    VIRGL_GLES|VIRGL_ZINK)
        export MOZ_WEBRENDER=0
        ;;
esac

# Chromium/Electron: disable sandbox (incompatible with proot's ptrace)
export ELECTRON_NO_SANDBOX=1

# --- Debug mode: enable Mesa/EGL verbose logging ---
if [ "${WLDROID_DEBUG:-0}" = "1" ]; then
    export MESA_DEBUG=1
    export EGL_LOG_LEVEL=debug
    export LIBGL_DEBUG=verbose
    log "Debug logging enabled"
fi

# --- X11/XWayland compatibility ---
# Disable xauth requirement for X11 apps running through XWayland.
# XWayland and clients share the same socket namespace so no auth is needed.
if [ -n "${DISPLAY:-}" ]; then
    export XAUTHORITY=/dev/null
    debug "DISPLAY=${DISPLAY}, XAUTHORITY set to /dev/null"
fi

# --- Wait for Wayland socket ---
if [ -n "${XDG_RUNTIME_DIR:-}" ] && [ -n "${WAYLAND_DISPLAY:-}" ]; then
    SOCKET_PATH="${XDG_RUNTIME_DIR}/${WAYLAND_DISPLAY}"
    WAIT_TIMEOUT=30
    WAITED=0

    while [ ! -e "${SOCKET_PATH}" ] && [ "${WAITED}" -lt "${WAIT_TIMEOUT}" ]; do
        debug "Waiting for Wayland socket: ${SOCKET_PATH} (${WAITED}s)"
        sleep 1
        WAITED=$((WAITED + 1))
    done

    if [ -e "${SOCKET_PATH}" ]; then
        log "Wayland socket ready: ${SOCKET_PATH}"
    else
        log "Warning: Wayland socket not found after ${WAIT_TIMEOUT}s: ${SOCKET_PATH}"
    fi
else
    debug "XDG_RUNTIME_DIR or WAYLAND_DISPLAY not set, skipping socket wait"
fi

# --- Exec the user's command ---
log "Launching: $*"
exec "$@"
