#!/bin/bash
# setup-gpu.sh — GPU setup script for WLDroid.
# Runs inside a proot environment to install Mesa packages,
# copy shim libraries to system paths, and create DRI symlinks.
#
# Required env vars:
#   WLDROID_GPU_MODE — one of: VIRGL_GLES, VIRGL_ZINK, TURNIP_DIRECT, VENUS
#
# Optional env vars:
#   WLDROID_SKIP_MESA_INSTALL — set to "1" to skip apt-get
#   WLDROID_DEBUG — set to "1" for verbose output

set -euo pipefail

SHIM_BASE="/opt/wldroid"
LIB_DIR="/usr/lib/aarch64-linux-gnu"
DRI_DIR="${LIB_DIR}/dri"
VERSION_FILE="${SHIM_BASE}/.shim_versions"

log() {
    echo "[setup-gpu] $*"
}

debug() {
    if [ "${WLDROID_DEBUG:-0}" = "1" ]; then
        echo "[setup-gpu][debug] $*"
    fi
}

die() {
    echo "[setup-gpu][ERROR] $*" >&2
    exit 1
}

# --- Validate required env ---
if [ -z "${WLDROID_GPU_MODE:-}" ]; then
    die "WLDROID_GPU_MODE is not set"
fi

log "GPU mode: ${WLDROID_GPU_MODE}"

# --- Create /dev/dri stubs (safety net) ---
create_dri_stubs() {
    if [ ! -d /dev/dri ]; then
        mkdir -p /dev/dri
        debug "Created /dev/dri directory"
    fi
    for dev in renderD128 card0; do
        if [ ! -e "/dev/dri/${dev}" ]; then
            touch "/dev/dri/${dev}"
            chmod 666 "/dev/dri/${dev}"
            debug "Created stub /dev/dri/${dev}"
        fi
    done
}
create_dri_stubs

# --- Mesa package installation ---
install_mesa_packages() {
    if [ "${WLDROID_SKIP_MESA_INSTALL:-0}" = "1" ]; then
        log "Skipping Mesa package installation (WLDROID_SKIP_MESA_INSTALL=1)"
        return 0
    fi

    local packages=""
    case "${WLDROID_GPU_MODE}" in
        VIRGL_GLES|VIRGL_ZINK)
            packages="libegl-mesa0 libgl1-mesa-dri libgles2 libgbm1 mesa-vulkan-drivers mesa-utils"
            ;;
        TURNIP_DIRECT)
            packages="mesa-vulkan-drivers libegl-mesa0 libgl1-mesa-dri libgles2 libgbm1"
            ;;
        VENUS)
            packages="mesa-vulkan-drivers libegl-mesa0 libgles2 libgbm1"
            ;;
        *)
            log "No Mesa packages needed for mode ${WLDROID_GPU_MODE}"
            return 0
            ;;
    esac

    # Check if all packages are already installed
    local all_installed=true
    for pkg in ${packages}; do
        if ! dpkg -s "${pkg}" >/dev/null 2>&1; then
            all_installed=false
            break
        fi
    done

    if [ "${all_installed}" = "true" ]; then
        log "All Mesa packages already installed"
        return 0
    fi

    log "Installing Mesa packages: ${packages}"
    apt-get update -qq || log "Warning: apt-get update failed, continuing..."
    # shellcheck disable=SC2086
    apt-get install -y --no-install-recommends ${packages}
    log "Mesa packages installed"
}
install_mesa_packages

# --- Shim copy with md5sum caching ---
# Reads current version file into an associative array.
declare -A CURRENT_VERSIONS
load_versions() {
    if [ -f "${VERSION_FILE}" ]; then
        while IFS='=' read -r key value; do
            [ -n "${key}" ] && CURRENT_VERSIONS["${key}"]="${value}"
        done < "${VERSION_FILE}"
    fi
}

# Copies a shim library if the checksum differs from the cached version.
# Args: $1=source $2=destination $3=version_key
copy_if_changed() {
    local src="$1" dst="$2" key="$3"

    if [ ! -f "${src}" ]; then
        debug "Source not found: ${src}, skipping"
        return 0
    fi

    local src_md5
    src_md5=$(md5sum "${src}" | awk '{print $1}')
    local cached_md5="${CURRENT_VERSIONS[${key}]:-}"

    if [ "${src_md5}" = "${cached_md5}" ] && [ -f "${dst}" ]; then
        debug "Shim ${key} unchanged (md5=${src_md5})"
        return 0
    fi

    local dst_dir
    dst_dir=$(dirname "${dst}")
    mkdir -p "${dst_dir}"
    cp -f "${src}" "${dst}"
    chmod 755 "${dst}"
    CURRENT_VERSIONS["${key}"]="${src_md5}"
    log "Copied ${key}: ${src} → ${dst}"
}

# --- Install shim libraries ---
install_shims() {
    load_versions

    # drm-shim → system lib path (referenced by LD_PRELOAD in launch-app.sh)
    copy_if_changed \
        "${SHIM_BASE}/drm-shim/libdrm-shim.so" \
        "${LIB_DIR}/libdrm-shim.so" \
        "drm-shim"

    # drm-wrapper → /opt/wldroid/lib/ (LD_LIBRARY_PATH picks it up)
    mkdir -p "${SHIM_BASE}/lib"
    copy_if_changed \
        "${SHIM_BASE}/drm-shim/libdrm-wrapper.so" \
        "${SHIM_BASE}/lib/libdrm.so.2" \
        "drm-wrapper"

    # egl-override → /opt/wldroid/lib/ (VirGL modes only)
    case "${WLDROID_GPU_MODE}" in
        VIRGL_GLES|VIRGL_ZINK|VENUS)
            copy_if_changed \
                "${SHIM_BASE}/egl-override/libegl_override.so" \
                "${SHIM_BASE}/lib/libEGL.so.1" \
                "egl-override"
            ;;
        *)
            debug "Skipping egl-override for mode ${WLDROID_GPU_MODE}"
            ;;
    esac

    # Write updated version cache
    mkdir -p "$(dirname "${VERSION_FILE}")"
    : > "${VERSION_FILE}"
    for key in "${!CURRENT_VERSIONS[@]}"; do
        echo "${key}=${CURRENT_VERSIONS[${key}]}" >> "${VERSION_FILE}"
    done
    debug "Version cache written to ${VERSION_FILE}"
}
install_shims

# --- Create virtio_gpu_dri.so symlink ---
create_dri_symlink() {
    mkdir -p "${DRI_DIR}"
    if [ -f "${DRI_DIR}/armv8_dri.so" ] && [ ! -e "${DRI_DIR}/virtio_gpu_dri.so" ]; then
        ln -s armv8_dri.so "${DRI_DIR}/virtio_gpu_dri.so"
        log "Created DRI symlink: virtio_gpu_dri.so → armv8_dri.so"
    else
        debug "DRI symlink already exists or armv8_dri.so not found"
    fi
}
create_dri_symlink

log "GPU setup complete for ${WLDROID_GPU_MODE}"
exit 0
