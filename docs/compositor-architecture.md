# Compositor Architecture

WLDroid's compositor is a custom wlroots-based Wayland compositor designed specifically for Android. It replaces standard Linux backends (DRM/KMS, libinput) with Android-native equivalents — using `ANativeWindow` for display output and `AHardwareBuffer` for zero-copy buffer sharing between the compositor and GPU-accelerated Linux applications.

## System Architecture

```
┌─────────────────────────────────────────────────────────────┐
│                     Android Application                      │
│  ┌───────────────────────────────────────────────────────┐  │
│  │              CompositorSession (Kotlin)                │  │
│  │  state: StateFlow<CompositorState>                    │  │
│  │  clientCount: StateFlow<Int>                          │  │
│  │  socketPath: StateFlow<String?>                       │  │
│  └──────────────────┬────────────────────────────────────┘  │
│                     │ JNI (RegisterNatives)                  │
│  ┌──────────────────▼────────────────────────────────────┐  │
│  │              jni_bridge.c (native thread)              │  │
│  │  ┌─────────────────────────────────────────────────┐  │  │
│  │  │           compositor_server.c                    │  │  │
│  │  │  wl_display + wl_event_loop                     │  │  │
│  │  │  ┌──────────┐ ┌────────────┐ ┌──────────────┐  │  │  │
│  │  │  │ android  │ │ wlr_scene  │ │  xdg_shell   │  │  │  │
│  │  │  │ _backend │ │ + renderer │ │  xwayland    │  │  │  │
│  │  │  │          │ │ (GLES2)    │ │  wlr_seat    │  │  │  │
│  │  │  └────┬─────┘ └──────┬─────┘ └──────────────┘  │  │  │
│  │  │       │              │                          │  │  │
│  │  │  ┌────▼─────┐  ┌────▼──────────┐               │  │  │
│  │  │  │ android  │  │ ahb_allocator │               │  │  │
│  │  │  │ _output  │  │ ahb_registry  │               │  │  │
│  │  │  │          │  │ ahb_receiver  │               │  │  │
│  │  │  └────┬─────┘  └──────────────┘               │  │  │
│  │  └───────┼────────────────────────────────────────┘  │  │
│  └──────────┼───────────────────────────────────────────┘  │
│             │                                               │
│  ┌──────────▼───────────────────────────────────────────┐  │
│  │  ANativeWindow (SurfaceView / SurfaceTexture)        │  │
│  └──────────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────────┘
         │ Wayland Socket                    │ VirGL Socket
         ▼                                   ▼
┌─────────────────────────────────────────────────────────────┐
│                    Proot Linux Environment                    │
│  Linux desktop applications (X11/Wayland)                    │
└─────────────────────────────────────────────────────────────┘
```

## Native Components

### compositor_server.c

The core compositor initialization and event loop. Creates and wires together:

- `wl_display` — Wayland display server with Unix domain socket
- `wlr_backend` — Custom Android backend (see below)
- `wlr_renderer` — GLES2 renderer (primary) with pixman software fallback
- `wlr_scene` — Scene graph for compositing window surfaces
- `ahb_allocator` — AHardwareBuffer-based buffer allocator
- `wlr_seat` — Input device seat (touch, keyboard, pointer)
- Protocol implementations (xdg-shell, linux-dmabuf, text-input, etc.)
- `xwayland` — X11 compatibility layer

### android_backend.c

A custom wlroots backend modeled after the headless backend but designed for Android:

- **Buffer capabilities:** `WLR_BUFFER_CAP_DATA_PTR | WLR_BUFFER_CAP_DMABUF` — supports both CPU and DMA-BUF buffer access
- **No DRM fd** — Android doesn't expose DRM devices to apps
- **Single output** — maps to the Android Surface provided by the Java layer
- **Event loop integration** — uses `wl_event_loop` for all I/O

### android_output.c

Renders compositor output to the Android `ANativeWindow`:

1. Lock AHardwareBuffer for CPU access
2. Copy rendered frame to ANativeWindow buffer
3. `ANativeWindow_unlockAndPost()` to present

Handles output resize via wlroots mode change events when the Android Surface dimensions change.

### ahb_allocator.c

Allocates `AHardwareBuffer` instances for use as wlroots buffers:

```
AHardwareBuffer_allocate({
    .width = ..., .height = ...,
    .format = AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM,
    .usage = CPU_READ_OFTEN | CPU_WRITE_OFTEN |
             GPU_SAMPLED_IMAGE | GPU_FRAMEBUFFER |
             COMPOSER_OVERLAY,
    .layers = 1,
})
```

