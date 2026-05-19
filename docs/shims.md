# Shim Libraries

WLDroid ships five shim libraries that bridge Linux desktop graphics APIs to Android's graphics stack. These libraries run inside the proot Linux environment via `LD_PRELOAD` and `LD_LIBRARY_PATH`, intercepting system calls and API functions to make Linux applications believe they're running on standard Linux hardware with DRM/GBM support.

## Overview

```
┌──────────────────────────────────────────────────────────────┐
│                     Proot Linux Environment                   │
│                                                              │
│  Linux App → Mesa → GBM → DRM                               │
│                 │     │     │                                 │
│  LD_PRELOAD:    │     │     │                                 │
│  ┌──────────────▼─────▼─────▼────────────────────────┐      │
│  │                                                    │      │
│  │  netstub ──► getifaddrs/freeifaddrs interception   │      │
│  │              (loaded first, all GPU modes)          │      │
│  │                                                    │      │
│  │  drm-shim ──► DRM ioctl interception               │      │
│  │               vtest protocol bridge                │      │
│  │               fake virtio_gpu device               │      │
│  │                                                    │      │
│  └────────────────────────────────────────────────────┘      │
│                                                              │
│  LD_LIBRARY_PATH (/opt/wldroid/lib takes precedence):        │
│  ┌────────────────────────────────────────────────────┐      │
│  │                                                    │      │
│  │  drm-wrapper ──► DT_NEEDED linker for drm-shim     │      │
│  │                  (ensures libdrm loads drm-shim)    │      │
│  │                                                    │      │
│  │  gbm-shim ──► GBM API backed by AHardwareBuffer    │      │
│  │               (replaces system libgbm.so.1)         │      │
│  │                                                    │      │
│  │  egl-override ──► EGL/display interception          │      │
│  │                   (mode-aware behavior)             │      │
│  │                                                    │      │
│  └────────────────────────────────────────────────────┘      │
└──────────────────────────────────────────────────────────────┘
```

## drm-shim (`libdrm-shim.so`)

**~2,900 lines of C** — The most complex shim. Creates a fake `virtio_gpu` DRM device and bridges DRM ioctls to the VirGL vtest protocol.

### Purpose

Linux GPU applications expect to open a DRM device (`/dev/dri/renderD128`). On Android, no DRM device is available. drm-shim intercepts `open()`, `ioctl()`, and related calls to make applications believe they're talking to a `virtio_gpu` DRM device, while actually forwarding GPU commands over the vtest socket to the VirGL server.

### Intercepted Functions (20+)

| Function | Interception |
|----------|-------------|
| `open()` / `open64()` | Intercepts `/dev/dri/*` paths, returns fake fd |
| `close()` | Cleans up per-fd state |
| `ioctl()` | Full virtio_gpu DRM ioctl emulation |
| `drmGetDevices2()` | Returns fake DRM device list |
| `drmGetVersion()` | Returns "virtio_gpu" version |
| `drmGetBusid()` / `drmGetDevice()` | Fake PCI bus/device info |
| `drmPrimeFDToHandle()` | DMA-BUF fd → GEM handle mapping |
| `drmPrimeHandleToFD()` | GEM handle → DMA-BUF fd mapping |
| `drmModeGetResources()` | Empty connector/CRTC list |
| `mmap()` | Maps shm-backed BO memory |
| `fstat()` / `stat()` | Returns fake device IDs for DRI nodes |

### Key Data Structures

```c
struct drm_shim_bo {
    uint32_t handle;
    uint64_t size;
    int shm_fd;           // Shared memory backing
    uint64_t mmap_offset;
    bool is_ahb;          // AHardwareBuffer-backed (zero-copy)
};
```

- **BO (Buffer Object) table:** Tracks all allocated buffers per fd
- **Inode cache (64 entries):** Survives `gem_close` for AHB reimport — essential for zero-copy buffer reuse
- **Multi-fd tracking (32 fds):** Multiple DRM fd opens in the same process

### Wire Protocol

