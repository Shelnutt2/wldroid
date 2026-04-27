package nu.shell.wldroid.compositor

import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

/**
 * Instrumented tests for [CompositorServer] and the native JNI bridge.
 *
 * The native library (libwldroid-compositor.so) is built for arm64 only,
 * so on x86_64 emulators the library load will fail with UnsatisfiedLinkError.
 * Tests are written to handle both cases gracefully.
 */
@RunWith(JUnit4::class)
class CompositorServerTest {

    @Test
    fun nativeLibraryLoadAttemptDoesNotCrash() {
        // CompositorServer companion init calls System.loadLibrary("wldroid-compositor").
        // On x86_64 emulators the arm64 .so isn't available, so we expect
        // UnsatisfiedLinkError — but the JVM must not crash.
        try {
            val server = CompositorServer()
            // If we get here, the native lib loaded (real ARM device).
            assertThat(server).isNotNull()
        } catch (e: UnsatisfiedLinkError) {
            // Expected on x86_64 emulators — the .so is arm64-only.
            assertThat(e.message).isNotEmpty()
        }
    }

    @Test
    fun compositorSessionDefaultState() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val config = CompositorConfig(
            cacheDir = context.cacheDir.absolutePath,
            testClientEnabled = true,
        )
        // CompositorSession creates a CompositorServer internally, which triggers
        // the native library load. Catch the expected failure on x86_64.
        try {
            val session = CompositorSession(config)
            assertThat(session.state.value).isEqualTo(CompositorState.IDLE)
            assertThat(session.clientCount.value).isEqualTo(0)
            assertThat(session.socketPath.value).isNull()
        } catch (e: UnsatisfiedLinkError) {
            // Expected on x86_64 — verify the config is still valid
            assertThat(config.cacheDir).isNotEmpty()
        }
    }

    @Test
    fun compositorConfigPreservesValues() {
        val config = CompositorConfig(
            cacheDir = "/data/test/cache",
            xkbBasePath = "/data/test/xkb",
            xwaylandEnabled = false,
            gpuMode = "SOFTWARE",
            testClientEnabled = true,
        )
        assertThat(config.cacheDir).isEqualTo("/data/test/cache")
        assertThat(config.xkbBasePath).isEqualTo("/data/test/xkb")
        assertThat(config.xwaylandEnabled).isFalse()
        assertThat(config.gpuMode).isEqualTo("SOFTWARE")
        assertThat(config.testClientEnabled).isTrue()
    }

    @Test
    fun compositorConfigDefaultFactory() {
        val config = CompositorConfig.default()
        assertThat(config.cacheDir).isEmpty()
        assertThat(config.xkbBasePath).isEmpty()
        assertThat(config.xwaylandEnabled).isTrue()
        assertThat(config.gpuMode).isEqualTo("AUTO")
        assertThat(config.testClientEnabled).isFalse()
    }

    @Test
    fun compositorConfigCopyModifiesCorrectly() {
        val original = CompositorConfig.default()
        val modified = original.copy(gpuMode = "TURNIP_DIRECT", xwaylandEnabled = false)
        assertThat(modified.gpuMode).isEqualTo("TURNIP_DIRECT")
        assertThat(modified.xwaylandEnabled).isFalse()
        // Unchanged fields preserved
        assertThat(modified.cacheDir).isEqualTo(original.cacheDir)
        assertThat(modified.testClientEnabled).isEqualTo(original.testClientEnabled)
    }

    @Test
    fun compositorStateEnumContainsAllExpectedValues() {
        val states = CompositorState.entries
        assertThat(states).containsExactly(
            CompositorState.IDLE,
            CompositorState.STARTING,
            CompositorState.RUNNING,
            CompositorState.STOPPING,
            CompositorState.STOPPED,
            CompositorState.ERROR,
        )
    }
}