Key feature: exports DMA-BUF file descriptors via `native_handle_t->data[0]`, enabling zero-copy buffer sharing with VirGL server and GPU-accelerated applications.

### ahb_registry.c

A fixed-capacity (256-entry) thread-safe registry mapping inode numbers to `AHardwareBuffer*` pointers:

- **Register:** Associates an inode number with an AHB, calling `AHardwareBuffer_acquire()` for ref-counting
- **Unregister:** Releases the AHB reference via `AHardwareBuffer_release()`
- **Lookup:** Returns an AHB by inode number (for compositor to import client buffers)
- **Thread safety:** Protected by mutex since the compositor and VirGL server access concurrently

### ahb_registry_receiver.c

Receives AHB handles from the VirGL server via a Unix domain socket wire protocol:

| Byte Offset | Field | Description |
|------------|-------|-------------|
| 0 | Tag (1 byte) | `0x01` = REGISTER, `0x02` = UNREGISTER |
| 1–8 | Inode (8 bytes LE) | File inode identifying the AHB |
| (ancillary) | AHB handle | Sent via `sendHandleToUnixSocket` (SCM_RIGHTS) |

This enables the VirGL server (running in a separate process) to register AHardwareBuffers with the compositor for zero-copy rendering.

### jni_bridge.c

Bridges the Kotlin `CompositorServer` class to native code:

- **10 JNI methods** registered via `RegisterNatives` in `JNI_OnLoad`
- **Thread model:** `nativeStartCompositor()` spawns a dedicated pthread for the wlroots event loop
- **Thread safety:** UI-thread JNI calls (input events, resize) are marshaled to the compositor thread via `wl_event_loop_add_idle` callbacks and atomic variables
- **Lifecycle:** `nativeStopCompositor()` signals termination and does `pthread_join`

### input_handler.c

Forwards Android input events to the wlroots seat:

- **Touch:** Multi-touch via `wlr_seat_touch_notify_*` (down/motion/up/cancel)
- **Keyboard:** Key events with Android keycode → XKB keycode translation (via `keycode_map.h`)
- **Pointer:** Motion, button press/release, and scroll via `wlr_seat_pointer_notify_*`

### text_input_handler.c

Bridges Android IME (Input Method Editor) to the Wayland text-input protocol:

- Implements `zwp_text_input_v3` for Wayland-native text input
- Named pipe for IME state communication between compositor and Java layer
- `nativeCommitText()` forwards committed text to focused Wayland client
- `nativeImeShown()/nativeImeHidden()` tracks soft keyboard visibility

### xwayland_surface.c

Manages XWayland surfaces — X11 windows that are composited as Wayland surfaces:

- Creates XWayland server via `wlr_xwayland_create()` (when `xwayland_enabled` is true)
- Maps X11 windows to xdg_surface equivalents in the scene graph
- Handles X11 window decorations, position, and focus
- Entirely skipped at runtime when `CompositorConfig.xwaylandEnabled` is false

## JNI Bridge Detail

```
  Kotlin (UI Thread)              JNI Bridge              Native (Compositor Thread)
  ─────────────────              ──────────              ──────────────────────────
        │                            │                            │
        │ start(surface)             │                            │
        ├──────────────────────────► │                            │
        │                            │ spawn pthread              │
        │                            ├───────────────────────────►│
        │                            │                  create wl_display
        │                            │                  create backend
        │                            │                  create renderer
        │                            │                  bind socket
        │                            │◄───────────────────────────┤
        │ getSocketName()            │                            │
        ├──────────────────────────► │                            │
        │◄──────────────────────────┤│   (read atomic)           │
        │  "/tmp/wayland-0"          │                            │
        │                            │                            │
        │ sendTouchEvent(...)        │                            │
        ├──────────────────────────► │                            │
        │                            │ wl_event_loop_add_idle     │
        │                            ├───────────────────────────►│
        │                            │                  input_handler
        │                            │                  wlr_seat_touch_*
        │                            │                            │
        │ stop()                     │                            │
        ├──────────────────────────► │                            │
        │                            │ set terminate flag         │
        │                            ├───────────────────────────►│
        │                            │                  wl_display_terminate
        │                            │◄───────────────────────────┤
        │                            │ pthread_join               │
        │◄──────────────────────────┤│                            │
```

## Buffer Flow by GPU Mode

### VirGL (GLES/Zink) — Zero-Copy AHB Path

