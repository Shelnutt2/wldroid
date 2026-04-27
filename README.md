# WLDroid — Wayland Compositor Library for Android

[![License: AGPL-3.0](https://img.shields.io/badge/License-AGPL--3.0-blue.svg)](LICENSE)

WLDroid is a standalone, modular Android library that provides a full Wayland compositor stack for running Linux desktop applications on Android devices. Built on [wlroots](https://gitlab.freedesktop.org/wlroots/wlroots), it offers zero-copy GPU rendering, proot-based Linux environment management, and a complete shim stack that bridges Android's graphics APIs with standard Linux DRM/GBM interfaces.

## Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│                        Consumer App                             │
│                    (or :testapp module)                         │
├─────────────────────────────────────────────────────────────────┤
│                         :ui Module                              │
│   CompositorSurface · SetupOverlay · GpuModeSelector           │
│   EnvironmentPicker · EnvironmentCreator · KeyboardToggleFab   │
├──────────┬──────────┬──────────────┬────────────────────────────┤
│:compositor│  :proot  │    :virgl    │          :shims           │
│          │          │              │                            │
│ wlroots  │ RootfsM. │ VirglSession │ ShimExtractor             │
│ JNI      │ ProotEx. │ GpuDetector  │ ShimConfig                │
│ bridge   │ EnvReg.  │ ServerMgr    │                           │
│          │          │              │ ┌────────────────────────┐ │
│ Native:  │ Native:  │ Native:      │ │ drm-shim · gbm-shim   │ │
│ C/Meson  │ proot    │ virglrender  │ │ egl-override · netstub │ │
│ wlroots  │ talloc   │ libepoxy     │ │ drm-wrapper            │ │
└──────────┴──────────┴──────────────┴─┴────────────────────────┘─┘
                              │
              ┌───────────────┴───────────────┐
              │      external/ (17 submodules) │
              │  3 forks + 14 upstream pinned  │
              └────────────────────────────────┘
```

## Modules

| Module | Type | Description |
|--------|------|-------------|
| `:compositor` | Android Library (AAR) | Core wlroots compositor with custom Android backend, AHardwareBuffer allocator, XWayland support, and JNI bridge |
| `:proot` | Android Library (AAR) | Linux rootfs environment management — download, extract, configure, and execute commands via proot |
| `:virgl` | Android Library (AAR) | VirGL/Venus server management, GPU capability detection, and mode selection |
| `:shims` | Android Library (AAR) | DRM/GBM/EGL/netstub shim libraries that bridge Linux graphics APIs to Android |
| `:ui` | Android Library (AAR) | Jetpack Compose UI components — embeddable compositor surface, setup overlays, GPU settings |
| `:testapp` | Android App (APK) | Demo/test application for standalone development and testing |

**Dependency graph:**
```
:testapp → :ui → :compositor
                 :proot
                 :virgl
                 :shims
```

The four library modules (`:compositor`, `:proot`, `:virgl`, `:shims`) are independent of each other. The `:ui` module composes them into ready-to-use Compose components.

## Quick Start

### Prerequisites

- Android SDK (compileSdk 35, minSdk 29)
- Android NDK r28
- Meson ≥ 1.0 and Ninja
- Python 3
- Java 17

### Clone & Build

```bash
# Clone with submodules
git clone --recursive https://github.com/Shelnutt2/wldroid.git
cd wldroid

# Or if already cloned:
git submodule update --init --recursive

# Build the test app
./gradlew :testapp:assembleDebug

# Build individual modules
./gradlew :compositor:assembleDebug
./gradlew :proot:assembleDebug
./gradlew :virgl:assembleDebug
./gradlew :shims:assembleDebug
```

### Skip Flags for CI

Native builds can be skipped with Gradle properties:

```bash
./gradlew assembleDebug -PskipCompositor -PskipShims
```

## Library Usage

### Basic: Embed a Compositor Surface

The simplest integration — drop a Wayland compositor into your Compose UI:

```kotlin
import nu.shell.wldroid.ui.CompositorSurface
import nu.shell.wldroid.compositor.CompositorConfig
import nu.shell.wldroid.compositor.CompositorState

@Composable
fun MyScreen() {
    CompositorSurface(
        modifier = Modifier.fillMaxSize(),
        config = CompositorConfig(
            cacheDir = context.cacheDir.absolutePath,
            xwaylandEnabled = true,
            gpuMode = "AUTO",
        ),
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
    )
}
```

### Intermediate: Manage Linux Environments

Use the `:proot` module to create and manage Linux rootfs environments:

```kotlin
import nu.shell.wldroid.proot.*

// Create environment registry
val store = RootfsStore(context)
val downloader = RootfsDownloader(context.cacheDir)
val extractor = RootfsExtractor()
val manager = RootfsManager(rootfsBaseDir, store, downloader, extractor)
val registry = EnvironmentRegistry(manager, store, coroutineScope)

// Create a Debian environment
val env = registry.create(
    EnvironmentConfig(
        name = "My Desktop",
        distro = DistroTemplate.DEBIAN_TRIXIE,
        bindMounts = listOf(
            BindMount("/sdcard/Documents", "/home/user/Documents"),
        ),
    )
)

// Observe environments
registry.environments.collect { envList ->
    envList.forEach { println("${it.name}: ${it.status}") }
}
```

### Advanced: Full Compositor + GPU Pipeline

Wire up the complete stack with GPU mode detection and VirGL:

```kotlin
import nu.shell.wldroid.virgl.*
import nu.shell.wldroid.shims.*
import nu.shell.wldroid.compositor.*

// Detect GPU capabilities
val gpuDetector = GpuCapabilityDetector(context)
val bestMode = gpuDetector.detectBestGpuMode()
Log.d("WLDroid", "GPU: ${gpuDetector.getGpuSummary()}")
Log.d("WLDroid", "Selected mode: ${bestMode.displayName}")

// Start VirGL server if needed
val virglSession = VirglSession(
    config = VirglConfig(
        virglBinaryPath = virglBinaryPath,
        socketPath = socketDir.resolve(".virgl_test").absolutePath,
        gpuMode = bestMode,
    ),
)
virglSession.start()

// Extract shim libraries
val shimExtractor = ShimExtractor(context)
val shims = shimExtractor.extractForGpuMode(targetDir, bestMode.name)
val ldPreload = shimExtractor.getLdPreloadString(shims, bestMode.name)

// Create compositor session
val session = CompositorSession(
    CompositorConfig(
        cacheDir = cacheDir,
        gpuMode = bestMode.name,
        xwaylandEnabled = true,
    )
)

// Start compositor with a Surface
session.start(surface)

// Monitor state
session.state.collect { state ->
    println("Compositor: $state")
}
```

## GPU Modes

WLDroid supports 5 GPU rendering modes, automatically selected based on device capabilities:

| Mode | Description | Server Required | Best For |
|------|-------------|----------------|----------|
| **Turnip Direct** | Native Vulkan via `/dev/kgsl-3d0` | No | Qualcomm Adreno devices — best performance |
| **VirGL Zink** | Vulkan-over-VirGL translation | Yes | Devices with Vulkan but no direct GPU access |
| **VirGL GLES** | OpenGL ES via VirGL | Yes | Broad device compatibility |
| **Venus** | Vulkan passthrough via Venus protocol | Yes | Experimental — native Vulkan forwarding |
| **Software** | CPU-only rendering (llvmpipe/pixman) | No | Universal fallback — works everywhere |

**Auto-detection priority:** Turnip Direct → VirGL Zink → VirGL GLES → Software

The GPU mode determines which shim libraries are loaded, whether a VirGL server is needed, and the buffer flow path through the compositor. See [GPU Rendering Architecture](docs/gpu-rendering.md) for detailed pipeline diagrams.

## Building

### Local Build

```bash
# Full build
./gradlew assembleDebug

# Run Kotlin unit tests
./gradlew test

# Run native tests (host x86_64)
cd compositor/native
meson setup builddir
meson test -C builddir

# Run instrumented tests (requires device/emulator)
./gradlew connectedAndroidTest
```

### Docker Build (Shims)

Shim libraries target Linux glibc aarch64 and can be cross-compiled in Docker for reproducibility:

```bash
# Build Docker image
docker build -t wldroid-builder docker/

# Build all shims
docker run -v $(pwd):/workspace wldroid-builder
```

See [Build System](docs/build-system.md) for the full build architecture and [Development Guide](docs/development.md) for contributor setup.

## Testing

```bash
# All Kotlin unit tests
./gradlew test

# Compositor native tests
cd compositor/native && meson setup builddir && meson test -C builddir

# Shim native tests (GBM shim: 64 tests)
cd shims/native/gbm-shim && meson setup builddir && meson test -C builddir

# Instrumented tests on device
./gradlew connectedAndroidTest
```

See [Testing Strategy](docs/testing.md) for the full test architecture.

## Documentation

| Document | Description |
|----------|-------------|
| [Build System](docs/build-system.md) | Meson + Gradle build architecture, cross-compilation, Docker builds |
| [Compositor Architecture](docs/compositor-architecture.md) | wlroots compositor internals, Android backend, AHB allocator |
| [GPU Rendering](docs/gpu-rendering.md) | 5 GPU modes with pipeline diagrams, environment variables |
| [Shim Libraries](docs/shims.md) | DRM/GBM/EGL/netstub shim details and interception architecture |
| [Integration Guide](docs/integration-guide.md) | How to consume WLDroid as a library dependency |
| [API Reference](docs/api-reference.md) | Kotlin API documentation for all modules |
| [Development Guide](docs/development.md) | Setting up dev environment, building, contributing |
| [Testing Strategy](docs/testing.md) | Test layers, running tests, CI pipeline |

## License

This project is licensed under the [GNU Affero General Public License v3.0](LICENSE).

## Contributing

Contributions are welcome! Please see the [Development Guide](docs/development.md) for setup instructions and coding conventions.

1. Fork the repository
2. Create a feature branch (`git checkout -b feature/my-feature`)
3. Commit your changes with clear messages
4. Push to your fork and submit a Pull Request

### Native Dependencies

WLDroid maintains 3 forks with Android-specific patches and 14 upstream-pinned submodules. See [Build System](docs/build-system.md) for fork management and submodule details.
