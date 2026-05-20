# API Reference

Complete Kotlin API documentation for all WLDroid modules. Native (C) APIs are documented in the [Compositor Architecture](compositor-architecture.md) doc.

## `:compositor` Module

**Package:** `nu.shel.wldroid.compositor`

### CompositorServer

Low-level JNI bridge to the native wlroots compositor. Loads `libwldroid-compositor.so`.

```kotlin
class CompositorServer {
    companion object {
        init { System.loadLibrary("wldroid-compositor") }
    }

    // Lifecycle
    external fun nativeStartCompositor(
        surface: Surface,
        cacheDir: String,
        xkbBasePath: String,
        xwaylandEnabled: Boolean,
    )
    external fun nativeStopCompositor()
    external fun nativePauseCompositor()
    external fun nativeResumeCompositor(surface: Surface)
    external fun nativeGetSocketName(): String?
    external fun nativeGetClientCount(): Int
    external fun nativeGetXWaylandDisplay(): String?
    external fun nativeResizeOutput(width: Int, height: Int)

    // Input events
    external fun nativeSendTouchEvent(id: Int, action: Int, x: Float, y: Float, timestampMs: Long)
    external fun nativeSendKeyEvent(androidKeyCode: Int, action: Int, timestampMs: Long)
    external fun nativeSendPointerMotion(x: Float, y: Float, timestampMs: Long)
    external fun nativeSendPointerButton(button: Int, action: Int, timestampMs: Long)
    external fun nativeSendPointerScroll(dx: Float, dy: Float, timestampMs: Long)

    // IME (Input Method Editor)
    external fun nativeCommitText(text: String)
    external fun nativeDeleteSurroundingText(beforeLength: Int, afterLength: Int)
    external fun nativeDeleteSurroundingTextInCodePoints(beforeLength: Int, afterLength: Int)
    external fun nativeImeShown()
    external fun nativeImeHidden()
    external fun nativeGetImePipeFd(): Int
    external fun nativeHasActiveTextInput(): Boolean

    // Testing
    external fun nativeStartTestClient()
}
```

> **Note:** Prefer `CompositorSession` for application-level use. `CompositorServer` is the raw JNI layer.

### CompositorSession

High-level compositor API with observable state via StateFlow.

```kotlin
class CompositorSession(config: CompositorConfig) {
    // Observable state
    val state: StateFlow<CompositorState>       // Lifecycle state
    val clientCount: StateFlow<Int>             // Connected Wayland client count
    val socketPath: StateFlow<String?>          // Wayland socket path (null until RUNNING)
    val xwaylandDisplay: StateFlow<String?>     // X11 DISPLAY name (e.g. ":0"), null if disabled/failed

    // Input access
    val input: CompositorInput                  // Input event dispatcher (new instance per access)

    // Lifecycle
    fun start(surface: Surface)                 // Start compositor; rejects double-start
    fun pause()                                 // Pause compositor (detach native window)
    fun resume(surface: Surface)                // Resume compositor (attach new native window)
    fun stop()                                  // Stop compositor → STOPPING→STOPPED
    fun stopAsync()                             // Stop on background thread (Mali GPU workaround)
    fun resizeOutput(width: Int, height: Int)   // Handle surface resize
    fun refreshClientCount()                    // Poll native client count
    fun getClientCount(): Int                   // Poll + return client count
}
```

Lifecycle notes:
- `start()` throws `IllegalStateException` if already STARTING or RUNNING.
- After `stop()`, the session resets its internal guard and can be restarted via `start()`.
- Both `stop()` and `stopAsync()` clean stale wayland sockets and reset `socketPath` and `xwaylandDisplay` to null.
- `stopAsync()` runs shutdown on a background thread to avoid Mali GPU SIGSEGV.
- `xwaylandDisplay` is null when `CompositorConfig.xwaylandEnabled` is false, when XWayland failed to start, or before `start()` completes.
- `pause()` and `resume()` manage the native window lifecycle for Android Activity start/stop.

### CompositorConfig

Configuration for the compositor session.