```
Guest App (proot)           VirGL Server              Compositor
─────────────────          ──────────────            ──────────
      │                         │                        │
      │ gbm_bo_create()         │                        │
      ├────────────────────────►│                        │
      │ (gbm-shim)              │                        │
      │                         │ AHardwareBuffer_allocate
      │                         │ Register AHB (inode)   │
      │                         ├───────────────────────►│
      │                         │ (Unix socket + SCM_RIGHTS)
      │                         │                        │ ahb_registry.register()
      │ GL render calls         │                        │
      ├────────────────────────►│                        │
      │ (via vtest protocol)    │                        │
      │                         │ EGLImage from AHB      │
      │                         │ GL render into AHB     │
      │                         │                        │
      │ wl_surface.commit       │                        │
      ├─────────────────────────┼───────────────────────►│
      │ (dmabuf fd)             │                        │ Lookup AHB by inode
      │                         │                        │ EGLImage import
      │                         │                        │ GL texture composite
      │                         │                        │ → ANativeWindow
```

**Zero GPU-to-CPU copies** — the AHardwareBuffer is created once, rendered into by VirGL, and composited by the compositor, all via GPU.

### Software Mode — CPU Path

```
Guest App (proot)                          Compositor
─────────────────                         ──────────
      │                                        │
      │ wl_surface.attach(wl_shm_buffer)       │
      ├───────────────────────────────────────►│
      │                                        │ memcpy to render target
      │ wl_surface.commit                      │
      ├───────────────────────────────────────►│
      │                                        │ pixman composite
      │                                        │ → lock AHB (CPU)
      │                                        │ → copy to ANativeWindow
      │                                        │ → unlockAndPost
```

### Turnip Direct — Native GPU Path

```
Guest App (proot)                          Compositor
─────────────────                         ──────────
      │                                        │
      │ vkCreateImage (via /dev/kgsl-3d0)      │
      │ (direct Qualcomm GPU access)           │
      │                                        │
      │ VK render                              │
      │ wl_surface.commit (dmabuf)             │
      ├───────────────────────────────────────►│
      │                                        │ EGLImage import from dmabuf
      │                                        │ GL texture composite
      │                                        │ → ANativeWindow
```

## Wayland Protocols Supported

| Protocol | Version | Purpose |
|----------|---------|---------|
| `wl_compositor` | v6 | Core surface management |
| `wl_subcompositor` | v1 | Subsurface support |
| `wl_seat` | v9 | Input device seat |
| `wl_output` | v4 | Output/display information |
| `wl_data_device_manager` | v3 | Clipboard / drag-and-drop |
| `xdg_wm_base` | v6 | Window management (toplevel, popup) |
| `zwp_linux_dmabuf_v1` | v4 | Zero-copy buffer sharing via DMA-BUF |
| `zwp_text_input_v3` | v1 | Text input / IME integration |
| `wp_fractional_scale_v1` | v1 | Fractional display scaling |
| `wp_viewporter` | v1 | Surface viewport/crop |
| `wp_cursor_shape_v1` | v1 | Cursor shape protocol |
| `zwp_pointer_constraints_v1` | v1 | Pointer lock/confine |
| `zwp_relative_pointer_v1` | v1 | Relative pointer motion |
| `XWayland` | — | X11 application compatibility |

## Thread Model

The compositor runs two primary threads:

1. **UI Thread (Android)** — Handles Kotlin API calls, input events, surface lifecycle
2. **Compositor Thread (native pthread)** — Runs the wlroots event loop, processes Wayland protocol messages, renders frames

All communication from the UI thread to the compositor thread goes through `wl_event_loop_add_idle()` callbacks, ensuring thread safety without explicit locking on the compositor's data structures. The exception is the AHB registry, which uses a mutex since it's accessed by both the compositor thread and the VirGL server process.

## Configuration

The compositor is configured via `CompositorConfig`:

```kotlin
data class CompositorConfig(
    val cacheDir: String = "",              // Cache directory for compositor temp files
    val xkbBasePath: String = "",           // Base path for XKB keyboard layouts
    val xwaylandEnabled: Boolean = true,    // Enable X11 app support (runtime toggle)
    val xwaylandBinaryPath: String = "",    // Path to XWayland wrapper script
    val xwaylandTmpDir: String = "",        // Override /tmp for XWayland sockets
    val gpuMode: String = "AUTO",           // GPU rendering mode
    val testClientEnabled: Boolean = false,  // Enable built-in test pattern client
    val ahbRegistrySocketPath: String = "", // AHB registry socket for GPU buffer sharing
)
```

The `gpuMode` affects which buffer allocation paths are used and whether the compositor expects DMA-BUF or shared-memory buffers from clients.
