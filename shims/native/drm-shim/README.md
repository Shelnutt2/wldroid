# drm-shim — Lightweight libdrm Interceptor for Proot GPU Acceleration

Apps like Chromium, Firefox, and SDL2 call `drmGetDevices2()` to probe for GPU
devices. Our `/dev/dri` overlay provides stub files that satisfy `stat()`/`glob()`
checks, but `drmGetDevices2()` goes further — reading sysfs and calling ioctls.
This library intercepts at the **libdrm API level** via `LD_PRELOAD`, returning
fake device info that satisfies GPU probes without requiring a real kernel driver.

> **⚠️ Driver Name:** The driver name MUST be `"coder_proot_gpu"` — NOT
> `"virtio_gpu"`. If Mesa sees `"virtio_gpu"` from `drmGetVersion()`, it loads
> `virgl_drm_winsys` (which requires a real kernel driver) instead of
> `virgl_vtest_winsys` (which uses the socket connection). This would crash.

## How It Works

```
App calls drmGetDevices2() → libdrm-shim.so intercepts via LD_PRELOAD
  → returns 1 fake platform device at /dev/dri/renderD128
App calls drmGetVersion(fd) → returns "coder_proot_gpu" v1.0
  → Mesa DRI loader doesn't recognize driver → gives up
  → GALLIUM_DRIVER=virpipe takes over → vtest socket → virgl → real GPU
```

## Intercepted Functions

| Function | Return | Notes |
|----------|--------|-------|
| `drmGetDevices2()` | 1 fake platform device | Single `calloc` block with render + card nodes |
| `drmGetDevices()` | delegates to `drmGetDevices2(0, …)` | Legacy wrapper |
| `drmGetVersion()` | `"coder_proot_gpu"` v1.0.0 | All fds return the same fake version |
| `drmGetCap()` | PRIME import/export, syncobj, dumb buffer | Returns `-EINVAL` for unknown caps |
| `drmGetMagic()` | `0x434F4445` ("CODE") | Fake magic token |
| `drmAuthMagic()` | 0 (success) | Always succeeds — no real auth needed |
| `drmPrimeHandleToFD()` | `memfd_create` anonymous fd | Valid fd for DMA-BUF protocol; rendering goes via virpipe |
| `drmPrimeFDToHandle()` | Atomic counter handle | Monotonically increasing fake handles |
| `drmGetBusid()` | `"platform:coder-proot-gpu:00"` | Platform bus type |
| `drmGetNodeTypeFromFd()` | `DRM_NODE_RENDER` | Always render node |
| `drmGetRenderDeviceNameFromFd()` | `"/dev/dri/renderD128"` | — |
| `drmGetDeviceNameFromFd2()` | `"/dev/dri/renderD128"` | — |
| `drmIoctl()` | 0 | Succeed silently for all ioctls |
| `drmClose()` | `close(fd)` | Just closes the real fd |
| `drmFreeDevices()` | — | Single `free()` per device |
| `drmFreeVersion()` | — | Frees version struct + strings |
| `drmFreeBusid()` | — | Frees strdup'd bus ID |

## Design Decisions

- **Replace, don't wrap**: No `dlsym(RTLD_NEXT)` — functions we don't intercept
  fall through to the real `libdrm.so.2` naturally via dynamic linker resolution.
- **No fd tracking**: All fds get fake responses since there are no real DRM
  devices in proot. Simpler than tracking which fds are "ours" with no downside.
- **Single allocation**: Each device from `drmGetDevices2` is one `calloc` block
  (struct + node paths + bus info). Internal pointers reference within the same
  allocation — `drmFreeDevices` does a single `free()` per device.
- **Thread safety**: Only mutable state is an atomic counter for PRIME handles
  (`__atomic_fetch_add`). Each function allocates its own return structs.

## Building

### Proot deployment (production)

Compiled by `setup-gpu.sh` inside proot using the system toolchain:

```bash
gcc -shared -fPIC -O2 -o /usr/lib/aarch64-linux-gnu/libdrm-shim.so \
    src/drm_shim.c -Iinclude $(pkg-config --cflags libdrm) -lpthread
```

The compiled `.so` is cached — subsequent launches skip compilation.

### Local development (meson)

```bash
meson setup builddir -Dtests=true
meson compile -C builddir
meson test -C builddir
```

## Testing

Three test suites validate the intercepted functions:

| Suite | Coverage |
|-------|----------|
| `test_drm_devices` | Device enumeration, node paths, bus info, memory cleanup |
| `test_drm_version` | Version strings, driver name, capability queries |
| `test_drm_prime` | PRIME handle/fd round-trip, memfd validity, auth stubs |

Run via meson (`meson test -C builddir`) or the standalone script:

```bash
./tests/run_tests.sh builddir
```

## Relationship to Other Components

- **gbm-shim** (`virgl/gbm-shim/`): Intercepts the GBM API (buffer allocation).
  drm-shim intercepts the DRM API (device enumeration). Both are `LD_PRELOAD`
  libraries with different targets.
- **virgl_test_server** (`virgl/build-virgl.sh`): Cross-compiled for Android,
  provides the vtest socket. drm-shim helps apps *find* the fake device; actual
  rendering goes through virgl_test_server.
- **setup-gpu.sh**: Compiles drm-shim from source inside proot during first-run
  GPU setup.
- **launch-vscode.sh**: Sets `LD_PRELOAD` to load the compiled shim at runtime.

## License

Same as the parent project.
