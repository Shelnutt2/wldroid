#!/bin/bash
# launch-app.sh — Application launch script for WLDroid.
# Sets up LD_PRELOAD, LD_LIBRARY_PATH, and waits for the Wayland socket
# before exec'ing the user's command.
#
# Required env vars:
#   WLDROID_GPU_MODE — one of: SOFTWARE, VIRGL_GLES, VIRGL_ZINK, TURNIP_DIRECT, VENUS
#   XDG_RUNTIME_DIR — Wayland runtime directory (typically /tmp/xdg-runtime)
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

# --- Debug mode: enable Mesa/EGL verbose logging ---
if [ "${WLDROID_DEBUG:-0}" = "1" ]; then
    export MESA_DEBUG=1
    export EGL_LOG_LEVEL=debug
    export LIBGL_DEBUG=verbose
    log "Debug logging enabled"
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
