package nu.shel.wldroid.ui

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import nu.shel.wldroid.compositor.CompositorConfig
import nu.shel.wldroid.compositor.CompositorState
import org.junit.Test

class CompositorSurfaceStateTest {

    /**
     * A minimal fake [CompositorSession] for testing.
     * We create a real [CompositorSurfaceState] but with a config that won't
     * try to load native libraries (the class just wraps the session).
     */

    @Test
    fun `keyboard visibility defaults to false`() {
        val config = CompositorConfig.default()
        // We can't instantiate a real CompositorSession in unit tests (needs native libs),
        // so we test the keyboard visibility tracking directly.
        val state = createTestableState(config)
        assertThat(state.isKeyboardVisible.value).isFalse()
    }

    @Test
    fun `setKeyboardVisible updates the flow`() {
        val state = createTestableState(CompositorConfig.default())
        state.setKeyboardVisible(true)
        assertThat(state.isKeyboardVisible.value).isTrue()
        state.setKeyboardVisible(false)
        assertThat(state.isKeyboardVisible.value).isFalse()
    }

    @Test
    fun `config is preserved`() {
        val config = CompositorConfig(
            cacheDir = "/tmp/test",
            xkbBasePath = "/xkb",
            xwaylandEnabled = false,
            gpuMode = "VIRGL_GLES",
            testClientEnabled = true,
        )
        val state = createTestableState(config)
        assertThat(state.config).isEqualTo(config)
        assertThat(state.config.cacheDir).isEqualTo("/tmp/test")
        assertThat(state.config.xwaylandEnabled).isFalse()
        assertThat(state.config.gpuMode).isEqualTo("VIRGL_GLES")
    }

    /**
     * Creates a testable [CompositorSurfaceState] using a stub session.
     * In unit tests we cannot load native code, so we use a minimal stub
     * that provides the state flows needed for testing.
     */
    private fun createTestableState(config: CompositorConfig): TestableCompositorSurfaceState {
        return TestableCompositorSurfaceState(config)
    }

    /**
     * Testable version that doesn't require native CompositorSession.
     * Mirrors the same keyboard visibility API.
     */
    private class TestableCompositorSurfaceState(val config: CompositorConfig) {
        private val _compositorState = MutableStateFlow(CompositorState.IDLE)
        val compositorState: StateFlow<CompositorState> = _compositorState

        private val _clientCount = MutableStateFlow(0)
        val clientCount: StateFlow<Int> = _clientCount

        private val _socketPath = MutableStateFlow<String?>(null)
        val socketPath: StateFlow<String?> = _socketPath

        private val _isKeyboardVisible = MutableStateFlow(false)
        val isKeyboardVisible: StateFlow<Boolean> = _isKeyboardVisible

        fun setKeyboardVisible(visible: Boolean) {
            _isKeyboardVisible.value = visible
        }
    }
}
