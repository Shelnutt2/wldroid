# GPU Rendering Architecture

WLDroid supports five GPU rendering modes that determine how Linux applications running inside the proot environment access GPU acceleration. The mode is either auto-detected based on device capabilities or explicitly selected by the user.

## The Five GPU Modes

| Mode | Display Name | VirGL Server | Shims Required | Performance | Compatibility |
|------|-------------|-------------|----------------|-------------|---------------|
| `SOFTWARE` | Software | No | netstub only | ★☆☆☆☆ | Universal |
| `VIRGL_GLES` | VirGL GLES | Yes | All | ★★★☆☆ | Broad |
| `VIRGL_ZINK` | VirGL Zink | Yes | All | ★★★★☆ | Vulkan devices |
| `VENUS` | Venus | Yes | All | ★★★★☆ | Experimental |
| `TURNIP_DIRECT` | Turnip Direct | No | netstub, egl-override | ★★★★★ | Qualcomm Adreno only |

## Mode Selection Flow

```
                    ┌──────────────┐
                    │ User Override│
                    │ (GpuModeStore)│
                    └──────┬───────┘
                           │
                    ┌──────▼───────┐     Yes    ┌──────────────┐
                    │ Override set?├────────────►│ Use override │
                    └──────┬───────┘            └──────────────┘
                           │ No
                    ┌──────▼───────┐
                    │ Auto-detect  │
                    │ (GpuCap.Det.)│
                    └──────┬───────┘
                           │
                ┌──────────▼──────────┐
                │ /dev/kgsl-3d0       │  Yes   ┌────────────────┐
                │ accessible + Adreno?├───────►│ TURNIP_DIRECT  │
                └──────────┬──────────┘        └────────────────┘
                           │ No
                ┌──────────▼──────────┐
                │ Vulkan support +    │  Yes   ┌────────────────┐
                │ VirGL available?    ├───────►│ VIRGL_ZINK     │
                └──────────┬──────────┘        └────────────────┘
                           │ No
                ┌──────────▼──────────┐
                │ VirGL available?    │  Yes   ┌────────────────┐
                │                     ├───────►│ VIRGL_GLES     │
                └──────────┬──────────┘        └────────────────┘
                           │ No
                    ┌──────▼───────┐
                    │   SOFTWARE   │
                    └──────────────┘
```

**Note:** Venus is never auto-selected — it must be explicitly chosen due to its experimental status.

## SOFTWARE Mode

CPU-only rendering using llvmpipe (Mesa software rasterizer) or pixman. Universal fallback that works on all devices.

### Pipeline

```
┌──────────────────────────────────────────────────────────┐
│                     Proot Environment                     │
│  ┌────────────────┐                                      │
│  │ Linux App      │                                      │
│  │ (llvmpipe)     │  CPU rendering                       │
│  │                │                                      │
│  │ wl_shm_buffer ─┼──────────────────────────────┐      │
│  └────────────────┘                              │      │
│                                                  │      │
│  Shims: netstub only                             │      │
└──────────────────────────────────────────────────┼──────┘
                                                   │
                                          Wayland Socket
                                                   │
┌──────────────────────────────────────────────────▼──────┐
│                      Compositor                          │
│  pixman composite → memcpy → ANativeWindow              │
│  (all CPU, no GPU)                                      │
└─────────────────────────────────────────────────────────┘
```

### Environment Variables
```bash
GALLIUM_DRIVER=llvmpipe
MESA_GL_VERSION_OVERRIDE=4.5
LIBGL_ALWAYS_SOFTWARE=1
```

### When to Use
- Device lacks any GPU capability detection
- Debugging rendering issues (eliminates GPU as variable)
- Running in emulators without GPU passthrough

## VIRGL_GLES Mode

OpenGL ES commands are forwarded from guest applications to the Android host GPU via the VirGL vtest protocol. The VirGL server renders using Android's EGL/GLES, and buffer sharing uses AHardwareBuffers for zero-copy compositing.

### Pipeline