```kotlin
data class CompositorConfig(
    val cacheDir: String = "",              // Cache directory for temp files
    val xkbBasePath: String = "",           // XKB keyboard layout base path
    val xwaylandEnabled: Boolean = true,    // Enable X11 compatibility (runtime toggle)
    val xwaylandBinaryPath: String = "",    // Path to XWayland wrapper script (from XWaylandManager)
    val xwaylandTmpDir: String = "",        // Override /tmp for XWayland sockets (required on Android)
    val gpuMode: String = "AUTO",           // GPU mode (AUTO, SOFTWARE, VIRGL_GLES, etc.)
    val testClientEnabled: Boolean = false,  // Enable built-in test pattern client
    val ahbRegistrySocketPath: String = "", // AHB registry socket for GPU buffer sharing
) {
    // Validates that all required paths exist and settings are consistent.
    // Throws IllegalStateException if xwaylandEnabled is true and
    // xwaylandBinaryPath is non-empty but references a non-existent file.
    // Validation is skipped when xwaylandEnabled is false.
    fun validate()

    companion object {
        fun default(): CompositorConfig
    }
}
```

### CompositorState

Compositor lifecycle states.

```kotlin
enum class CompositorState {
    IDLE,       // Not started
    STARTING,   // Spawning native thread
    RUNNING,    // Compositor active, accepting Wayland clients
    PAUSED,     // Native window detached; clients may remain connected
    STOPPING,   // Shutting down
    STOPPED,    // Clean shutdown
    ERROR;      // Fatal error

    val isRunning: Boolean // true for RUNNING and PAUSED
}
```

### CompositorInput

Input event dispatcher — wraps `CompositorServer` JNI calls with a cleaner API.

```kotlin
class CompositorInput(server: CompositorServer) {
    // Touch
    fun sendTouchEvent(id: Int, action: Int, x: Float, y: Float, timestampMs: Long)

    // Keyboard
    fun sendKeyEvent(androidKeyCode: Int, action: Int, timestampMs: Long)

    // Pointer/Mouse
    fun sendPointerMotion(x: Float, y: Float, timestampMs: Long)
    fun sendPointerButton(button: Int, action: Int, timestampMs: Long)
    fun sendPointerScroll(dx: Float, dy: Float, timestampMs: Long)

    // IME
    fun commitText(text: String)
    fun deleteSurroundingText(beforeLength: Int, afterLength: Int)
    fun deleteSurroundingTextInCodePoints(beforeLength: Int, afterLength: Int)
    fun notifyImeShown()
    fun notifyImeHidden()
    fun getImePipeFd(): Int
    fun hasActiveTextInput(): Boolean
}
```

`deleteSurroundingText()` accepts Android/Java UTF-16 counts. `deleteSurroundingTextInCodePoints()` is for IMEs or custom views that already provide Unicode code-point counts. Counts are clamped natively to keep fallback synthetic delete handling bounded. `getImePipeFd()` is a low-level signal pipe used by custom Android views to learn when the focused Wayland text input asks the host IME to show (`S`) or hide (`H`). Managed `CompositorSurface` reads this pipe for you.


---

## `:proot` Module

**Package:** `nu.shel.wldroid.proot`

### EnvironmentRegistry

Dynamic environment management — CRUD operations for Linux rootfs environments.

```kotlin
class EnvironmentRegistry(
    rootfsManager: RootfsManager,
    rootfsStore: RootfsStore,
    scope: CoroutineScope,
) {
    // Observable state
    val environments: StateFlow<List<RootfsEnvironment>>

    // CRUD operations
    fun create(config: EnvironmentConfig): StateFlow<EnvironmentProgress>
    suspend fun delete(id: String)
    suspend fun duplicate(id: String, newName: String): RootfsEnvironment

    // Import/Export
    suspend fun importRootfs(tarballPath: String, name: String = "Imported"): RootfsEnvironment
    suspend fun exportRootfs(id: String, outputPath: String)

    // State observation
    fun getState(id: String): StateFlow<EnvironmentProgress>

    // Available distros
    fun availableDistros(): List<DistroTemplate>
}
```

