package nu.shel.wldroid.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import nu.shel.wldroid.compositor.CompositorConfig
import nu.shel.wldroid.compositor.CompositorSession
import nu.shel.wldroid.compositor.CompositorState

/**
 * Holds the compositor session and its observable state flows.
 * Create via [rememberCompositorSurfaceState] inside a Composable scope.
 */
class CompositorSurfaceState(
    val session: CompositorSession,
    val config: CompositorConfig,
) {
    /** Current lifecycle state of the compositor. */
    val compositorState: StateFlow<CompositorState>
        get() = session.state

    /** Number of connected Wayland clients. */
    val clientCount: StateFlow<Int>
        get() = session.clientCount

    /** Unix socket path for the compositor, available once RUNNING. */
    val socketPath: StateFlow<String?>
        get() = session.socketPath

    private val _isKeyboardVisible = MutableStateFlow(false)

    /** Whether the software keyboard is currently shown. */
    val isKeyboardVisible: StateFlow<Boolean>
        get() = _isKeyboardVisible.asStateFlow()

    /** Update the keyboard visibility tracking. */
    internal fun setKeyboardVisible(visible: Boolean) {
        _isKeyboardVisible.value = visible
    }
}

/**
 * Remember a [CompositorSurfaceState] scoped to the current composition.
 * The [CompositorSession] is created once and reused across recompositions.
 */
@Composable
fun rememberCompositorSurfaceState(
    config: CompositorConfig = CompositorConfig.default(),
): CompositorSurfaceState {
    return remember(config) {
        val session = CompositorSession(config)
        CompositorSurfaceState(session, config)
    }
}
