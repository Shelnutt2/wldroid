package nu.shell.wldroid.testapp

import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import nu.shell.wldroid.compositor.CompositorConfig
import nu.shell.wldroid.compositor.CompositorSession
import nu.shell.wldroid.compositor.CompositorState
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

/**
 * End-to-end test for compositor startup.
 *
 * Note: Full rendering tests require a real device with GPU access.
 * These tests verify the session lifecycle and state transitions.
 * Tests that require the native compositor library are skipped when
 * it is unavailable (e.g. on x86_64 CI emulators built with -PskipCompositor).
 */
@RunWith(JUnit4::class)
class CompositorE2ETest {

    companion object {
        /** True when the native compositor .so is present and loadable. */
        val nativeLibAvailable: Boolean by lazy {
            try {
                System.loadLibrary("wldroid-compositor")
                true
            } catch (_: UnsatisfiedLinkError) {
                false
            }
        }
    }

    private fun assumeNativeLibAvailable() {
        assumeTrue(
            "Native compositor library not available — skipping",
            nativeLibAvailable,
        )
    }

    @Test
    fun compositorSessionStartsInIdleState() {
        assumeNativeLibAvailable()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val config = CompositorConfig(
            cacheDir = context.cacheDir.absolutePath,
            testClientEnabled = true,
        )
        val session = CompositorSession(config)
        assertThat(session.state.value).isEqualTo(CompositorState.IDLE)
    }

    @Test
    fun compositorSessionInitialClientCountIsZero() {
        assumeNativeLibAvailable()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val config = CompositorConfig(
            cacheDir = context.cacheDir.absolutePath,
        )
        val session = CompositorSession(config)
        assertThat(session.clientCount.value).isEqualTo(0)
    }

    @Test
    fun compositorSessionInitialSocketPathIsNull() {
        assumeNativeLibAvailable()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val config = CompositorConfig(
            cacheDir = context.cacheDir.absolutePath,
        )
        val session = CompositorSession(config)
        assertThat(session.socketPath.value).isNull()
    }

    @Test
    fun compositorConfigDefaultValues() {
        val config = CompositorConfig.default()
        assertThat(config.cacheDir).isEmpty()
        assertThat(config.xwaylandEnabled).isTrue()
        assertThat(config.gpuMode).isEqualTo("AUTO")
        assertThat(config.testClientEnabled).isFalse()
    }
}