### EnvironmentConfig

Configuration for creating a new environment.

```kotlin
data class EnvironmentConfig(
    val name: String,
    val distro: DistroTemplate,
    val bindMounts: List<BindMount> = emptyList(),
    val environmentVariables: Map<String, String> = emptyMap(),
)
```

### EnvironmentState

Environment lifecycle states.

```kotlin
enum class EnvironmentState {
    IDLE, DOWNLOADING, EXTRACTING, INSTALLING, RUNNING, STOPPING, STOPPED, ERROR
}
```

### EnvironmentProgress

Progress snapshot emitted while an environment is being created or changed.

```kotlin
data class EnvironmentProgress(
    val state: EnvironmentState,
    val progress: Float = -1f,
    val message: String = "",
)
```

### RootfsManager

Low-level rootfs lifecycle management — download, extract, configure.

```kotlin
class RootfsManager(
    rootfsBaseDir: File,
    rootfsStore: RootfsStore,
    downloader: RootfsDownloader,
    extractor: RootfsExtractor,
) {
    fun hasEnvironment(id: String): Boolean
    suspend fun deleteEnvironment(id: String)
    fun getEnvironments(): Flow<List<RootfsEnvironment>>
    fun getDiskUsage(envId: String): Long
    fun getTotalDiskUsage(): Long
    fun getEnvironmentDir(id: String): File

    // Full download → extract → configure pipeline
    fun createEnvironment(
        id: String,
        name: String = id,
        distro: DistroTemplate = DistroTemplate.DEBIAN_TRIXIE,
    ): Flow<RootfsProgress>
}
```

### ProotExecutor

Builds and executes proot commands.

```kotlin
class ProotExecutor(config: ProotConfig) {
    // Build a ProcessBuilder for a proot command
    fun buildCommand(
        environment: RootfsEnvironment,
        command: List<String>,
        envVars: Map<String, String> = emptyMap(),
        bindMounts: List<BindMount> = emptyList(),
    ): ProcessBuilder

    // Run a command in proot and wait for completion
    suspend fun runInProot(
        environment: RootfsEnvironment,
        command: List<String>,
        envVars: Map<String, String> = emptyMap(),
        bindMounts: List<BindMount> = emptyList(),
        onOutput: ((String) -> Unit)? = null,
    ): Int  // exit code
}
```

### ProotConfig

Configuration for the proot executor.

```kotlin
data class ProotConfig(
    val prootBinaryPath: String,                        // Path to proot binary
    val prootLoaderPath: String = "",                    // Path to proot loader
    val defaultDistro: DistroTemplate = DistroTemplate.DEBIAN_TRIXIE,
    val rootfsBaseDir: String = "",                      // Base directory for rootfs environments
    val cacheDir: String = "",                           // Cache directory
    val fakeRoot: Boolean = true,                        // Run as fake root (UID 0)
    val link2symlink: Boolean = true,                    // Enable link2symlink translation
)
```

### BindMount

Represents a bind mount between host and guest paths.

```kotlin
data class BindMount(
    val hostPath: String,
    val guestPath: String,
    val readOnly: Boolean = false,
)
```

### DistroTemplate

Available Linux distribution templates.

```kotlin
enum class DistroTemplate(
    val displayName: String,
    val downloadUrl: String,
    val sha256: String,
    val version: String,
) {
    DEBIAN_TRIXIE,      // Debian Trixie (testing) — proot-distro v4.26.0
    DEBIAN_BOOKWORM,    // Debian Bookworm (stable) — proot-distro v4.6.0
}
```

### RootfsEnvironment

Data class representing a rootfs environment.

```kotlin
data class RootfsEnvironment(
    val id: String,
    val name: String = id,
    val rootfsPath: String,
    val distro: String = "",
    val createdAt: Long,
    val sizeBytes: Long = 0,
    val lastUsedAt: Long? = null,
    val status: RootfsStatus = RootfsStatus.READY,
)
```

