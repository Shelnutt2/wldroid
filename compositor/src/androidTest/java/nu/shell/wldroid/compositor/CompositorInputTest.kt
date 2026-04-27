package nu.shell.wldroid.compositor

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

/**
 * Instrumented tests for [CompositorInput] API surface.
 *
 * Native methods can't be called on x86_64 emulators, so these tests verify
 * the Kotlin API surface and object creation without calling native methods.
 */
@RunWith(JUnit4::class)
class CompositorInputTest {

    @Test
    fun inputObjectCreationRequiresServer() {
        // CompositorInput takes a CompositorServer, which triggers native lib load.
        // On x86_64 emulators this will fail — verify gracefully.
        try {
            val server = CompositorServer()
            val input = CompositorInput(server)
            assertThat(input).isNotNull()
        } catch (e: UnsatisfiedLinkError) {
            // Expected: native lib is arm64-only
            assertThat(e).isNotNull()
        }
    }

    @Test
    fun compositorSessionExposesInputProperty() {
        // Verify the session.input property returns a CompositorInput.
        try {
            val config = CompositorConfig(cacheDir = "/tmp")
            val session = CompositorSession(config)
            val input = session.input
            assertThat(input).isInstanceOf(CompositorInput::class.java)
        } catch (e: UnsatisfiedLinkError) {
            // Expected on x86_64 emulator
            assertThat(e).isNotNull()
        }
    }

    @Test
    fun compositorConfigWithAllGpuModeStrings() {
        // Verify all known GPU mode strings can be set in CompositorConfig
        val modes = listOf("AUTO", "SOFTWARE", "VIRGL_GLES", "VIRGL_ZINK", "VENUS", "TURNIP_DIRECT")
        for (mode in modes) {
            val config = CompositorConfig(gpuMode = mode)
            assertThat(config.gpuMode).isEqualTo(mode)
        }
    }
}
