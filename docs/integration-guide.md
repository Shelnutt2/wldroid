# Integration Guide

This guide explains how to consume WLDroid as a library in your Android application. WLDroid is modular — you can use individual modules or the full stack depending on your needs.

## Gradle Dependency Setup

### Option 1: Full Stack (Recommended)

Include all modules via the `:ui` module, which transitively pulls in all library modules:

```kotlin
// settings.gradle.kts — include WLDroid as a composite build or git submodule
includeBuild("path/to/wldroid") {
    dependencySubstitution {
        substitute(module("nu.shell.wldroid:ui")).using(project(":ui"))
        substitute(module("nu.shell.wldroid:compositor")).using(project(":compositor"))
        substitute(module("nu.shell.wldroid:proot")).using(project(":proot"))
        substitute(module("nu.shell.wldroid:virgl")).using(project(":virgl"))
        substitute(module("nu.shell.wldroid:shims")).using(project(":shims"))
    }
}
```

```kotlin
// app/build.gradle.kts
dependencies {
    implementation("nu.shell.wldroid:ui")
}
```

### Option 2: Individual Modules

Pick only what you need:

```kotlin
dependencies {
    // Core compositor only (no proot, no GPU management)
    implementation("nu.shell.wldroid:compositor")

    // Add environment management
    implementation("nu.shell.wldroid:proot")

    // Add GPU detection and VirGL server
    implementation("nu.shell.wldroid:virgl")

    // Add shim libraries for Linux graphics bridging
    implementation("nu.shell.wldroid:shims")
}
```

### Option 3: AAR Artifacts

If publishing AARs, consume them from a local Maven repository:

```kotlin
repositories {
    maven { url = uri("path/to/wldroid/build/repo") }
}

dependencies {
    implementation("nu.shell.wldroid:compositor:0.1.0")
    implementation("nu.shell.wldroid:proot:0.1.0")
    implementation("nu.shell.wldroid:virgl:0.1.0")
    implementation("nu.shell.wldroid:shims:0.1.0")
    implementation("nu.shell.wldroid:ui:0.1.0")
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
import nu.shell.wldroid.compositor.*

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
import nu.shell.wldroid.ui.CompositorSurface
import nu.shell.wldroid.compositor.*

@Composable
fun WaylandScreen() {
    var compositorState by remember { mutableStateOf(CompositorState.IDLE) }

    CompositorSurface(
        modifier = Modifier.fillMaxSize(),
        config = CompositorConfig(
            cacheDir = LocalContext.current.cacheDir.absolutePath,
            gpuMode = "AUTO",
            xwaylandEnabled = true,
        ),
        onStateChange = { compositorState = it },
        onClientCountChange = { /* update UI */ },
    )
}
```

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
-keep class nu.shell.wldroid.compositor.CompositorServer { *; }

# Keep all enum classes (used in StateFlow)
-keepclassmembers enum nu.shell.wldroid.** {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# Keep data classes used in DataStore serialization
-keep class nu.shell.wldroid.proot.RootfsEnvironment { *; }
-keep class nu.shell.wldroid.proot.RootfsStatus { *; }
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

## Lifecycle Management

### CompositorSession Lifecycle

The `CompositorSession` manages the native compositor lifecycle:

```
IDLE → STARTING → RUNNING → STOPPING → STOPPED
                     ↓
                   ERROR
```

- Call `start(surface)` when the Android Surface is ready
- Call `stop()` when the Surface is destroyed or the Activity is finishing
- Observe `state` to react to lifecycle changes
- The compositor thread is automatically cleaned up on `stop()`

### VirglSession Lifecycle

```
IDLE → DETECTING_GPU → STARTING → RUNNING → STOPPING → STOPPED
                                     ↓
                                  UNHEALTHY → (auto-restart)
                                     ↓
                                   ERROR
```

- `VirglSession` includes a health check that monitors the server process every 5 seconds
- If the server dies unexpectedly, state transitions to `UNHEALTHY`

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

If you manage your own SurfaceView instead of using `CompositorSurface`:

```kotlin
val input = session.input

// Forward touch events
surfaceView.setOnTouchListener { _, event ->
    input.sendTouchEvent(
        id = event.getPointerId(0),
        action = event.action,
        x = event.x,
        y = event.y,
        timestampMs = event.eventTime,
    )
    true
}

// Forward keyboard events
override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
    input.sendKeyEvent(keyCode, KeyEvent.ACTION_DOWN, event.eventTime)
    return true
}
```
