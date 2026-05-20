# Integration Guide

This guide explains how to consume WLDroid as a library in your Android application. WLDroid is modular — you can use individual modules or the full stack depending on your needs.

## Gradle Dependency Setup

### Repository Setup

WLDroid artifacts are available from GitHub Releases (recommended) or GitHub Packages.

See [CONSUMING.md](../CONSUMING.md) for full details on all resolution options, including the Maven repo zip from GitHub Releases (no authentication required) and GitHub Packages (requires a PAT with `read:packages` scope).

Quick setup using the Maven repo zip from a GitHub Release:

```kotlin
// settings.gradle.kts
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven {
            // Path to extracted wldroid-maven-repo.zip from the GitHub Release
            url = uri("/path/to/wldroid-maven")
            content { includeGroup("nu.shel.wldroid") }
        }
    }
}
```

Or with GitHub Packages (requires authentication):

```kotlin
// settings.gradle.kts
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven {
            name = "WLDroid"
            url = uri("https://maven.pkg.github.com/Shelnutt2/wldroid")
            credentials {
                username = providers.gradleProperty("gpr.user").orNull
                    ?: System.getenv("GITHUB_ACTOR")
                password = providers.gradleProperty("gpr.key").orNull
                    ?: System.getenv("GITHUB_TOKEN")
            }
        }
    }
}
```

> **Note:** GitHub Packages requires authentication even for public packages. You'll need a GitHub personal access token (classic) with `read:packages` scope. Set it in `~/.gradle/gradle.properties`:
> ```properties
> gpr.user=YOUR_GITHUB_USERNAME
> gpr.key=YOUR_GITHUB_TOKEN
> ```
> In CI environments, use the `GITHUB_ACTOR` and `GITHUB_TOKEN` environment variables instead.

### Option 1: Full Stack (Recommended)

Include the `:ui` module, which transitively pulls in all library modules:

```kotlin
// app/build.gradle.kts
dependencies {
    implementation("nu.shel.wldroid:wldroid-ui:<version>")
}
```

### Option 2: Individual Modules

Pick only what you need:

```kotlin
dependencies {
    // Core compositor only (no proot, no GPU management)
    implementation("nu.shel.wldroid:wldroid-compositor:<version>")

    // Add environment management
    implementation("nu.shel.wldroid:wldroid-proot:<version>")

    // Add GPU detection and VirGL server
    implementation("nu.shel.wldroid:wldroid-virgl:<version>")

    // Add shim libraries for Linux graphics bridging
    implementation("nu.shel.wldroid:wldroid-shims:<version>")
}
```

### Option 3: Source Dependency

If you prefer to build from source, include WLDroid as a composite build or git submodule:

```kotlin
// settings.gradle.kts — include WLDroid as a composite build or git submodule
includeBuild("path/to/wldroid") {
    dependencySubstitution {
        substitute(module("nu.shel.wldroid:wldroid-ui")).using(project(":ui"))
        substitute(module("nu.shel.wldroid:wldroid-compositor")).using(project(":compositor"))
        substitute(module("nu.shel.wldroid:wldroid-proot")).using(project(":proot"))
        substitute(module("nu.shel.wldroid:wldroid-virgl")).using(project(":virgl"))
        substitute(module("nu.shel.wldroid:wldroid-shims")).using(project(":shims"))
    }
}
```

```kotlin
// app/build.gradle.kts
dependencies {
    implementation("nu.shel.wldroid:wldroid-ui")
}
```

## Required Permissions

Add to your `AndroidManifest.xml`:

```xml
<!-- Required: Internet access for rootfs download -->
<uses-permission android:name="android.permission.INTERNET" />

<!-- Required: Network state for DNS configuration -->
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />

<!-- Optional: External storage for bind mounts -->
<uses-permission android:name="android.permission.READ_EXTERNAL_STORAGE" />
<uses-permission android:name="android.permission.WRITE_EXTERNAL_STORAGE" />
```

## Integration Levels

### Level 1: Just the Compositor

The simplest integration — embed a Wayland compositor surface that Linux applications can connect to:

```kotlin
import nu.shel.wldroid.compositor.*

class MyActivity : ComponentActivity() {
    private val server = CompositorServer()
    private lateinit var session: CompositorSession

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        session = CompositorSession(
            CompositorConfig(
                cacheDir = cacheDir.absolutePath,
                xkbBasePath = "$cacheDir/xkb",
                gpuMode = "SOFTWARE",
            )
        )

        setContent {
            // Observe compositor state
            val state by session.state.collectAsState()
            val clientCount by session.clientCount.collectAsState()

            Column {
                Text("Compositor: $state, Clients: $clientCount")

                AndroidView(
                    factory = { ctx ->
                        SurfaceView(ctx).apply {
                            holder.addCallback(object : SurfaceHolder.Callback {
                                override fun surfaceCreated(holder: SurfaceHolder) {
                                    session.start(holder.surface)
                                }
                                override fun surfaceDestroyed(holder: SurfaceHolder) {
                                    session.stop()
                                }
                                override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, h2: Int) {
                                    session.resizeOutput(w, h2)
                                }
                            })
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}
```

### Level 2: Compositor + Compose UI

Use the pre-built `CompositorSurface` Compose component:

```kotlin
import nu.shel.wldroid.ui.CompositorKeyboardController
import nu.shel.wldroid.ui.CompositorSurface
import nu.shel.wldroid.ui.rememberCompositorSurfaceState
import nu.shel.wldroid.compositor.*

@Composable
fun WaylandScreen() {
    var compositorState by remember { mutableStateOf(CompositorState.IDLE) }
    var keyboardController by remember { mutableStateOf<CompositorKeyboardController?>(null) }
    val config = CompositorConfig(
        cacheDir = LocalContext.current.cacheDir.absolutePath,
        gpuMode = "AUTO",
        xwaylandEnabled = true,
    )
    val surfaceState = rememberCompositorSurfaceState(config)

    CompositorSurface(
        modifier = Modifier.fillMaxSize(),
        config = config,
        surfaceState = surfaceState,
        onStateChange = { compositorState = it },
        onClientCountChange = { /* update UI */ },
        onKeyboardControllerChange = { keyboardController = it },
        // Optional: reserve two-finger Android host pinch/pan for viewport zoom.
        // Defaults off so two-finger guest gestures still work out of the box.
        // This does not change guest Wayland output size or DPI.
        enableViewportGestures = true,
    )
}
```

To run without XWayland (Wayland-only apps), set `xwaylandEnabled = false`:

```kotlin
val config = CompositorConfig(
    cacheDir = context.cacheDir.absolutePath,
    xwaylandEnabled = false,  // Skip XWayland initialization entirely
)
```

When XWayland is disabled, the compositor skips all X11 support initialization.
The `CompositorSession.xwaylandDisplay` StateFlow will emit `null`. Only pure
Wayland clients can connect. This reduces startup overhead and avoids the need
for an XWayland binary in the proot environment.

### Android viewport zoom and keyboard behavior

`CompositorSurface` defaults are designed to work without app-side glue:

- Software keyboard requests from focused Wayland text inputs are handled automatically through the native IME pipe and Android `InputConnection`.
- The built-in keyboard FAB calls `CompositorKeyboardController.toggle()` and respects `InputMode`; disabling keyboard input hides/restarts the IME.
- `KeyboardPanBehavior.PanWithinImeSafeArea` is the default, allowing the host viewport to pan content above the Android keyboard. Use `KeyboardPanBehavior.None` if your surrounding UI handles IME overlap itself.
- Host viewport gestures default to disabled. Set `enableViewportGestures = true` only if Android should reserve two-finger pinch/pan for viewport zoom instead of forwarding those gestures to the guest.
- Viewport zoom/pan never changes the Wayland output size, DPI, or client layout.

For custom controls, keep a `surfaceState` and call:

```kotlin
surfaceState.zoomIn()
surfaceState.zoomOut()
surfaceState.setZoom(2f)
surfaceState.panBy(dx = -24f, dy = 0f)
surfaceState.resetZoom()

val guestPoint = surfaceState.mapViewToGuest(viewX, viewY)
keyboardController?.show()
keyboardController?.hide()
```

If an Activity hosts WLDroid fullscreen, prefer `android:windowSoftInputMode="adjustNothing"` so Android does not resize the Activity while `CompositorSurface` manages viewport panning for IME overlap.

### Level 3: Full Stack (Compositor + Proot + GPU)

Wire up the complete pipeline — environment management, GPU detection, VirGL server, and shim extraction:

```kotlin
class DesktopActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val scope = lifecycleScope

        setContent {
            var status by remember { mutableStateOf("Initializing...") }

            LaunchedEffect(Unit) {
                // 1. Detect GPU capabilities
                val gpuDetector = GpuCapabilityDetector(this@DesktopActivity)
                val gpuMode = gpuDetector.detectBestGpuMode()
                status = "GPU: ${gpuMode.displayName}"

                // 2. Start VirGL server if needed
                if (gpuMode.requiresVirglServer) {
                    val virglSession = VirglSession(
                        VirglConfig(
                            virglBinaryPath = extractVirglBinary(),
                            socketPath = "$cacheDir/.virgl_test",
                            gpuMode = gpuMode,
                        )
                    )
                    virglSession.start()
                    status = "VirGL server running"
                }

                // 3. Extract shim libraries
                val shimExtractor = ShimExtractor(this@DesktopActivity)
                val shims = shimExtractor.extractForGpuMode(
                    "$cacheDir/shims", gpuMode.name
                )
                status = "Shims extracted"

                // 4. Ensure proot environment exists
                val store = RootfsStore(this@DesktopActivity)
                val downloader = RootfsDownloader(cacheDir)
                val extractor = RootfsExtractor()
                val rootfsDir = File(filesDir, "rootfs")
                val manager = RootfsManager(rootfsDir, store, downloader, extractor)
                val registry = EnvironmentRegistry(manager, store, scope)

                val envs = registry.environments.first()
                val env = if (envs.isEmpty()) {
                    status = "Creating environment..."
                    registry.create(
                        EnvironmentConfig(
                            name = "Desktop",
                            distro = DistroTemplate.DEBIAN_TRIXIE,
                        )
                    )
                } else {
                    envs.first()
                }

                status = "Ready — ${env.name}"
            }

            Column {
                Text(status)
                CompositorSurface(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    config = CompositorConfig(
                        cacheDir = cacheDir.absolutePath,
                        gpuMode = "AUTO",
                    ),
                    onStateChange = {},
                    onClientCountChange = {},
                )
            }
        }
    }
}
```

## Hilt Integration

WLDroid modules support optional Hilt dependency injection. If your app uses Hilt:

```kotlin
@Module
@InstallIn(SingletonComponent::class)
object WldroidModule {

    @Provides
    @Singleton
    fun provideRootfsStore(@ApplicationContext context: Context): RootfsStore =
        RootfsStore(context)

    @Provides
    @Singleton
    fun provideRootfsDownloader(@ApplicationContext context: Context): RootfsDownloader =
        RootfsDownloader(context.cacheDir)

    @Provides
    @Singleton
    fun provideRootfsExtractor(): RootfsExtractor = RootfsExtractor()

    @Provides
    @Singleton
    fun provideGpuCapabilityDetector(@ApplicationContext context: Context): GpuCapabilityDetector =
        GpuCapabilityDetector(context)

    @Provides
    @Singleton
    fun provideGpuModeStore(@ApplicationContext context: Context): GpuModeStore =
        GpuModeStore(context)

    @Provides
    @Singleton
    fun provideShimExtractor(@ApplicationContext context: Context): ShimExtractor =
        ShimExtractor(context)
}
```

## ProGuard / R8 Considerations

WLDroid uses JNI and reflection in specific areas. Add these rules to your `proguard-rules.pro`:

```proguard
# Keep JNI bridge class — native methods registered via RegisterNatives
-keep class nu.shel.wldroid.compositor.CompositorServer { *; }

# Keep all enum classes (used in StateFlow)
-keepclassmembers enum nu.shel.wldroid.** {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# Keep data classes used in DataStore serialization
-keep class nu.shel.wldroid.proot.RootfsEnvironment { *; }
-keep class nu.shel.wldroid.proot.RootfsStatus { *; }
```

## Android SDK Requirements

| Requirement | Value |
|-------------|-------|
| compileSdk | 35 |
| minSdk | 29 (Android 10) |
| targetSdk | 35 |
| NDK | r28 |
| Java | 17 |

**Why minSdk 29?** WLDroid uses:
- `AHardwareBuffer` APIs added in API 26, with usage flags added in API 29
- `native_handle_t` interop for DMA-BUF export (API 29+)
- `FileDescriptor` APIs for Unix socket communication

## Startup Ordering

WLDroid's desktop pipeline has a strict startup ordering. The high-level
`CompositorConfigFactory` and `DesktopLauncher` handle this automatically,
but the ordering is documented here for apps that wire components manually.

### Required order