### RootfsStatus

```kotlin
enum class RootfsStatus { READY, DOWNLOADING, EXTRACTING, INSTALLING, ERROR }
```

### RootfsProgress

Progress indicator for rootfs creation.

```kotlin
data class RootfsProgress(
    val status: RootfsStatus,
    val progress: Float = -1f,  // 0.0–1.0, or -1 for indeterminate
)
```

### RootfsDownloader

HTTP downloader with resume support.

```kotlin
class RootfsDownloader(cacheDir: File) {
    suspend fun download(
        url: String,
        destFile: File,
        onProgress: ((bytesRead: Long, totalBytes: Long) -> Unit)? = null,
    )
    fun verifySha256(file: File, expectedHash: String): Boolean
    fun getCachedTarball(url: String): File?
    fun getCacheFile(url: String): File
}
```

### RootfsExtractor

Extracts tar.xz archives with symlink/hardlink/permission support.

```kotlin
class RootfsExtractor {
    suspend fun extract(
        tarXzFile: File,
        destDir: File,
        stripComponents: Int = 0,
        onProgress: ((entriesExtracted: Int) -> Unit)? = null,
    )
}
```

### RootfsStore

DataStore-backed persistence for environment metadata.

```kotlin
class RootfsStore(context: Context) {
    fun getEnvironments(): Flow<List<RootfsEnvironment>>
    suspend fun addEnvironment(env: RootfsEnvironment)
    suspend fun removeEnvironment(id: String)
    suspend fun updateEnvironment(id: String, transform: (RootfsEnvironment) -> RootfsEnvironment)
    fun getAutoDeleteEnabled(): Flow<Boolean>
    suspend fun setAutoDeleteEnabled(enabled: Boolean)
}
```

### ProotDnsManager

DNS configuration for proot environments.

```kotlin
class ProotDnsManager(context: Context) {
    fun getSystemDnsServers(): List<String>  // Queries ConnectivityManager, falls back to 8.8.8.8
    fun writeResolvConf(rootfsDir: File)     // Writes /etc/resolv.conf with device DNS
}
```

---

## `:virgl` Module

**Package:** `nu.shel.wldroid.virgl`

### VirglSession

High-level VirGL session management with GPU auto-detection.

```kotlin
class VirglSession(
    config: VirglConfig,
    serverManager: VirglServerManager = VirglServerManager(config),
    gpuDetector: GpuCapabilityDetector? = null,
) {
    // Observable state
    val state: StateFlow<VirglState>              // Session lifecycle
    val detectedGpuMode: StateFlow<GpuMode>       // Resolved GPU mode

    // Lifecycle
    suspend fun start()     // Resolve GPU mode, start server, launch health monitor
    suspend fun stop()      // Stop server, cancel health monitor

    // Health
    fun isHealthy(): Boolean  // true if RUNNING and server process alive

    companion object {
        const val HEALTH_CHECK_INTERVAL_MS = 5000L
    }
}
```

### VirglServerManager

Low-level VirGL server process management.

```kotlin
class VirglServerManager(config: VirglConfig) {
    val socketDir: File
    val socketPath: String
    val serverBinaryPath: String
    val isRunning: Boolean          // process.isAlive

    // VirGL mode: virgl_test_server --use-egl-surfaceless --use-gles
    // Venus mode: virgl_test_server --no-virgl --venus
    // Both: --no-fork --multi-clients
    suspend fun start(gpuMode: GpuMode, scope: CoroutineScope): Boolean
    suspend fun stop()              // Graceful (3s timeout) then force kill
}
```

### GpuCapabilityDetector

Runtime GPU capability detection.

```kotlin
class GpuCapabilityDetector(context: Context) {
    // Auto-detect (priority: Turnip > VirGL Zink > VirGL GLES > Software)
    fun detectBestGpuMode(virglAvailable: Boolean = true): GpuMode

    // Individual capability checks
    fun isKgslAccessible(): Boolean      // /dev/kgsl-3d0 readable
    fun isAdrenoGpu(): Boolean           // /sys/class/kgsl/kgsl-3d0/gpu_model
    fun hasVulkanSupport(): Boolean      // FEATURE_VULKAN_HARDWARE_LEVEL

    // Diagnostic output
    fun getGpuInfo(): String             // Multi-line diagnostic report
    fun getGpuSummary(): String          // One-line summary for settings UI
}
```