```
┌──────────────────────────────────────────────────────────┐
│                     Proot Environment                     │
│  ┌────────────────┐                                      │
│  │ Linux App      │                                      │
│  │ (Mesa virgl)   │  GL calls                            │
│  │                ├──────┐                               │
│  └────────────────┘      │ vtest protocol                │
│                          │                               │
│  Shims: drm-shim → drm-wrapper → gbm-shim               │
│         netstub, egl-override                            │
│                          │                               │
└──────────────────────────┼───────────────────────────────┘
                           │ VirGL Socket
┌──────────────────────────▼───────────────────────────────┐
│                    VirGL Server                           │
│  virgl_test_server --use-egl-surfaceless --use-gles      │
│                                                          │
│  ┌──────────────────────────────────────────────┐       │
│  │ EGLImage ← AHardwareBuffer                   │       │
│  │ GL render into AHB (zero-copy)               │       │
│  │                                               │       │
│  │ Register AHB with compositor ─────────────────┼───┐  │
│  └──────────────────────────────────────────────┘   │  │
└─────────────────────────────────────────────────────┼──┘
                                                      │
                                          AHB Registry Socket
                                                      │
┌─────────────────────────────────────────────────────▼──┐
│                      Compositor                         │
│  Lookup AHB by inode → EGLImage import → GL composite  │
│  → ANativeWindow (zero GPU-to-CPU copies)              │
└────────────────────────────────────────────────────────┘
```

### Environment Variables
```bash
GALLIUM_DRIVER=virgl
MESA_GL_VERSION_OVERRIDE=4.5
VTEST_SOCK=/tmp/.virgl_test
```

### AHardwareBuffer Zero-Copy Pipeline

The key innovation in WLDroid's VirGL integration is zero-copy buffer sharing:

1. **Guest app** calls `gbm_bo_create()` (intercepted by gbm-shim)
2. **gbm-shim** calls `AHardwareBuffer_allocate()` on Android
3. **VirGL server** creates `EGLImage` from the AHB and renders GL commands into it
4. **VirGL server** registers the AHB with the compositor via Unix socket (inode + SCM_RIGHTS handle)
5. **Guest app** commits the surface with a DMA-BUF fd (from `native_handle_t->data[0]`)
6. **Compositor** looks up the AHB by inode in the registry
7. **Compositor** imports as `EGLImage`, composites as GL texture, presents to `ANativeWindow`

No pixel data is ever copied between GPU and CPU — the AHardwareBuffer is shared between all three processes.

## VIRGL_ZINK Mode

Similar to VIRGL_GLES but uses the Zink Gallium driver, which translates OpenGL to Vulkan. This provides better performance on devices with strong Vulkan support.

### Differences from VIRGL_GLES
- Guest uses `GALLIUM_DRIVER=zink` instead of `virgl`
- Vulkan is used as the backend rendering API
- Better OpenGL 4.x compatibility through Vulkan translation

### Environment Variables
```bash
GALLIUM_DRIVER=zink
MESA_GL_VERSION_OVERRIDE=4.5
VTEST_SOCK=/tmp/.virgl_test
```

## VENUS Mode

Experimental Vulkan passthrough using the Venus protocol. Guest Vulkan commands are forwarded directly to the host Vulkan driver. Unlike VirGL modes, Venus currently cannot provide scanout-capable DMA-BUF for zero-copy compositing — applications fall back to CPU compositing via `wl_shm`.

### Pipeline

```
┌──────────────────────────────────────────────────────────┐
│                     Proot Environment                     │
│  ┌────────────────┐                                      │
│  │ Linux App      │                                      │
│  │ (Vulkan)       │  VK commands                         │
│  │                ├──────┐                               │
│  └────────────────┘      │ Venus protocol                │
│                          │                               │
│  Shims: all (drm-shim, gbm-shim, egl-override, netstub) │
└──────────────────────────┼───────────────────────────────┘
                           │ VirGL Socket
┌──────────────────────────▼───────────────────────────────┐
│                 VirGL Server (Venus)                      │
│  virgl_test_server --no-virgl --venus                    │
│                                                          │
│  Venus contexts run as threads                           │
│  Forwards VK calls to host Vulkan driver                 │
│  CPU readback for scanout (wl_shm)                       │
└──────────────────────────────────────────────────────────┘
                           │
                    Wayland Socket (wl_shm)
                           │
┌──────────────────────────▼───────────────────────────────┐
│                      Compositor                           │
│  CPU composite (no zero-copy for Venus)                  │
└──────────────────────────────────────────────────────────┘
```

### Environment Variables
```bash
VK_DRIVER_FILES=/usr/share/vulkan/icd.d/venus_icd.aarch64.json
MESA_VK_VERSION_OVERRIDE=1.3
```

### Limitations
- Cannot provide scanout-capable DMA-BUF — falls back to CPU compositing
- Applications using GPU compositing (e.g., Chromium) need `--disable-gpu-compositing` flag
- Experimental — not auto-selected by GPU capability detection

## TURNIP_DIRECT Mode

Highest performance mode — guest applications access the Qualcomm Adreno GPU directly via `/dev/kgsl-3d0`. No VirGL server is needed. Only works on Qualcomm Adreno devices where the KGSL device node is accessible.

### Pipeline