```
1. Proot environment         EnvironmentRegistry.create() or RootfsManager
2. XWayland preparation      XWaylandManager.prepare(environment, tempDir)
3. Compositor config          CompositorConfig (with xwaylandBinaryPath from step 2)
4. Compositor session         CompositorSession(config) + start(surface)
5. GPU detection + VirGL      VirglSession.start() (after compositor is RUNNING)
6. Shim extraction            ShimExtractor.extractAll()
7. GPU setup (Phase 1)        GpuSetupManager.setup() inside proot
8. Package installation       PackageInstaller.installPackages()
9. App launch (Phase 2)       launch-app.sh inside proot
```

`DesktopLauncher` refreshes `/etc/resolv.conf` before GPU setup, package
installation, and app launch so `apt-get` and the launched application see the
current Android network DNS. Package installation failures abort the launch; they
are not ignored.

Steps 2 and 3 are the most common source of ordering bugs: if the XWayland
wrapper script is not extracted before `CompositorConfig` is constructed, the
`xwaylandBinaryPath` will reference a non-existent file and the compositor
will fail to start XWayland.

### Recommended: use CompositorConfigFactory

`CompositorConfigFactory` performs steps 2 and 3 atomically, so downstream
apps cannot construct a config with a dangling wrapper path:

```kotlin
val xwaylandManager = XWaylandManager(prootConfig, cacheDir)
val factory = CompositorConfigFactory(xwaylandManager)
val config = factory.createWithXWayland(
    environment = rootfsEnvironment,
    cacheDir = context.cacheDir.absolutePath,
    tempDir = launcherConfig.tempDir,
    xkbBasePath = xkbPath,
)
val session = CompositorSession(config)
```

### Recommended: use DesktopSession

`DesktopSession` handles steps 4 through 9 internally. Combined with
`CompositorConfigFactory` for steps 2 and 3, downstream apps only need to
manage step 1 (environment creation) and surface lifecycle. Prefer this path
when you have an Android `Surface` available:

```kotlin
// Step 1: environment
val env = environmentRegistry.environments.first().first()

// Steps 2-3: config via factory
val factory = CompositorConfigFactory(xwaylandManager)
val config = factory.createWithXWayland(
    environment = env,
    cacheDir = context.cacheDir.absolutePath,
    tempDir = launcherConfig.tempDir,
)
val session = CompositorSession(config)

// Steps 4-9: DesktopSession starts the compositor, then the launcher.
val launcher = DesktopLauncher(
    context = context,
    compositorSession = session,
    virglSession = virglSession,
    shimExtractor = shimExtractor,
    prootExecutor = prootExecutor,
    config = launcherConfig,
    xwaylandManager = xwaylandManager,
)
val desktopSession = DesktopSession(session, launcher)
desktopSession.start(surface, env, listOf("code", "--no-sandbox"), scope = lifecycleScope)

// If you call DesktopLauncher directly, start CompositorSession first and wait
// until it reports a socketPath. The launcher fails fast when the Wayland socket
// or required environment is missing, so launch ordering issues surface as
// startup errors instead of a black window.
```

### Manual wiring (advanced)

If you need full control, call the low-level APIs in order:

```kotlin
// 1. Prepare XWayland
val result = xwaylandManager.prepare(environment, tempDir)

// 2. Build config with the ready path
val config = CompositorConfig(
    cacheDir = cacheDir,
    xkbBasePath = xkbPath,
    xwaylandEnabled = true,
    xwaylandBinaryPath = result.wrapperScriptPath,
    gpuMode = "AUTO",
)

// 3. Validate before starting (optional, start() validates too)
config.validate()

// 4. Start compositor
val session = CompositorSession(config)
session.start(surface)
```

## Lifecycle Management

### DesktopSession (Recommended)

`DesktopSession` is the recommended high-level API for managing the entire desktop session lifecycle. It wraps `CompositorSession` and `DesktopLauncher` to provide a single cleanup path:

```kotlin
val session = DesktopSession(compositorSession, launcher)

// Start: compositor + full launch pipeline
session.start(surface, environment, command, scope)

// Stop: deterministic teardown of all resources
session.stop()

// Restart: stop + start in one call (e.g. for retry flows)
session.restart(surface, environment, command, scope)
```

`stop()` tears down resources in the correct order:
1. **Launcher**: kills the proot process (SIGTERM, then SIGKILL), stops VirGL server, cleans stale files.
2. **Compositor**: shuts down the native Wayland server, cleans wayland sockets.

The session can be restarted after `stop()` completes.