### GpuMode

GPU rendering mode enum.

```kotlin
enum class GpuMode(val displayName: String, val description: String) {
    SOFTWARE("Software", "CPU-only rendering"),
    VIRGL_GLES("VirGL GLES", "OpenGL ES via VirGL server"),
    VIRGL_ZINK("VirGL Zink", "Vulkan-based VirGL rendering"),
    VENUS("Venus", "Experimental Vulkan passthrough"),
    TURNIP_DIRECT("Turnip Direct", "Native Qualcomm GPU access"),
    AUTO("Auto-detect", "Automatically select best mode");

    val requiresVirglServer: Boolean
        // true for VIRGL_GLES, VIRGL_ZINK, VENUS

    companion object {
        fun fromString(value: String): GpuMode  // defaults to AUTO
    }
}
```

### GpuModeStore

DataStore-backed user GPU mode preference.

```kotlin
class GpuModeStore(context: Context) {
    fun getGpuModeOverride(): Flow<GpuMode?>     // null = auto-detect
    suspend fun setGpuModeOverride(mode: GpuMode?)  // null reverts to auto
}
```

### VirglConfig

VirGL server configuration.

```kotlin
data class VirglConfig(
    val virglBinaryPath: String = "",
    val socketPath: String = "",
    val gpuMode: GpuMode = GpuMode.AUTO,
    val venusEnabled: Boolean = false,
    val useZinkBackend: Boolean = false,
)
```

### VirglState

VirGL session lifecycle states.

```kotlin
enum class VirglState {
    IDLE,           // Not started
    DETECTING_GPU,  // Running GPU capability detection
    STARTING,       // Launching server process
    RUNNING,        // Server active and healthy
    UNHEALTHY,      // Server process died unexpectedly
    STOPPING,       // Shutting down
    STOPPED,        // Clean shutdown
    ERROR,          // Fatal error
}
```

---

## `:shims` Module

**Package:** `nu.shel.wldroid.shims`

### ShimExtractor

Extracts shim .so files from APK assets to the filesystem.

```kotlin
class ShimExtractor(context: Context) {
    // Extract all 5 shim libraries
    suspend fun extractAll(targetDir: String): ShimSet

    // Extract only shims needed for a specific GPU mode
    suspend fun extractForGpuMode(targetDir: String, gpuMode: String): ShimSet

    // Check if shims are already extracted
    fun isExtracted(targetDir: String): Boolean

    // Build LD_PRELOAD string for a GPU mode
    fun getLdPreloadString(shimSet: ShimSet, gpuMode: String): String

    // Extracted shim paths
    data class ShimSet(
        val drmShim: String,        // Path to libdrm-shim.so
        val drmWrapper: String,     // Path to libdrm-wrapper.so
        val gbmShim: String,        // Path to libgbm.so.1
        val eglOverride: String,    // Path to libegl_override.so
        val netstub: String,        // Path to libnetstub.so
    )
}
```

### ShimConfig

Per-GPU-mode shim configuration.

```kotlin
data class ShimConfig(
    val enableDrmShim: Boolean = true,
    val enableGbmShim: Boolean = true,
    val enableEglOverride: Boolean = true,
    val enableNetstub: Boolean = true,
    val enableDrmWrapper: Boolean = true,
) {
    companion object {
        fun forGpuMode(gpuMode: String): ShimConfig
        // SOFTWARE     → drm=false, gbm=false, egl=false, netstub=true, wrapper=false
        // TURNIP_DIRECT → drm=true,  gbm=true,  egl=false, netstub=true, wrapper=true
        // (default)     → all enabled
    }
}
```

---

## `:launcher` Module

**Package:** `nu.shel.wldroid.launcher`

