# gbm-shim — AHardwareBuffer-backed GBM for Android

A static shim library that implements the standard
[GBM (Generic Buffer Management)](https://www.khronos.org/registry/EGL/extensions/MESA/EGL_MESA_platform_gbm.txt)
API backed by Android's `AHardwareBuffer`, enabling virglrenderer and other
GBM consumers to build and run on Android without modification.

## Architecture

```
┌──────────────────────────┐
│     virglrenderer        │
│  (calls standard GBM)    │
└────────────┬─────────────┘
             │  gbm_bo_create(), gbm_bo_get_fd(), ...
             ▼
┌──────────────────────────┐
│      gbm-shim            │
│  (this library)          │
│  libgbm.a + gbm.pc       │
└────────────┬─────────────┘
             │  AHardwareBuffer_allocate(), _lock(), etc.
             ▼
┌──────────────────────────┐
│   Android NDK / HAL      │
│  AHardwareBuffer API     │
└──────────────────────────┘
```

## Why?

Android provides `AHardwareBuffer` for GPU buffer allocation but has no
native GBM library. virglrenderer's EGL platform requires `libgbm` at build
time (via pkg-config `dependency('gbm')`). This shim satisfies that
requirement by translating GBM calls into AHardwareBuffer operations.

## Format Mapping

DRM uses memory-layout naming while AHB uses component-order naming:

| GBM Format             | AHB Format                  | BPP |
|------------------------|-----------------------------|-----|
| `GBM_FORMAT_ABGR8888`  | `R8G8B8A8_UNORM`            | 4   |
| `GBM_FORMAT_XBGR8888`  | `R8G8B8X8_UNORM`            | 4   |
| `GBM_FORMAT_RGB565`    | `R5G6B5_UNORM`              | 2   |
| `GBM_FORMAT_BGR888`    | `R8G8B8_UNORM`              | 3   |
| `GBM_FORMAT_ABGR16161616F` | `R16G16B16A16_FLOAT`    | 8   |
| `GBM_FORMAT_XBGR16161616F` | `R16G16B16A16_FLOAT`    | 8   |
| `GBM_FORMAT_R8`        | `BLOB`                      | 1   |
| `GBM_FORMAT_ABGR2101010` | `R10G10B10A2_UNORM`       | 4   |

## API Coverage

### Fully Implemented

| Function | Notes |
|----------|-------|
| `gbm_create_device` | Any fd value accepted (-1 valid on Android) |
| `gbm_device_destroy` | Direct free, NULL-safe |
| `gbm_device_get_fd` | Returns stored fd |
| `gbm_device_get_backend_name` | Returns `"android"` |
| `gbm_device_is_format_supported` | Checks format table |
| `gbm_bo_create` | Full AHB allocation with usage mapping |
| `gbm_bo_create_with_modifiers` | Falls back to linear allocation |
| `gbm_bo_create_with_modifiers2` | Merges flags with LINEAR |
| `gbm_bo_destroy` | Auto-unlocks if mapped, releases AHB, fires user_data callback |
| `gbm_bo_get_width` | From AHB descriptor |
| `gbm_bo_get_height` | From AHB descriptor |
| `gbm_bo_get_format` | Original GBM format stored |
| `gbm_bo_get_stride` | Bytes per row (AHB pixel stride × bpp) |
| `gbm_bo_get_stride_for_plane` | Plane 0 only |
| `gbm_bo_get_plane_count` | Always returns 1 |
| `gbm_bo_get_offset` | Always returns 0 |
| `gbm_bo_get_modifier` | Returns `DRM_FORMAT_MOD_INVALID` (AHB doesn't expose tiling info) |
| `gbm_bo_get_handle` | Returns AHB pointer |
| `gbm_bo_get_handle_for_plane` | Plane 0 only |
| `gbm_bo_get_device` | Returns parent device |
| `gbm_bo_get_fd` | DMA-BUF via dlsym(getNativeHandle) |
| `gbm_bo_get_fd_for_plane` | Plane 0 only |
| `gbm_bo_map` | AHardwareBuffer_lock with ARect |
| `gbm_bo_unmap` | AHardwareBuffer_unlock |
| `gbm_bo_set_user_data` | Trivial store |
| `gbm_bo_get_user_data` | Trivial load |
| `gbm_device_get_format_modifier_plane_count` | Returns 1 for known formats |

### Stubs (return error / NULL)

| Function | Returns |
|----------|---------|
| `gbm_bo_import` | `NULL`, sets `errno = ENOSYS` |
| `gbm_bo_write` | `-1`, sets `errno = ENOSYS` |

## Building

### As a standalone project (cross-compile for Android)

```bash
# Set up NDK cross file (see Meson docs)
meson setup builddir \
    --cross-file android-aarch64.txt \
    -Dtests=false

meson compile -C builddir
```

### As part of the virgl build script

The parent `build-virgl.sh` builds gbm-shim first and points virglrenderer's
pkg-config at the resulting `gbm.pc`.

## Testing

Tests require an Android device (AHardwareBuffer is not available on desktop).

```bash
# Build with tests enabled
meson setup builddir --cross-file android-aarch64.txt -Dtests=true
meson compile -C builddir

# Push and run on device
adb push builddir/test_gbm_device /data/local/tmp/
adb push builddir/test_gbm_bo /data/local/tmp/
adb push builddir/test_gbm_formats /data/local/tmp/
adb push builddir/test_gbm_map /data/local/tmp/

adb shell /data/local/tmp/test_gbm_device
adb shell /data/local/tmp/test_gbm_bo
adb shell /data/local/tmp/test_gbm_formats
adb shell /data/local/tmp/test_gbm_map

# Or use the test runner script:
adb push tests/run_tests.sh /data/local/tmp/
adb shell "cd /data/local/tmp && sh run_tests.sh ."
```

## Thread Safety

- **Device lifecycle**: single-owner, direct free on destroy.
- **dlsym loading**: uses `pthread_once` for one-time initialization.
- **Buffer operations**: not internally synchronized (caller responsibility,
  per standard GBM convention — one thread per BO at a time).

## Known Limitations

- **Single plane only** — multi-plane formats (NV12, YUV420) not supported
- **No import** — `gbm_bo_import()` returns NULL/ENOSYS
- **No explicit modifiers** — always reports `DRM_FORMAT_MOD_INVALID` (AHB does not expose tiling information)
- **DMA-BUF fd** — requires `AHardwareBuffer_getNativeHandle` (system API,
  present on all Android 8+ but not in public NDK headers; loaded via dlsym)
- **Excluded formats** — `ARGB8888`, `XRGB8888`, `RGB888`, and `GR88` are deliberately
  excluded due to DRM↔AHB byte-order mismatches (DRM uses memory-layout naming,
  AHB uses component-order naming). Only `ABGR*` variants are safe to map.

## License

Same as the parent project.