> **Note:** `DesktopSession.stop()` is the single documented cleanup path. Downstream apps do not need to call `compositorSession.stop()` or `virglSession.stop()` separately.

### Process-global State

The compositor sets `AHB_REGISTRY_SOCKET` and `WLR_XWAYLAND` as process-global environment variables via `Os.setenv()`. These survive across session restarts within the same Android process. This is safe for single-session usage (one compositor at a time), but callers should be aware that these values persist until the process exits.

### CompositorSession Lifecycle

The `CompositorSession` manages the native compositor lifecycle:

```
IDLE → STARTING → RUNNING → STOPPING → STOPPED
                     ↓                     ↓
                   ERROR              (can restart)
```

- Call `start(surface)` when the Android Surface is ready
- Call `stop()` when the Surface is destroyed or the Activity is finishing
- Observe `state` to react to lifecycle changes
- The compositor thread is automatically cleaned up on `stop()`
- `start()` rejects double-start (throws `IllegalStateException` if STARTING or RUNNING)
- After `stop()`, the session can be restarted by calling `start()` again
- Stale wayland sockets are cleaned on both `start()` and `stop()`

### VirglSession Lifecycle

```
IDLE → DETECTING_GPU → STARTING → RUNNING → STOPPING → STOPPED
                                     ↓                     ↓
                                  UNHEALTHY           (can restart)
                                     ↓
                                   ERROR
```

- `VirglSession` includes a health check that monitors the server process every 5 seconds
- If the server dies unexpectedly, state transitions to `UNHEALTHY`
- `start()` is a no-op if already STARTING or RUNNING
- `stop()` is a no-op if already IDLE or STOPPED
- After `stop()`, the session can be restarted by calling `start()` again

### Environment Lifecycle

```
IDLE → DOWNLOADING → EXTRACTING → INSTALLING → RUNNING → STOPPING → STOPPED
                                                  ↓
                                                ERROR
```

- Environment creation involves downloading a rootfs tarball, extracting it, and configuring the environment
- Progress is observable via `EnvironmentRegistry.getState(id)`

## Common Patterns

### Observing State with Compose

```kotlin
@Composable
fun StatusBar(session: CompositorSession) {
    val state by session.state.collectAsState()
    val clients by session.clientCount.collectAsState()
    val socket by session.socketPath.collectAsState()

    Row {
        Text("State: $state")
        Spacer(Modifier.width(8.dp))
        Text("Clients: $clients")
        socket?.let { Text("Socket: $it") }
    }
}
```

### Error Handling

```kotlin
session.state.collect { state ->
    when (state) {
        CompositorState.ERROR -> {
            // Compositor failed — check logcat for native errors
            // Attempt restart or show error UI
        }
        CompositorState.STOPPED -> {
            // Clean shutdown — safe to release resources
        }
        else -> {}
    }
}
```

### Input Forwarding

Prefer `CompositorSurface` unless you need a custom Android view. If you manage your own `SurfaceView`, you are responsible for forwarding input, mapping host viewport coordinates, and handling IME requests.

```kotlin
val input = session.input
var viewport = ViewportTransform()

// Forward touch events. If you implement host zoom/pan, map view pixels back
// to the fixed Wayland output coordinate space before sending to native.
surfaceView.setOnTouchListener { _, event ->
    val guest = viewport.mapViewToGuest(event.x, event.y)
    input.sendTouchEvent(
        id = event.getPointerId(0),
        action = event.action,
        x = guest.x,
        y = guest.y,
        timestampMs = event.eventTime,
    )
    true
}

// Forward keyboard events.
override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
    input.sendKeyEvent(keyCode, KeyEvent.ACTION_DOWN, event.eventTime)
    return true
}

// Implement InputConnection deletion using the correct Android API.
override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
    input.deleteSurroundingText(beforeLength, afterLength) // UTF-16 counts
    return true
}

override fun deleteSurroundingTextInCodePoints(beforeLength: Int, afterLength: Int): Boolean {
    input.deleteSurroundingTextInCodePoints(beforeLength, afterLength)
    return true
}
```

Custom views should also read `input.getImePipeFd()` on a background/coroutine thread. The pipe emits `S` when the focused Wayland text input requests Android IME show and `H` when it requests hide. Notify native with `input.notifyImeShown()` and `input.notifyImeHidden()` when your view shows or hides the Android keyboard. Managed `CompositorSurface` already implements this, including nonblocking pipe polling, active text-input focus checks, and bounded delete fallback handling.
