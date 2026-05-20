package nu.shel.wldroid.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
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
    private val _viewport = MutableStateFlow(ViewportTransform())

    /** Host-side viewport transform. This does not alter the Wayland output size. */
    val viewport: StateFlow<ViewportTransform>
        get() = _viewport.asStateFlow()

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

    /** Configure viewport scale limits. */
    fun setViewportScaleBounds(minScale: Float, maxScale: Float) {
        updateViewport { it.withScaleBounds(minScale, maxScale) }
    }

    /** Reset host zoom/pan without changing the Wayland output size. */
    fun resetZoom() {
        updateViewport { it.reset() }
    }

    /** Zoom the host viewport around a focal point in Android view pixels. */
    fun zoomBy(factor: Float, focalX: Float, focalY: Float) {
        updateViewport { it.zoomBy(factor, focalX, focalY) }
    }

    /** Pan the host viewport by Android view pixels. */
    fun panBy(dx: Float, dy: Float) {
        updateViewport { it.panBy(dx, dy) }
    }

    /** Map an Android view coordinate to the fixed Wayland guest/output coordinate space. */
    fun mapViewToGuest(x: Float, y: Float): GuestPoint = _viewport.value.mapViewToGuest(x, y)

    internal fun setViewportViewSize(width: Int, height: Int) {
        updateViewport { it.withViewSize(width, height) }
    }

    internal fun setViewportContentSize(width: Int, height: Int) {
        updateViewport { it.withContentSize(width, height) }
    }

    internal fun setImeBottomInset(heightPx: Int) {
        updateViewport { it.withImeBottomInset(heightPx) }
    }

    private fun updateViewport(transform: (ViewportTransform) -> ViewportTransform) {
        _viewport.update(transform)
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
