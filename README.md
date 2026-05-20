# WLDroid

[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)

WLDroid is a standalone Android library that provides a full [Wayland](https://wayland.freedesktop.org/) compositor stack for running Linux desktop applications on Android devices. It combines a [wlroots](https://gitlab.freedesktop.org/wlroots/wlroots)-based compositor, proot-managed Linux environments, VirGL GPU translation, and a DRM/GBM/EGL shim layer into a set of independent Gradle modules that can be composed as needed.

## Architecture

```
┌──────────────────────────────────────────────────────────────────┐
│                     Consumer App  /  :testapp                    │
├─────────────────────────────┬────────────────────────────────────┤
│        :launcher            │              :ui                   │
│  DesktopLauncher            │  CompositorSurface (Compose)       │
│  DesktopAppPreset           │  SetupOverlay · GpuModeSelector    │
│  orchestrates all 4 below   │  EnvironmentPicker                 │
├────────┬────────┬───────┬───┴────────────────────────────────────┤
│:compos.│ :proot │:virgl │              :shims                    │
│        │        │       │                                        │
│wlroots │RootfsM.│VirglS.│ drm-shim · gbm-shim · egl-override   │
│JNI     │ProotEx.│GpuDet.│ netstub · drm-wrapper                 │
│AHB     │EnvReg. │SrvMgr.│ ShimExtractor                         │
└────────┴────────┴───────┴────────────────────────────────────────┘
                              │
              ┌───────────────┴───────────────┐
              │     external/  (17 submodules) │
              │   3 forks + 14 upstream pinned │
              └────────────────────────────────┘
```

**Dependency graph:**

```
:testapp → :launcher → :compositor
                       :proot
                       :virgl
                       :shims
           :ui         (independent — Compose components)
```

The four library modules (`:compositor`, `:proot`, `:virgl`, `:shims`) have no dependencies on each other. `:launcher` orchestrates all four into a single `launch()` call. `:ui` provides standalone Compose components.

## Modules

| Module | Type | Description |
|--------|------|-------------|
| `:compositor` | AAR | wlroots compositor with custom Android backend, AHardwareBuffer allocator, XWayland, JNI bridge |
| `:proot` | AAR | Linux rootfs management — download, extract, configure, and run commands via proot |
| `:virgl` | AAR | VirGL/Venus server lifecycle, GPU capability detection, mode selection |
| `:shims` | AAR | DRM/GBM/EGL/netstub shim libraries bridging Linux graphics APIs to Android |
| `:launcher` | AAR | High-level orchestrator — wires compositor, proot, virgl, and shims into a single launch flow |
| `:ui` | AAR | Jetpack Compose UI — embeddable compositor surface, setup overlays, GPU mode selector |
| `:testapp` | APK | Demo application for standalone development and testing |

## Quick Start

**Prerequisites:** Android SDK (compileSdk 35, minSdk 29), NDK r28, Meson >= 1.0, Ninja, Python 3, Java 17.

```bash
git clone --recursive https://github.com/Shelnutt2/wldroid.git
cd wldroid

# Build the test app
./gradlew :testapp:assembleDebug
```

If already cloned without `--recursive`, run `git submodule update --init --recursive` first.

## Usage

### Embed a Compositor Surface

The `:ui` module provides a Compose component that renders a Wayland compositor inline:

```kotlin
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import nu.shel.wldroid.ui.CompositorSurface
import nu.shel.wldroid.ui.CompositorKeyboardController
import nu.shel.wldroid.ui.rememberCompositorSurfaceState
import nu.shel.wldroid.compositor.CompositorConfig
import nu.shel.wldroid.compositor.CompositorState

@Composable
fun MyScreen() {
    val config = CompositorConfig(
        cacheDir = context.cacheDir.absolutePath,
        xwaylandEnabled = true,
        gpuMode = "AUTO",
    )
    val surfaceState = rememberCompositorSurfaceState(config)
    var keyboardController by remember { mutableStateOf<CompositorKeyboardController?>(null) }

    CompositorSurface(
        modifier = Modifier.fillMaxSize(),
        config = config,
        surfaceState = surfaceState,
        onStateChange = { state ->
            when (state) {
                CompositorState.RUNNING -> Log.d("WLDroid", "Compositor ready")
                CompositorState.ERROR -> Log.e("WLDroid", "Compositor failed")
                else -> {}
            }
        },
        onClientCountChange = { count ->
            Log.d("WLDroid", "$count Wayland clients connected")
        },
        onKeyboardControllerChange = { keyboardController = it },
        // Optional host-side pinch/pan. Defaults off to preserve guest multi-touch.
        // This transforms only the Android viewport; guest output size/DPI stays fixed.
        enableViewportGestures = true,
    )
}
```

`CompositorSurface` works out of the box with software-keyboard handling and an optional keyboard FAB. Host viewport pinch-to-zoom/pan is Android-native and never resizes the Wayland output or changes guest DPI. It is disabled by default (`enableViewportGestures = false`) so two-finger guest gestures continue to reach apps; enable it only when you want Android to reserve two-finger pinch/pan for viewport zoom.

Use `surfaceState.viewport` to observe zoom/pan, `surfaceState.zoomIn()` / `zoomOut()` / `setZoom()` / `panBy()` / `resetZoom()` for toolbar controls, and `surfaceState.mapViewToGuest()` when translating custom overlay coordinates. Use `onKeyboardControllerChange` to get a `CompositorKeyboardController` for `show()`, `hide()`, `toggle()`, and `restartInput()` calls.

### Launch a Desktop App with DesktopLauncher

The `:launcher` module is the recommended high-level entry point. `DesktopLauncher` handles GPU detection, VirGL server startup, shim extraction, package installation, and app launch in a single call:

```kotlin
import nu.shel.wldroid.launcher.*

// DesktopLauncher is typically injected or constructed with all four
// library modules (compositor, proot, virgl, shims).

// Launch a preset application in a rootfs environment:
launcher.launchPreset(
    environment = myRootfsEnvironment,
    preset = DesktopAppPreset.XTERM,
    scope = lifecycleScope,
)

// Or launch an arbitrary command:
launcher.launch(
    environment = myRootfsEnvironment,
    command = listOf("weston-terminal"),
    requiredPackages = listOf("weston"),
    scope = lifecycleScope,
)

// Observe launch progress through sealed-class states:
launcher.state.collect { state ->
    when (state) {
        is DesktopLauncherState.Running -> Log.d("WLDroid", "App running")
        is DesktopLauncherState.Error -> Log.e("WLDroid", state.message)
        else -> {}
    }
}
```

Available presets include `TEST_PATTERN`, `WESTON_TERMINAL`, `ES2GEARS`, `WESTON_SIMPLE_EGL`, `VKCUBE`, `XTERM`, `VSCODE`, and `FIREFOX`. Access the full list via `DesktopAppPreset.ALL`.

### Direct CompositorSession Control

For lower-level control without the launcher orchestration:

```kotlin
import nu.shel.wldroid.compositor.*

val session = CompositorSession(
    CompositorConfig(
        cacheDir = cacheDir,
        gpuMode = "VIRGL_GLES",
        xwaylandEnabled = true,
    )
)

session.start(surface)

session.state.collect { state -> /* IDLE, STARTING, RUNNING, STOPPING, STOPPED, ERROR */ }
session.clientCount.collect { count -> /* connected Wayland clients */ }
session.socketPath.collect { path -> /* Wayland socket for clients to connect to */ }

session.stop()
```

## GPU Modes

Five rendering modes, auto-detected by `GpuCapabilityDetector` based on device hardware:

| Mode | Enum | VirGL Server | Description |
|------|------|:---:|-------------|
| Turnip Direct | `TURNIP_DIRECT` | No | Native Vulkan via `/dev/kgsl-3d0` on Qualcomm Adreno — highest performance |
| VirGL + Zink | `VIRGL_ZINK` | Yes | Vulkan-backed VirGL translation for devices with Vulkan but no direct GPU access |
| VirGL + GLES | `VIRGL_GLES` | Yes | OpenGL ES via VirGL server — broad device compatibility |
| Venus | `VENUS` | Yes | Experimental Vulkan passthrough — never auto-selected |
| Software | `SOFTWARE` | No | CPU-only rendering (llvmpipe/pixman) — universal fallback |

**Auto-detection order:** Turnip Direct > VirGL Zink > VirGL GLES > Software.

Venus is never auto-selected; it must be set explicitly. See [GPU Rendering Architecture](docs/gpu-rendering.md) for pipeline details.

## Wayland Protocols

| Protocol | Version |
|----------|:-------:|
| `wl_compositor` | 6 |
| `wl_subcompositor` | 1 |
| `wl_seat` | 9 |
| `wl_output` | 4 |
| `wl_data_device_manager` | 3 |
| `xdg_wm_base` | 6 |
| `xdg_decoration_v1` | 1 |
| `xdg_output_v1` | 1 |
| `zwp_linux_dmabuf_v1` | 4 |
| `zwp_text_input_v3` | 1 |
| `zwp_pointer_constraints_v1` | 1 |
| `zwp_relative_pointer_v1` | 1 |
| `wp_fractional_scale_v1` | 1 |
| `wp_viewporter` | 1 |
| `wp_cursor_shape_v1` | 1 |
| `wp_single_pixel_buffer_v1` | 1 |
| `wp_primary_selection_v1` | 1 |
| XWayland | — |

## Building

```bash
# Full build
./gradlew assembleDebug

# Individual modules
./gradlew :compositor:assembleDebug
./gradlew :launcher:assembleDebug

# Skip expensive native builds
./gradlew assembleDebug -PskipCompositor -PskipProot -PskipVirgl -PskipShims

# Kotlin unit tests
./gradlew test

# Compositor native tests (host x86_64)
cd compositor/native && meson setup builddir && meson test -C builddir

# GBM shim native tests
cd shims/native/gbm-shim && meson setup builddir && meson test -C builddir

# Instrumented tests (requires device/emulator)
./gradlew connectedAndroidTest
```

Shim libraries target Linux glibc aarch64 (not Android bionic) and can be cross-compiled in Docker for reproducibility. See [Build System](docs/build-system.md) for details.

## Documentation

| Document | Description |
|----------|-------------|
| [Build System](docs/build-system.md) | Meson + Gradle build architecture, cross-compilation, Docker |
| [Compositor Architecture](docs/compositor-architecture.md) | wlroots internals, Android backend, AHB allocator |
| [GPU Rendering](docs/gpu-rendering.md) | GPU mode pipelines, environment variables, buffer flow |
| [Shim Libraries](docs/shims.md) | DRM/GBM/EGL/netstub interception architecture |
| [Integration Guide](docs/integration-guide.md) | Consuming WLDroid as a library dependency |
| [API Reference](docs/api-reference.md) | Kotlin API documentation for all modules |
| [Migration Guide](docs/migration-guide.md) | Migrating from previous versions |
| [Development Guide](docs/development.md) | Dev environment setup, building, conventions |
| [Testing Strategy](docs/testing.md) | Test layers, native tests, CI pipeline |

## License

MIT License. See [LICENSE](LICENSE) for details.

## Contributing

See the [Development Guide](docs/development.md) for setup and conventions. Fork the repo, create a feature branch, and open a pull request.

WLDroid depends on 17 git submodules in `external/` (3 forks carrying Android patches, 14 upstream pinned to release tags). See [Build System](docs/build-system.md) for submodule management.
