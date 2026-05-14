# Migration Guide: coder-mobile-android → WLDroid

This guide walks through migrating [coder-mobile-android](https://github.com/coder/coder-mobile-android) from its built-in Wayland compositor, proot, VirGL, and shim code to the standalone WLDroid library. After migration, all native build complexity moves into WLDroid and the host app focuses on its own UI and business logic.

## Overview

### What Changes

| Area | Before (built-in) | After (WLDroid) |
|------|--------------------|-----------------|
| Compositor | Inline C code + JNI in `compositor/` | `:compositor` module via `CompositorServer` / `CompositorSession` |
| Proot | Custom build tasks + Kotlin in `app/` | `:proot` module via `EnvironmentRegistry` / `ProotExecutor` |
| VirGL | Custom build tasks + Kotlin in `app/` | `:virgl` module via `VirglSession` / `VirglServerManager` |
| Shims | Custom native build in `compositor/` | `:shims` module via `ShimExtractor` |
| UI | Inline `SurfaceView` + manual JNI | `:ui` module via `CompositorSurface` Composable |
| Native builds | 9+ Gradle `Exec` tasks | Zero — handled internally by WLDroid |
| Submodules | 17 git submodules in-tree | Zero — vendored inside WLDroid |

### What Stays the Same

- Application-level UI (workspace list, login, settings)
- Coder SDK integration and authentication
- Application theme and branding
- ProGuard / R8 configuration (WLDroid ships its own consumer rules)

## Prerequisites

| Requirement | Version |
|-------------|---------|
| WLDroid | 1.0.0+ |
| Android SDK | compileSdk 35 |
| Android NDK | r28 (28.0.13004108) |
| Kotlin | 2.1.10+ |
| AGP | 8.9.1+ |
| minSdk | 29 (Android 10) |
| Jetpack Compose | BOM 2024.12.01+ |
| Hilt | 2.54+ (if using DI integration) |

---

## Step 1: Add WLDroid Dependencies

### Option A: Maven Coordinates (Published Release)

```kotlin
// app/build.gradle.kts
dependencies {
    // Full stack — :ui transitively includes all modules
    implementation("nu.shel.wldroid:ui:1.0.0")

    // Or pick individual modules:
    // implementation("nu.shel.wldroid:compositor:1.0.0")
    // implementation("nu.shel.wldroid:proot:1.0.0")
    // implementation("nu.shel.wldroid:virgl:1.0.0")
    // implementation("nu.shel.wldroid:shims:1.0.0")
}
```

### Option B: Composite Build (Development / Pre-release)

```kotlin
// settings.gradle.kts
includeBuild("path/to/wldroid") {
    dependencySubstitution {
        substitute(module("nu.shel.wldroid:ui")).using(project(":ui"))
        substitute(module("nu.shel.wldroid:compositor")).using(project(":compositor"))
        substitute(module("nu.shel.wldroid:proot")).using(project(":proot"))
        substitute(module("nu.shel.wldroid:virgl")).using(project(":virgl"))
        substitute(module("nu.shel.wldroid:shims")).using(project(":shims"))
    }
}
```

```kotlin
// app/build.gradle.kts
dependencies {
    implementation("nu.shel.wldroid:ui")
}
```

---

## Step 2: Remove Built-in Code

Delete the following files and directories from coder-mobile-android:

### Entire Directories

```
compositor/                          # Native compositor C code + Meson build
proot/                               # Proot native build directory
virgl/                               # VirGL native build directory
compositor/subprojects/              # All .wrap files for native dependencies
```

### Kotlin Source Files (in `app/src/main/java/com/coder/android/`)

```
data/proot/RootfsManager.kt         # → nu.shel.wldroid.proot.RootfsManager
data/proot/VirglServerManager.kt    # → nu.shel.wldroid.virgl.VirglServerManager
data/proot/GpuCapabilityDetector.kt # → nu.shel.wldroid.virgl.GpuCapabilityDetector
data/proot/GpuModeStore.kt          # → nu.shel.wldroid.virgl.GpuModeStore
```

### Gradle Build Tasks

Remove all 9 native build `Exec` tasks from `app/build.gradle.kts`:

```kotlin
// DELETE all tasks like these:
tasks.register<Exec>("buildCompositor") { ... }
tasks.register<Exec>("buildProot") { ... }
tasks.register<Exec>("buildVirgl") { ... }
tasks.register<Exec>("buildShims") { ... }
tasks.register<Exec>("mesonSetupCompositor") { ... }
tasks.register<Exec>("ninjaBuildCompositor") { ... }
tasks.register<Exec>("mesonSetupShims") { ... }
tasks.register<Exec>("ninjaBuildShims") { ... }
tasks.register<Exec>("buildAllNative") { ... }
```

Also remove the associated Gradle properties from `gradle.properties`:

```properties
# DELETE these:
skipCompositor=false
skipProot=false
skipVirgl=false
skipShims=false
```

### Git Submodules

Remove all 17 git submodules previously referenced in `.gitmodules`:

```bash
# Remove all compositor/proot/virgl external dependencies
git submodule deinit -f compositor/subprojects/wlroots
git submodule deinit -f compositor/subprojects/wayland
# ... repeat for all 17 submodules
git rm compositor/subprojects/
```

---

## Step 3: Update CompositorActivity

Replace the inline `SurfaceView` + manual JNI calls with WLDroid's `CompositorSurface` Composable.

### Before (Inline Implementation)

```kotlin
// CompositorActivity.kt — BEFORE
class CompositorActivity : ComponentActivity() {
    private external fun nativeStartCompositor(surface: Surface, cachePath: String): Long
    private external fun nativeSendTouchEvent(compositor: Long, x: Float, y: Float, action: Int)
    private external fun nativeStopCompositor(compositor: Long)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AndroidView(factory = { ctx ->
                SurfaceView(ctx).apply {
                    holder.addCallback(object : SurfaceHolder.Callback {
                        override fun surfaceCreated(holder: SurfaceHolder) {
                            compositorHandle = nativeStartCompositor(
                                holder.surface,
                                cacheDir.absolutePath,
                            )
                        }
                        override fun surfaceDestroyed(holder: SurfaceHolder) {
                            nativeStopCompositor(compositorHandle)
                        }
                        // ...
                    })
                }
            })
        }
    }
}
```

### After (WLDroid)

```kotlin
// CompositorActivity.kt — AFTER
import nu.shel.wldroid.compositor.CompositorConfig
import nu.shel.wldroid.compositor.CompositorState
import nu.shel.wldroid.ui.CompositorSurface

class CompositorActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            CompositorSurface(
                config = CompositorConfig(
                    cacheDir = cacheDir.absolutePath,
                    xkbBasePath = "$cacheDir/xkb",
                ),
                onStateChange = { state ->
                    when (state) {
                        is CompositorState.Running -> Log.d(TAG, "Compositor running")
                        is CompositorState.Error -> Log.e(TAG, "Error: ${state.message}")
                        is CompositorState.Stopped -> finish()
                        else -> {}
                    }
                },
            )
        }
    }
}
```

**Key differences:**
- No `System.loadLibrary()` call needed — WLDroid handles native library loading internally
- No JNI method declarations — all native interaction is encapsulated in `CompositorServer`
- No `SurfaceHolder.Callback` boilerplate — `CompositorSurface` manages the lifecycle
- Touch/keyboard input is handled automatically by `CompositorInput`

---

## Step 4: Update Desktop App Launcher

Replace inline rootfs management with WLDroid's `EnvironmentRegistry` and `ProotExecutor`.

### Before (Inline Implementation)

```kotlin
// DesktopAppLauncher.kt — BEFORE
class DesktopAppLauncher @Inject constructor(
    private val rootfsManager: RootfsManager,
) {
    suspend fun launchDesktop() {
        // Download and extract rootfs manually
        rootfsManager.downloadRootfs("https://example.com/rootfs.tar.xz")
        rootfsManager.extractRootfs(downloadPath, targetPath)

        // Run command in proot
        rootfsManager.runInProot(
            rootfsPath = targetPath,
            command = listOf("startxfce4"),
            envVars = mapOf("DISPLAY" to ":0"),
        )
    }
}
```

### After (WLDroid)

```kotlin
// DesktopAppLauncher.kt — AFTER
import nu.shel.wldroid.proot.EnvironmentRegistry
import nu.shel.wldroid.proot.ProotConfig
import nu.shel.wldroid.proot.ProotExecutor
import nu.shel.wldroid.proot.RootfsEnvironment

class DesktopAppLauncher @Inject constructor(
    private val environmentRegistry: EnvironmentRegistry,
    private val prootExecutor: ProotExecutor,
) {
    suspend fun launchDesktop() {
        // Get or create an environment
        val environment = environmentRegistry.getDefault()
            ?: environmentRegistry.create(
                RootfsEnvironment(
                    name = "debian-trixie",
                    distro = "trixie",
                    arch = "aarch64",
                )
            )

        // Run command in proot with structured config
        prootExecutor.execute(
            environment = environment,
            config = ProotConfig(
                command = listOf("startxfce4"),
                envVars = mapOf("DISPLAY" to ":0"),
            ),
        )
    }
}
```

**Key differences:**
- `EnvironmentRegistry` manages multiple named environments with persistence
- `ProotExecutor` accepts structured `ProotConfig` instead of raw parameters
- Rootfs download/extraction is handled by `RootfsManager` + `RootfsDownloader` + `RootfsExtractor` internally
- DNS configuration is managed automatically by `ProotDnsManager`

---

## Step 5: Update GPU Management

Replace inline GPU detection and VirGL server management with WLDroid's `VirglSession`.

### Before (Inline Implementation)

```kotlin
// GpuManager.kt — BEFORE
class GpuManager @Inject constructor(
    private val gpuCapabilityDetector: GpuCapabilityDetector,
    private val virglServerManager: VirglServerManager,
    private val gpuModeStore: GpuModeStore,
) {
    suspend fun startGpuAcceleration(): String {
        val gpuMode = gpuModeStore.getSelectedMode()
            ?: gpuCapabilityDetector.detectBestMode()

        if (gpuMode != GpuMode.SOFTWARE) {
            virglServerManager.start(gpuMode)
        }

        return gpuMode.name
    }
}
```

### After (WLDroid)

```kotlin
// GpuManager.kt — AFTER
import nu.shel.wldroid.virgl.GpuMode
import nu.shel.wldroid.virgl.VirglConfig
import nu.shel.wldroid.virgl.VirglSession
import nu.shel.wldroid.virgl.VirglState

class GpuManager @Inject constructor() {
    private var session: VirglSession? = null

    suspend fun startGpuAcceleration(): GpuMode {
        val session = VirglSession(
            VirglConfig(gpuMode = GpuMode.AUTO)  // Auto-detects best mode
        )
        this.session = session

        session.start()

        // Observe state via StateFlow
        session.state.collect { state ->
            when (state) {
                is VirglState.Running -> Log.d(TAG, "VirGL running: ${state.gpuMode}")
                is VirglState.Error -> Log.e(TAG, "VirGL error: ${state.message}")
                else -> {}
            }
        }

        return session.resolvedGpuMode
    }

    fun stop() {
        session?.stop()
    }
}
```

**Key differences:**
- `VirglSession` encapsulates the full lifecycle (detect → start → monitor → stop)
- `GpuMode.AUTO` triggers automatic detection via `GpuCapabilityDetector` internally
- State is exposed as `StateFlow<VirglState>` for reactive observation
- `GpuModeStore` persists user overrides automatically via DataStore
- The 5 GPU modes remain the same: `TURNIP_DIRECT`, `VIRGL_ZINK`, `VIRGL_GLES`, `VENUS`, `SOFTWARE`

---

## Step 6: Update Gradle Build

### Remove Native Build Configuration

Delete all native build tasks and properties from `app/build.gradle.kts`:

```kotlin
// REMOVE all of these patterns:
tasks.register<Exec>("buildCompositor") { ... }
tasks.register<Exec>("mesonSetupCompositor") { ... }
tasks.register<Exec>("ninjaBuildCompositor") { ... }
// ... and all similar tasks for proot, virgl, shims

// REMOVE native build dependency wiring:
tasks.named("preBuild") {
    dependsOn("buildAllNative")
}
```

### Remove Skip Properties

From `gradle.properties`, delete:

```properties
skipCompositor=false
skipProot=false
skipVirgl=false
skipShims=false
```

### Final `app/build.gradle.kts` Dependencies Block

```kotlin
dependencies {
    // WLDroid — full stack
    implementation("nu.shel.wldroid:ui:1.0.0")

    // Or individual modules if you don't need the UI layer:
    // implementation("nu.shel.wldroid:compositor:1.0.0")
    // implementation("nu.shel.wldroid:proot:1.0.0")
    // implementation("nu.shel.wldroid:virgl:1.0.0")
    // implementation("nu.shel.wldroid:shims:1.0.0")

    // Your existing app dependencies remain unchanged
    implementation(libs.coder.sdk)
    implementation(libs.hilt.android)
    // ...
}
```

### Verify Version Catalog Compatibility

Ensure your `gradle/libs.versions.toml` meets the minimum versions:

```toml
[versions]
agp = "8.9.1"        # minimum for WLDroid
kotlin = "2.1.10"     # minimum for WLDroid
compileSdk = "35"
minSdk = "29"
ndkVersion = "28.0.13004108"
```

---

## Step 7: Test

### Build Verification

```bash
# Clean build — should succeed without any native build tasks
./gradlew clean assembleDebug

# Verify no native build errors
./gradlew app:dependencies | grep wldroid
```

### Runtime Verification Checklist

- [ ] **Compositor starts** — `CompositorSurface` renders without crashes
- [ ] **Touch input works** — tap, drag, and multi-touch pass through to Linux apps
- [ ] **Keyboard input works** — both soft keyboard and physical keyboard events are received
- [ ] **Proot environment launches** — `ProotExecutor` successfully enters the rootfs
- [ ] **GPU acceleration works** — VirGL session starts and Linux apps can use OpenGL
- [ ] **GPU mode detection** — `GpuCapabilityDetector` selects the correct mode for the device
- [ ] **Shims load correctly** — `ShimExtractor` unpacks shims and `LD_PRELOAD` works
- [ ] **Multiple environments** — `EnvironmentRegistry` can create, list, and delete environments
- [ ] **State observation** — `CompositorState` and `VirglState` StateFlows emit correct values
- [ ] **Graceful shutdown** — stopping the compositor and VirGL session cleans up resources
- [ ] **Error handling** — invalid configs produce meaningful error states, not crashes

### Device Test Matrix

| Device | SoC | Expected GPU Mode | Status |
|--------|-----|-------------------|--------|
| Pixel 8 | Tensor G3 | VIRGL_GLES | |
| Samsung S24 | Snapdragon 8 Gen 3 | TURNIP_DIRECT | |
| OnePlus 12 | Snapdragon 8 Gen 3 | TURNIP_DIRECT | |
| Generic ARM64 | Any | SOFTWARE (fallback) | |

---

## API Mapping Table

Complete mapping from coder-mobile-android classes to WLDroid equivalents:

| Old (coder-mobile-android) | New (WLDroid) | Package | Notes |
|---------------------------|---------------|---------|-------|
| `CompositorActivity` JNI calls (`nativeStartCompositor`, `nativeSendTouchEvent`, `nativeStopCompositor`) | `CompositorServer` / `CompositorSession` | `nu.shel.wldroid.compositor` | JNI refactored to `RegisterNatives` in `JNI_OnLoad`; no manual `System.loadLibrary()` needed |
| `RootfsManager` | `RootfsManager` + `EnvironmentRegistry` | `nu.shel.wldroid.proot` | Split into rootfs operations (`RootfsManager`) and environment lifecycle (`EnvironmentRegistry`) |
| `VirglServerManager` | `VirglServerManager` + `VirglSession` | `nu.shel.wldroid.virgl` | Added `VirglSession` as high-level lifecycle wrapper around `VirglServerManager` |
| `GpuCapabilityDetector` | `GpuCapabilityDetector` | `nu.shel.wldroid.virgl` | Same detection logic, new package; now returns `GpuMode` enum |
| `GpuModeStore` | `GpuModeStore` | `nu.shel.wldroid.virgl` | Same DataStore-based persistence, new package |
| Inline `SurfaceView` + `SurfaceHolder.Callback` | `CompositorSurface` | `nu.shel.wldroid.ui` | Jetpack Compose component; handles surface lifecycle, input, and state |
| Inline setup/loading UI | `SetupOverlay` | `nu.shel.wldroid.ui` | Reusable Composable for download/extraction progress |
| Inline GPU mode picker | `GpuModeSelector` | `nu.shel.wldroid.ui` | Composable dropdown with all 5 GPU modes |
| Inline environment picker | `EnvironmentPicker` | `nu.shel.wldroid.ui` | Composable list with create/delete actions |
| *(none)* | `EnvironmentCreator` | `nu.shel.wldroid.ui` | New Composable for guided environment creation |
| Inline shim extraction logic | `ShimExtractor` | `nu.shel.wldroid.shims` | Dedicated class for extracting shim `.so` files from assets |
| Inline `CompositorConfig` construction | `CompositorConfig` | `nu.shel.wldroid.compositor` | Data class with validation |
| *(none)* | `CompositorInput` | `nu.shel.wldroid.compositor` | Dedicated input routing (touch, keyboard, pointer) |
| *(none)* | `CompositorState` | `nu.shel.wldroid.compositor` | Sealed class for lifecycle states (`Idle`, `Starting`, `Running`, `Error`, `Stopped`) |
| *(none)* | `VirglConfig` | `nu.shel.wldroid.virgl` | Structured config (GPU mode, socket path, flags) |
| *(none)* | `VirglState` | `nu.shel.wldroid.virgl` | Sealed class for VirGL lifecycle states |
| *(none)* | `ProotConfig` | `nu.shel.wldroid.proot` | Structured config (command, env vars, bind mounts) |
| *(none)* | `ProotDnsManager` | `nu.shel.wldroid.proot` | Automatic DNS configuration for proot environments |
| *(none)* | `RootfsDownloader` | `nu.shel.wldroid.proot` | Resumable rootfs downloads with progress reporting |
| *(none)* | `RootfsExtractor` | `nu.shel.wldroid.proot` | Tar extraction with progress reporting |
| *(none)* | `RootfsStore` | `nu.shel.wldroid.proot` | DataStore-based rootfs metadata persistence |
| *(none)* | `ShimConfig` | `nu.shel.wldroid.shims` | Shim extraction configuration |

## Namespace Migration

All WLDroid classes live under the `nu.shel.wldroid` namespace. Update your imports:

```kotlin
// BEFORE
import com.coder.android.data.proot.RootfsManager
import com.coder.android.data.proot.GpuCapabilityDetector
import com.coder.android.data.proot.GpuModeStore
import com.coder.android.data.proot.VirglServerManager

// AFTER
import nu.shel.wldroid.proot.RootfsManager
import nu.shel.wldroid.proot.EnvironmentRegistry
import nu.shel.wldroid.proot.ProotExecutor
import nu.shel.wldroid.virgl.GpuCapabilityDetector
import nu.shel.wldroid.virgl.GpuModeStore
import nu.shel.wldroid.virgl.VirglServerManager
import nu.shel.wldroid.virgl.VirglSession
import nu.shel.wldroid.compositor.CompositorConfig
import nu.shel.wldroid.compositor.CompositorServer
import nu.shel.wldroid.compositor.CompositorSession
import nu.shel.wldroid.ui.CompositorSurface
```

---

## Troubleshooting

### Build Fails with "Missing Native Libraries"

Ensure you've completely removed the old native build tasks. WLDroid bundles its own `.so` files — you should not be building any native code in the host app.

### `UnsatisfiedLinkError` at Runtime

WLDroid uses `RegisterNatives` in `JNI_OnLoad` and loads its native libraries automatically. If you still have old `System.loadLibrary("compositor")` calls, remove them — they'll conflict with WLDroid's internal loading.

### GPU Mode Not Detected

Check that the device runs Android 10+ (API 29) and that Vulkan drivers are available. Use `adb logcat -s WLDroid` to see GPU detection logs. You can force a specific mode via `VirglConfig(gpuMode = GpuMode.SOFTWARE)`.

### Proot Environment Fails to Start

Verify that the rootfs was fully extracted by checking `EnvironmentRegistry.getAll()`. Incomplete extractions (e.g., from interrupted downloads) should be deleted and re-created.

### Shim Libraries Not Loading

Ensure `ShimExtractor.extract()` was called before launching proot. Check that `LD_PRELOAD` and `LD_LIBRARY_PATH` environment variables are set correctly in `ProotConfig.envVars`.