drm-shim communicates with the VirGL server using the vtest v3 protocol over a Unix socket:

```
Guest App → drm-shim → vtest socket → VirGL Server → Android EGL/GLES
```

### Fake Device Tree

drm-shim requires a fake sysfs tree to satisfy `drmGetDevices2()`:

```
/sys/dev/char/226:128/
├── uevent          (DEVNAME=dri/renderD128, MAJOR=226, MINOR=128)
├── dev             (226:128)
└── device/
    └── drm/
        └── renderD128/
            └── dev (226:128)

/dev/dri/
├── renderD128      (regular file — drm-shim intercepts open())
└── card0           (regular file)
```

The fake sysfs uses directories (not symlinks) for `226:128` to avoid proot symlink resolution issues. `VirglServerManager` creates this tree before launching applications.

## drm-wrapper (`libdrm-wrapper.so`)

A minimal empty shared library with `DT_NEEDED` entries that force the dynamic linker to load both `libdrm-shim.so` and the real `libdrm.so.2.X.0`.

### Purpose

Some Linux libraries are linked directly against `libdrm.so.2` and call DRM functions without going through `LD_PRELOAD`. drm-wrapper sits in `LD_LIBRARY_PATH` and ensures those calls are intercepted by drm-shim via the linker's symbol resolution order.

### LIBDRM_SOVERSION Parameterization

Different Linux distributions ship different `libdrm.so.2` soversions:

| Distro | libdrm soversion |
|--------|-----------------|
| Debian Trixie | `libdrm.so.2.124.0` |
| Debian Bookworm | `libdrm.so.2.4.0` |

The soversion is parameterized in the build system and flows from the distro configuration through to the `DT_NEEDED` entry in drm-wrapper.

## gbm-shim (`libgbm.so.1`)

Implements the GBM (Generic Buffer Manager) API backed by Android's `AHardwareBuffer`:

### Purpose

GBM is the standard Linux API for GPU buffer allocation. Applications and libraries call `gbm_bo_create()` to allocate buffers. gbm-shim replaces the system `libgbm.so.1` and backs all allocations with AHardwareBuffers, enabling zero-copy buffer sharing with the compositor.

### Key Behaviors