```
┌──────────────────────────────────────────────────────────┐
│                     Proot Environment                     │
│  ┌────────────────┐                                      │
│  │ Linux App      │                                      │
│  │ (Turnip VK)    │  Direct Vulkan via KGSL              │
│  │                │                                      │
│  │ /dev/kgsl-3d0 ─┼──── bind mounted from host           │
│  └────────┬───────┘                                      │
│           │                                              │
│  Shims: netstub, egl-override only                       │
│  (no drm-shim, no gbm-shim — direct GPU access)         │
└───────────┼──────────────────────────────────────────────┘
            │ DMA-BUF fd
┌───────────▼──────────────────────────────────────────────┐
│                      Compositor                           │
│  EGLImage import from DMA-BUF → GL composite             │
│  → ANativeWindow                                         │
└──────────────────────────────────────────────────────────┘
```

### KGSL Detection

The `GpuCapabilityDetector` checks for Turnip Direct support:

1. Open `/dev/kgsl-3d0` — must be readable
2. Read `/sys/class/kgsl/kgsl-3d0/gpu_model` — must be an Adreno GPU
3. Check `PackageManager.FEATURE_VULKAN_HARDWARE_LEVEL` — Vulkan support required

### Environment Variables
```bash
VK_DRIVER_FILES=/usr/share/vulkan/icd.d/freedreno_icd.aarch64.json
MESA_VK_VERSION_OVERRIDE=1.3
# No VTEST_SOCK — direct GPU access
```

### Bind Mounts
```
/dev/kgsl-3d0 → /dev/kgsl-3d0 (from host)
```

## Comparison Table

| Feature | SOFTWARE | VIRGL_GLES | VIRGL_ZINK | VENUS | TURNIP_DIRECT |
|---------|---------|-----------|-----------|-------|--------------|
| GPU acceleration | No | Yes (GLES) | Yes (VK→GL) | Yes (VK) | Yes (VK native) |
| VirGL server | No | Yes | Yes | Yes | No |
| Zero-copy buffers | No | Yes (AHB) | Yes (AHB) | No | Yes (DMA-BUF) |
| DRM shim | No | Yes | Yes | Yes | No |
| GBM shim | No | Yes | Yes | Yes | No |
| EGL override | No | Yes | Yes | Yes | Yes |
| Netstub | Yes | Yes | Yes | Yes | Yes |
| Device requirement | None | Any | Vulkan | Vulkan | Qualcomm Adreno |
| OpenGL support | 4.5 (sw) | 4.5 | 4.5 | No | No |
| Vulkan support | No | No | No | Yes | Yes |
| Stability | Stable | Stable | Stable | Experimental | Stable |
| Performance | Low | Medium | Medium-High | Medium-High | High |

## Common Infrastructure

### Wayland Socket

All modes communicate with the compositor via a Wayland Unix domain socket. The socket path is generated by the compositor and exposed via `CompositorSession.socketPath`:

```
/tmp/wayland-0   (default socket name)
```

### VirGL Socket

Modes requiring a VirGL server use a second Unix socket:

```
/tmp/.virgl_test  (vtest protocol socket)
```

### LD_PRELOAD Chain

Shim libraries are loaded via `LD_PRELOAD` in the proot environment:

```
LD_PRELOAD=libnetstub.so:libdrm-shim.so
```

The order matters — netstub must load first to intercept `getifaddrs` before any library calls it.

### LD_LIBRARY_PATH

Shim libraries are found via `LD_LIBRARY_PATH` priority:

```
LD_LIBRARY_PATH=/opt/wldroid/lib:/usr/lib/aarch64-linux-gnu
```

WLDroid's shim directory is first so that `libgbm.so.1` and `libegl_override.so` are loaded before system versions.

## GPU Capability Detection API

```kotlin
val detector = GpuCapabilityDetector(context)

// Auto-detect best mode
val mode = detector.detectBestGpuMode()

// Individual capability checks
detector.isKgslAccessible()    // /dev/kgsl-3d0 readable
detector.isAdrenoGpu()         // Qualcomm Adreno GPU
detector.hasVulkanSupport()    // Vulkan hardware support

// Diagnostic info
detector.getGpuSummary()       // One-line: "Adreno 730, Vulkan 1.3, KGSL accessible"
detector.getGpuInfo()          // Multi-line diagnostic report
```

## User GPU Mode Override

Users can override the auto-detected mode via `GpuModeStore`:

```kotlin
val store = GpuModeStore(context)

// Set override
store.setGpuModeOverride(GpuMode.VIRGL_GLES)

// Revert to auto-detection
store.setGpuModeOverride(null)

// Observe current override
store.getGpuModeOverride().collect { mode ->
    // null = auto-detect, non-null = user override
}
```
