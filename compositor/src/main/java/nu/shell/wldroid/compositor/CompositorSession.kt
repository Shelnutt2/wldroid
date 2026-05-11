package nu.shell.wldroid.compositor

import android.system.Os
import android.view.Surface
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

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

    fun start(surface: Surface) {
        _state.value = CompositorState.STARTING
        try {
            // Set AHB_REGISTRY_SOCKET env var so the native compositor can open the
            // Unix socket for AHardwareBuffer handle sharing with the VirGL server.
            if (config.ahbRegistrySocketPath.isNotEmpty()) {
                Os.setenv("AHB_REGISTRY_SOCKET", config.ahbRegistrySocketPath, true)
            }

            // Create a dedicated wayland-runtime subdirectory and clean stale sockets.
            val runtimeDir = File(config.cacheDir, "wayland-runtime")
            runtimeDir.mkdirs()
            cleanStaleWaylandFiles(runtimeDir)

            server.nativeStartCompositor(surface, runtimeDir.absolutePath, config.xkbBasePath)

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

    fun stop() {
        _state.value = CompositorState.STOPPING
        try {
            server.nativeStopCompositor()
        } finally {
            _state.value = CompositorState.STOPPED
        }
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
