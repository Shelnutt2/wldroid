package nu.shell.wldroid.compositor

import android.system.Os
import android.view.Surface
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * High-level compositor session manager with observable state.
 */
class CompositorSession(private val config: CompositorConfig) {
    private val server = CompositorServer()

    private val _state = MutableStateFlow(CompositorState.IDLE)
    val state: StateFlow<CompositorState> = _state.asStateFlow()

    private val _clientCount = MutableStateFlow(0)
    val clientCount: StateFlow<Int> = _clientCount.asStateFlow()

    private val _socketPath = MutableStateFlow<String?>(null)
    val socketPath: StateFlow<String?> = _socketPath.asStateFlow()

    /** Guard to prevent double-stop (matches original CompositorActivity pattern). */
    private val stopRequested = AtomicBoolean(false)

    /** Cached runtime directory so cleanupOnStop() can remove stale sockets. */
    private var runtimeDir: File? = null

    fun start(surface: Surface) {
        val current = _state.value
        if (current == CompositorState.STARTING || current == CompositorState.RUNNING) {
            throw IllegalStateException(
                "Cannot start compositor while in state $current"
            )
        }

        stopRequested.set(false)
        _state.value = CompositorState.STARTING
        try {
            // Set AHB_REGISTRY_SOCKET env var so the native compositor can open the
            // Unix socket for AHardwareBuffer handle sharing with the VirGL server.
            if (config.ahbRegistrySocketPath.isNotEmpty()) {
                Os.setenv("AHB_REGISTRY_SOCKET", config.ahbRegistrySocketPath, true)
            }

            // Set WLR_XWAYLAND so wlroots can find the Xwayland binary (or wrapper
            // script). Must be set before nativeStartCompositor() which calls
            // wlr_xwayland_create() during server_init().
            if (config.xwaylandEnabled && config.xwaylandBinaryPath.isNotEmpty()) {
                Os.setenv("WLR_XWAYLAND", config.xwaylandBinaryPath, true)
            }

            // Create a dedicated wayland-runtime subdirectory and clean stale sockets.
            val dir = File(config.cacheDir, "wayland-runtime")
            dir.mkdirs()
            cleanStaleWaylandFiles(dir)
            runtimeDir = dir

            server.nativeStartCompositor(surface, dir.absolutePath, config.xkbBasePath)

            val socketName = server.nativeGetSocketName()
            if (socketName == null) {
                _state.value = CompositorState.ERROR
                throw IllegalStateException(
                    "Compositor started but nativeGetSocketName() returned null — " +
                        "the compositor may have failed internally"
                )
            }

            _state.value = CompositorState.RUNNING
            _socketPath.value = socketName
        } catch (e: Exception) {
            if (_state.value != CompositorState.ERROR) {
                _state.value = CompositorState.ERROR
            }
            throw e
        }
    }

    /**
     * Remove stale `wayland-*` socket and lock files left over from previous
     * crashes to avoid "address already in use" errors.
     */
    private fun cleanStaleWaylandFiles(dir: File) {
        dir.listFiles()?.filter { it.name.startsWith("wayland-") }?.forEach { it.delete() }
    }

    /** Reset observable state and clean stale socket files after the compositor stops. */
    private fun cleanupOnStop() {
        _socketPath.value = null
        runtimeDir?.let { cleanStaleWaylandFiles(it) }
    }

    /**
     * Stop the compositor synchronously (blocks until the event-loop thread
     * exits and resources are destroyed).  Safe to call multiple times —
     * subsequent calls are no-ops.
     */
    fun stop() {
        if (!stopRequested.compareAndSet(false, true)) return
        _state.value = CompositorState.STOPPING
        try {
            server.nativeStopCompositor()
        } finally {
            cleanupOnStop()
            _state.value = CompositorState.STOPPED
        }
    }

    /**
     * Request compositor shutdown on a background thread so the caller
     * (typically the `surfaceDestroyed` callback) is not blocked.
     *
     * This avoids a SIGSEGV on Mali GPUs where destroying the EGL context
     * inside the `surfaceDestroyed` stack frame causes a NULL-pointer
     * dereference in the driver — the native window is still being torn
     * down on the UI thread at that point.  Running the shutdown on a
     * separate thread lets Android finish the surface teardown first.
     */
    fun stopAsync() {
        if (!stopRequested.compareAndSet(false, true)) return
        _state.value = CompositorState.STOPPING
        Thread {
            try {
                server.nativeStopCompositor()
            } finally {
                cleanupOnStop()
                _state.value = CompositorState.STOPPED
            }
        }.start()
    }

    fun refreshClientCount() {
        _clientCount.value = server.nativeGetClientCount()
    }

    fun resizeOutput(width: Int, height: Int) {
        server.nativeResizeOutput(width, height)
    }

    fun startTestClient() {
        server.nativeStartTestClient()
    }

    // Input forwarding (delegates to server)
    val input: CompositorInput = CompositorInput(server)
}