### XWaylandManager

Prepares the XWayland wrapper script inside a proot environment.

```kotlin
class XWaylandManager(
    prootConfig: ProotConfig,
    cacheDir: String,
) {
    val wrapperScriptPath: String

    fun extractWrapperScript(environment: RootfsEnvironment): String

    // Extracts the XWayland wrapper script and ensures /tmp/.X11-unix exists.
    // Must be called before CompositorConfig is constructed so that
    // xwaylandBinaryPath points to a real file.
    fun prepare(
        environment: RootfsEnvironment,
        tempDir: String,
    ): XWaylandReadyResult

    fun ensureTmpDirReady(tempDir: String)

    suspend fun isXWaylandInstalled(
        packageInstaller: PackageInstaller,
        environment: RootfsEnvironment,
    ): Boolean
}
```

### XWaylandReadyResult

Result of XWayland preparation.

```kotlin
data class XWaylandReadyResult(
    val wrapperScriptPath: String,  // Absolute path to the extracted wrapper script
)
```

### CompositorConfigFactory

Creates a `CompositorConfig` with XWayland preparation handled atomically.
Prevents the common ordering bug where `CompositorConfig` is constructed
before the XWayland wrapper script exists.

```kotlin
class CompositorConfigFactory(
    private val xwaylandManager: XWaylandManager,
) {
    // Calls XWaylandManager.prepare() and builds a CompositorConfig
    // with the resolved xwaylandBinaryPath in one step.
    fun createWithXWayland(
        environment: RootfsEnvironment,
        cacheDir: String,
        tempDir: String,
        xwaylandConfig: XWaylandConfig = XWaylandConfig(),
        xkbBasePath: String = "",
        gpuMode: String = "AUTO",
        testClientEnabled: Boolean = false,
        ahbRegistrySocketPath: String = "",
    ): CompositorConfig
}
```

### DesktopSession

Unified lifecycle manager for a complete desktop session. Recommended entry point for downstream apps.

```kotlin
class DesktopSession(
    val compositorSession: CompositorSession,
    val launcher: DesktopLauncher,
) {
    val state: StateFlow<DesktopSessionState>

    fun start(
        surface: Surface,
        environment: RootfsEnvironment,
        command: List<String>,
        scope: CoroutineScope,
        requiredPackages: List<String> = emptyList(),
    )

    suspend fun stop()    // Deterministic teardown: launcher + VirGL + compositor

    suspend fun restart(  // stop() + start()
        surface: Surface,
        environment: RootfsEnvironment,
        command: List<String>,
        scope: CoroutineScope,
        requiredPackages: List<String> = emptyList(),
    )
}
```

### DesktopSessionState

```kotlin
enum class DesktopSessionState {
    IDLE,       // No session active
    STARTING,   // Compositor + launcher pipeline starting
    RUNNING,    // Session active
    STOPPING,   // Tearing down all resources
    STOPPED,    // Clean shutdown (can restart)
    ERROR,      // Startup error
}
```

### DesktopLauncher

Orchestrates the two-phase desktop app launch pipeline (GPU setup + app launch). `DesktopLauncher` expects `CompositorSession` to already be started and reporting a Wayland socket; use `DesktopSession` if you want a single API that starts the compositor from an Android `Surface` before launching the app.

```kotlin
class DesktopLauncher(
    context: Context,
    compositorSession: CompositorSession,
    virglSession: VirglSession,
    shimExtractor: ShimExtractor,
    prootExecutor: ProotExecutor,
    config: DesktopLauncherConfig,
) {
    val state: StateFlow<DesktopLauncherState>
    val processOutput: SharedFlow<String>
    val gpuMode: StateFlow<GpuMode>

    fun launch(
        environment: RootfsEnvironment,
        command: List<String>,
        requiredPackages: List<String> = emptyList(),
        scope: CoroutineScope,
    )
    fun launchPreset(environment: RootfsEnvironment, preset: DesktopAppPreset, scope: CoroutineScope)
    suspend fun stop()     // Kills proot, stops VirGL, cleans stale files
}
```

