package nu.shell.wldroid.compositor

import android.view.Surface
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

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
            server.nativeStartCompositor(surface, config.cacheDir, config.xkbBasePath)
            _state.value = CompositorState.RUNNING
            _socketPath.value = server.nativeGetSocketName()
        } catch (e: Exception) {
            _state.value = CompositorState.ERROR
            throw e
        }
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

    // Input forwarding (delegates to server)
    val input: CompositorInput get() = CompositorInput(server)
}