| GBM Function | Implementation |
|-------------|----------------|
| `gbm_create_device()` | Returns fake device (no real DRM fd needed) |
| `gbm_bo_create()` | `AHardwareBuffer_allocate()` with appropriate usage flags |
| `gbm_bo_create_with_modifiers()` | Linear modifier fallback (AHB doesn't support explicit modifiers) |
| `gbm_bo_get_fd()` | `dup(native_handle_t->data[0])` — returns DMA-BUF fd |
| `gbm_bo_get_stride()` | Queries AHB description for stride |
| `gbm_bo_destroy()` | `AHardwareBuffer_release()` |
| `gbm_surface_*()` | Not implemented (applications use `gbm_bo_*`) |

### Format Mapping

| GBM Format | AHB Format |
|-----------|-----------|
| `GBM_FORMAT_ARGB8888` | `AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM` |
| `GBM_FORMAT_XRGB8888` | `AHARDWAREBUFFER_FORMAT_R8G8B8X8_UNORM` |
| `GBM_FORMAT_RGB565` | `AHARDWAREBUFFER_FORMAT_R5G6B5_UNORM` |
| `GBM_FORMAT_ABGR8888` | `AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM` |
| `GBM_FORMAT_GR88` | `AHARDWAREBUFFER_FORMAT_R8G8_UNORM` |

### Testing

gbm-shim has 64 unit tests across 4 test files covering buffer allocation, format mapping, fd export, and edge cases.

## egl-override (`libegl_override.so`)

**~2,000 lines of C** — A mode-aware EGL interception library that adapts EGL behavior based on the current GPU mode.

### Purpose

Linux applications expect to create EGL displays and surfaces using GBM or native window handles. egl-override intercepts EGL calls and adapts them for Android's graphics stack.

### Behavior by GPU Mode

#### Software Mode
1. Redirects GBM platform → EGL surfaceless platform
2. Creates pbuffer surface + `wl_shm` buffer
3. `glReadPixels()` → row-flip → `wl_surface.commit`
4. Creates `xdg_toplevel` for window management

#### GPU Modes (VirGL / Turnip)
1. Mostly passthrough for EGL calls
2. Creates `xdg_toplevel` for touch event redirect
3. Manages window lifecycle integration

### Implementation

- **Mode detection** — reads `WLDROID_GPU_MODE` (`VIRGL_GLES`, `VIRGL_ZINK`, `TURNIP_DIRECT`, `VENUS`).
- **No headers required** — all function resolution via `dlopen()` / `dlsym()` at runtime
- **Hardcoded Wayland protocol opcodes** — directly speaks Wayland wire protocol without libwayland dependency
- **Self-contained** — no build-time dependencies on EGL or Wayland headers

## netstub (`libnetstub.so`)

**~500 lines of C** — Replaces `getifaddrs()` and `freeifaddrs()` to work around Android SELinux restrictions.

### The Problem

Android 11+ restricts access to the `NETLINK_ROUTE` socket. When Linux applications call `getifaddrs()` (from glibc), it uses `NETLINK` under the hood, which triggers a `SIGABRT` due to SELinux denials in the proot environment.

### The Solution

netstub intercepts `getifaddrs()` and `freeifaddrs()` via `LD_PRELOAD`, replacing the NETLINK-based implementation with one that reads:

- `/proc/net/dev` — network interface names and statistics
- `/proc/net/if_inet6` — IPv6 addresses
- `ioctl(SIOCGIFADDR)` — IPv4 addresses

### Memory Layout

Each `ifaddrs` entry is allocated as a single contiguous block:

```
┌─────────────────────────┐
│ struct ifaddrs           │
├─────────────────────────┤
│ ifa_name (string)       │
├─────────────────────────┤
│ struct sockaddr_in (addr)│
├─────────────────────────┤
│ struct sockaddr_in (mask)│
└─────────────────────────┘
```

### Usage

Loaded in **all GPU modes** — this is not GPU-related but is required for any Linux application in the proot environment that queries network interfaces.

## Shim Loading Configuration

### ShimConfig

Determines which shims to enable per GPU mode:

```kotlin
data class ShimConfig(
    val enableDrmShim: Boolean = true,
    val enableGbmShim: Boolean = true,
    val enableEglOverride: Boolean = true,
    val enableNetstub: Boolean = true,
    val enableDrmWrapper: Boolean = true,
)

// Per-mode defaults
ShimConfig.forGpuMode("SOFTWARE")      // drm=✗ gbm=✗ egl=✗ netstub=✓ wrapper=✗
ShimConfig.forGpuMode("TURNIP_DIRECT") // drm=✓ gbm=✓ egl=✗ netstub=✓ wrapper=✓
ShimConfig.forGpuMode("VIRGL_GLES")    // drm=✓ gbm=✓ egl=✓ netstub=✓ wrapper=✓
```

### ShimExtractor

Extracts shim libraries from APK assets to the proot environment at runtime:

```kotlin
val extractor = ShimExtractor(context)

// Extract all shim libraries
val shims: ShimSet = extractor.extractAll(targetDir)
// shims.drmShim = "/path/to/libdrm-shim.so"
// shims.gbmShim = "/path/to/libgbm.so.1"
// etc.

// Get LD_PRELOAD string for the current GPU mode
val ldPreload = extractor.getLdPreloadString(shims, "VIRGL_GLES")
// "libnetstub.so:libdrm-shim.so"
```

## Build

Shim libraries target Linux glibc aarch64 (not Android bionic). See [Build System](build-system.md) for cross-compilation details.

```bash
# Build all shims
./gradlew :shims:buildNative

# Build with Docker (for reproducibility)
./gradlew :shims:buildNative -PdrmShimDocker -PgbmShimDocker

# Run shim tests (gbm-shim has 64 tests)
cd shims/native/gbm-shim
meson setup builddir
meson test -C builddir
```
