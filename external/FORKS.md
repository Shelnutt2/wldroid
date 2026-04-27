# Forked Dependencies — Patch Documentation

Three native dependencies require Android-specific patches that are not suitable
for upstream. Each is maintained as a fork with a dedicated branch.

---

## wlroots

- **Upstream:** https://gitlab.freedesktop.org/wlroots/wlroots
- **Fork:** https://github.com/Shelnutt2/wlroots.git
- **Branch:** `android-0.19.2` (based on upstream tag `0.19.2`)

### Why a fork is needed

wlroots assumes a Linux desktop environment with DRM/KMS, libgbm, and POSIX
shared memory. Android lacks DRM render nodes, uses AHardwareBuffer instead
of GBM, and bionic libc has limited shm/memfd support. These patches adapt
wlroots to run as an embedded Wayland compositor inside an Android app.

### Patches

| Patch | Summary |
|-------|---------|
| `0010-android-build-fixes.patch` | Makes `librt` optional (Android bionic doesn't have it), fixes GLESv2 discovery to fall back to `find_library('GLESv2')`, and adds Android-compatible EGL platform detection. |
| `0020-android-shm-compat.patch` | Implements `allocate_shm_file()` and `allocate_shm_file_pair()` using `memfd_create` via raw syscall on Android (bionic lacks `shm_open` before API 30). |
| `0030-android-egl-dmabuf-compat.patch` | Guards `#include <gbm.h>` and GBM-dependent EGL initialization behind `#ifndef __ANDROID__`. Allows EGL display creation without `EGL_EXT_platform_base` on Android. Replaces GBM platform with direct `eglGetDisplay(EGL_DEFAULT_DISPLAY)`. |
| `0040-android-ahb-import.patch` | Adds AHardwareBuffer-based buffer import path to the GLES2 renderer. Imports dmabuf fds by looking up the originating AHB via an inode-based registry, then creating an EGL image from the native buffer. Falls back to standard dmabuf import when AHB lookup fails. |
| `0050-android-dmabuf-validation.patch` | Skips DRM handle validation for dmabuf attributes on Android (no `drmPrimeFDToHandle` available). The single-app trust model makes this safe. |
| `0060-android-dmabuf-feedback.patch` | Skips DRM device open for dmabuf feedback compilation on Android. Wraps `drmGetDeviceFromDevId` / `drmFreeDevice` calls in `#ifndef __ANDROID__` guards. |
| `0070-dmabuf-cpu-fallback.patch` | When EGL dmabuf image import fails (common with certain AHB formats), falls back to CPU-side `begin_data_ptr_access` texture upload instead of failing. Adds `mapped_data`/`mapped_size` fields to `wlr_dmabuf_buffer` for mmap-based access. |
| `0080-egl-dmabuf-error-diagnostic.patch` | Enhances `eglCreateImageKHR` failure logging to include EGL error code, format, dimensions, modifier, plane count, and fd — critical for debugging Android GPU driver issues. |

---

## virglrenderer

- **Upstream:** https://gitlab.freedesktop.org/virgl/virglrenderer
- **Fork:** https://github.com/Shelnutt2/virglrenderer.git
- **Branch:** `android-v1.1.0` (based on upstream tag `v1.1.0`)

### Why a fork is needed

virglrenderer assumes DRM render nodes for GPU access and GBM for buffer
allocation. On Android, GPU access is via EGL/GLES and buffers are backed by
AHardwareBuffer. These patches replace DRM/GBM paths with Android equivalents
and add AHB export for zero-copy display compositing.

### Patches

| Patch | Summary |
|-------|---------|
| `0010-android-egl-display.patch` | Modifies EGL initialization to skip `EGL_KHR_surfaceless_context` check on Android (surfaceless is a core EGL 1.5 feature). Uses `eglGetDisplay(EGL_DEFAULT_DISPLAY)` instead of platform-specific display creation. |
| `0020-android-gbm-shim.patch` | Guards DRM-dependent code (`#include <xf86drm.h>`, render node discovery via `/dev/dri/`) behind `#ifndef __ANDROID__`. Allows the GBM shim layer to operate without real DRM devices. |
| `0030-android-gbm-alloc-preference.patch` | On Android, prefers external GBM/AHB allocation for all renderable resources (render targets, sampler views) — not just scanout/shared — to enable zero-copy AHB export. |
| `0040-android-venus-memfd.patch` | Provides `memfd_create` via raw syscall for Venus (Vulkan) device memory allocation, since Android API 29 doesn't expose it in `<sys/mman.h>`. |
| `0045-android-skip-memfd-seals.patch` | Skips `F_GET_SEALS` / `F_ADD_SEALS` validation on Android memfds (kernel may return EINVAL). Size-check-only validation is sufficient for the single-app trust model. |
| `0046-android-venus-gbm-fallback.patch` | Falls back to `gbm_create_device(-1)` when minigbm device creation fails on Android. The GBM shim accepts fd=-1 since it uses AHB backing instead of DRM. |
| `0050-android-ahb-egl-image.patch` | Creates EGL images directly from AHardwareBuffer (via `EGL_NATIVE_BUFFER_ANDROID`) instead of going through dmabuf export, avoiding the need for `drmPrimeHandleToFD`. |
| `0060-android-ahb-export-fd.patch` | Uses `gbm_bo_get_fd()` (which extracts the dmabuf fd from AHB's native handle) for resource export instead of `virgl_gbm_export_fd()` which requires DRM. |
| `0070-android-ahb-vtest-export.patch` | Adds AHB-backed dmabuf export to the vtest protocol. Tries `virgl_renderer_resource_export_blob()` for scanout resources and sends the dmabuf fd to the compositor via the vtest socket. |
| `0080-android-ahb-registry.patch` | Adds an AHB registry client that communicates with the compositor's AHB registry socket. When an EGL image is created from an AHB, the fd's inode is sent to the registry so the compositor can look up the original AHB for zero-copy import. |

---

## proot

- **Upstream:** https://github.com/proot-me/proot
- **Fork:** https://github.com/Shelnutt2/proot.git
- **Branch:** `android` (based on upstream main)

### Why a fork is needed

Minor build fix needed for Android NDK compilation.

### Patches

| Patch | Summary |
|-------|---------|
| `001-fix-implicit-declarations.patch` | Adds missing `#include <string.h>` in `ashmem_memfd.c` to fix implicit declaration warnings/errors with newer NDK versions. |

---

## Creating the Forks

To create a new fork with patches applied:

```bash
# Example for wlroots
git clone https://gitlab.freedesktop.org/wlroots/wlroots.git
cd wlroots
git checkout 0.19.2
git checkout -b android-0.19.2

# Apply patches in order
for patch in /path/to/patches/wlroots/*.patch; do
    git apply "$patch"
    git add -A
    git commit -m "$(basename "$patch" .patch | sed 's/-/ /g; s/^[0-9]* //')"
done

# Push to fork
git remote add fork https://github.com/Shelnutt2/wlroots.git
git push fork android-0.19.2
```

Repeat for virglrenderer (tag `v1.1.0`, branch `android-v1.1.0`) and proot
(branch `android`).

## Rebasing to a New Upstream Release

```bash
cd external/wlroots
git fetch upstream
git rebase --onto <new-tag> <old-tag> android-<new-tag>
# Resolve conflicts, then force-push the fork branch
git push fork android-<new-tag> --force-with-lease
```

Update `.gitmodules` branch reference and the submodule commit pointer.