### DesktopLauncherState

```kotlin
sealed class DesktopLauncherState {
    data object Idle : DesktopLauncherState()
    data object StartingCompositor : DesktopLauncherState()
    data object DetectingGpu : DesktopLauncherState()
    data object StartingVirgl : DesktopLauncherState()
    data object ExtractingShims : DesktopLauncherState()
    data object SetupGpu : DesktopLauncherState()
    data object InstallingPackages : DesktopLauncherState()
    data object LaunchingApp : DesktopLauncherState()
    data object Running : DesktopLauncherState()
    data object Stopping : DesktopLauncherState()
    data class Error(val message: String, val phase: String, val cause: Throwable? = null)

    val isActive: Boolean    // true for all non-terminal states
    val isTerminal: Boolean  // true for Idle or Error
}
```

---

## `:ui` Module

**Package:** `nu.shel.wldroid.ui`

### CompositorSurface

Embeddable Compose component that wraps a Wayland compositor.

```kotlin
@Composable
fun CompositorSurface(
    modifier: Modifier = Modifier,
    config: CompositorConfig = CompositorConfig.default(),
    surfaceState: CompositorSurfaceState = rememberCompositorSurfaceState(config),
    onStateChange: (CompositorState) -> Unit = {},
    onClientCountChange: (Int) -> Unit = {},
    onKeyboardControllerChange: (CompositorKeyboardController?) -> Unit = {},
    inputMode: InputMode = InputMode.TOUCH_AND_KEYBOARD,
    showKeyboardFab: Boolean = true,
    enableViewportGestures: Boolean = false,
    minZoom: Float = 1f,
    maxZoom: Float = 4f,
    keyboardPanBehavior: KeyboardPanBehavior = KeyboardPanBehavior.PanWithinImeSafeArea,
    keyboardAutoShowBehavior: KeyboardAutoShowBehavior = KeyboardAutoShowBehavior.TextInputRequestsAndFocusTap,
)
```

`CompositorSurface` is the recommended Android integration. It manages the `SurfaceView`, `CompositorSession`, touch/key/pointer forwarding, IME request pipe, Android `InputConnection`, keyboard FAB, and optional host viewport gestures.

`enableViewportGestures` opts into Android-native two-finger pinch/pan. This only transforms the host viewport; it does not resize the Wayland output, change DPI, or relayout guest clients. The default is `false` so guest multi-touch gestures continue to reach Wayland apps. When enabled, Android reserves two-finger pinch/pan for host zoom.

`keyboardAutoShowBehavior` defaults to `TextInputRequestsAndFocusTap`: focused Wayland text-input requests open the Android IME, and a tap/click on the focused compositor surface also opens it as a practical fallback for XWayland/Electron apps that accept synthetic key input but do not advertise text-input focus. Use `TextInputRequestsOnly` for protocol-only auto-open behavior.

### CompositorSurfaceState

Observable state and imperative controls for a `CompositorSurface`.

```kotlin
class CompositorSurfaceState(
    val session: CompositorSession,
    val config: CompositorConfig,
) {
    val viewport: StateFlow<ViewportTransform>
    val compositorState: StateFlow<CompositorState>
    val clientCount: StateFlow<Int>
    val socketPath: StateFlow<String?>
    val isKeyboardVisible: StateFlow<Boolean>

    fun setViewportScaleBounds(minScale: Float, maxScale: Float)
    fun resetZoom()
    fun zoomBy(factor: Float, focalX: Float, focalY: Float)
    fun setZoom(scale: Float, focalX: Float? = null, focalY: Float? = null)
    fun zoomIn(factor: Float = 1.25f)
    fun zoomOut(factor: Float = 1.25f)
    fun panBy(dx: Float, dy: Float)
    fun setPan(panX: Float, panY: Float)
    fun setViewport(scale: Float, panX: Float, panY: Float)
    fun mapViewToGuest(x: Float, y: Float): GuestPoint
}

@Composable
fun rememberCompositorSurfaceState(
    config: CompositorConfig = CompositorConfig.default(),
): CompositorSurfaceState
```

Use these APIs for downstream toolbar buttons, zoom sliders, reset actions, overlay coordinate translation, and custom keyboard controls without enabling host gestures. All zoom/pan values are Android view-pixel transforms over the fixed Wayland output.

### ViewportTransform

```kotlin
data class ViewportTransform(
    val scale: Float = 1f,
    val panX: Float = 0f,
    val panY: Float = 0f,
    val viewWidth: Int = 0,
    val viewHeight: Int = 0,
    val contentWidth: Int = 0,
    val contentHeight: Int = 0,
    val imeBottomInsetPx: Int = 0,
    val minScale: Float = 1f,
    val maxScale: Float = 4f,
) {
    fun mapViewToGuest(viewX: Float, viewY: Float): GuestPoint
    fun clamped(): ViewportTransform
    fun zoomBy(factor: Float, focalX: Float, focalY: Float): ViewportTransform
    fun panBy(dx: Float, dy: Float): ViewportTransform
    fun reset(): ViewportTransform
}

data class GuestPoint(val x: Float, val y: Float)
```

### CompositorKeyboardController

Controller supplied by `CompositorSurface(onKeyboardControllerChange = ...)` while the Android view is attached.

```kotlin
class CompositorKeyboardController {
    fun show(notifyNative: Boolean = true): Boolean
    fun hide(notifyNative: Boolean = true)
    fun toggle(keyboardInputEnabled: Boolean = true): Boolean
    fun restartInput()
}
```

`show()`, `hide()`, and `toggle()` notify the native compositor about Android IME visibility by default. `restartInput()` asks Android to rebuild the `InputConnection`, which is useful after changing `InputMode` or text-editor capability.

### KeyboardAutoShowBehavior

```kotlin
enum class KeyboardAutoShowBehavior {
    TextInputRequestsOnly,
    TextInputRequestsAndFocusTap,
}
```

`TextInputRequestsAndFocusTap` is the default. It preserves text-input-v3 driven IME requests and adds a tap/click fallback for desktop apps such as VS Code. `TextInputRequestsOnly` disables the fallback and opens the IME only when clients request it through text-input.

### KeyboardPanBehavior

```kotlin
enum class KeyboardPanBehavior {
    None,
    PanWithinImeSafeArea,
}
```

`PanWithinImeSafeArea` is the default and expands the host viewport's upward pan range while the Android IME overlaps the surface. `None` leaves viewport pan bounds unchanged when the IME appears.

### SetupOverlay

Progress overlay shown during environment initialization.

```kotlin
@Composable
fun SetupOverlay(
    modifier: Modifier = Modifier,
    progress: Float,          // 0.0–1.0, or -1 for indeterminate
    statusText: String,       // Current operation description
    onCancel: (() -> Unit)?,  // Optional cancel action
)
```

### GpuModeSelector

Settings component for GPU mode selection.

```kotlin
@Composable
fun GpuModeSelector(
    currentMode: GpuMode,
    detectedMode: GpuMode,
    onModeSelected: (GpuMode?) -> Unit,  // null = revert to auto
    gpuSummary: String,                   // Device GPU info summary
)
```

### EnvironmentPicker

Dialog for selecting an existing environment.

```kotlin
@Composable
fun EnvironmentPicker(
    environments: List<RootfsEnvironment>,
    selectedId: String?,
    onSelect: (String) -> Unit,
    onCreateNew: () -> Unit,
    onDismiss: () -> Unit,
)
```

### EnvironmentCreator

Multi-step wizard for creating a new environment.

```kotlin
@Composable
fun EnvironmentCreator(
    availableDistros: List<DistroTemplate>,
    onConfirm: (EnvironmentConfig) -> Unit,
    onDismiss: () -> Unit,
)
```

### KeyboardToggleFab

Floating action button to toggle the soft keyboard.

```kotlin
@Composable
fun KeyboardToggleFab(
    isKeyboardVisible: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    visible: Boolean = true,
)
```
