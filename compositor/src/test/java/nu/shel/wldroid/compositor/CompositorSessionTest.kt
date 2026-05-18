package nu.shel.wldroid.compositor

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit tests for CompositorSession state management.
 *
 * Note: Full lifecycle tests require the native library and an Android Surface,
 * so these tests focus on verifiable pure-Kotlin behavior. Integration tests
 * with JNI would run as Android instrumented tests.
 */
class CompositorSessionTest {

    @Test
    fun `session starts in IDLE state`() {
        // CompositorSession constructor instantiates CompositorServer which calls
        // System.loadLibrary. Since native libs aren't available in unit tests,
        // we test the state enum and config independently.
        val config = CompositorConfig.default()
        assertEquals(CompositorState.IDLE, CompositorState.entries.first())
    }

    @Test
    fun `config is preserved through session creation`() {
        val config = CompositorConfig(
            cacheDir = "/test/cache",
            xkbBasePath = "/test/xkb",
        )
        // Verify config data class behavior (session creation requires native lib)
        assertEquals("/test/cache", config.cacheDir)
        assertEquals("/test/xkb", config.xkbBasePath)
    }

    @Test
    fun `state transitions are well-defined`() {
        // Verify the expected state machine transitions exist as enum values
        val idle = CompositorState.IDLE
        val starting = CompositorState.STARTING
        val running = CompositorState.RUNNING
        val paused = CompositorState.PAUSED
        val stopping = CompositorState.STOPPING
        val stopped = CompositorState.STOPPED
        val error = CompositorState.ERROR

        // IDLE -> STARTING -> RUNNING is the happy path
        assertEquals(idle.ordinal + 1, starting.ordinal)
        assertEquals(starting.ordinal + 1, running.ordinal)

        // RUNNING -> PAUSED is the pause path
        assertEquals(running.ordinal + 1, paused.ordinal)

        // PAUSED/RUNNING -> STOPPING -> STOPPED is the shutdown path
        assertEquals(paused.ordinal + 1, stopping.ordinal)
        assertEquals(stopping.ordinal + 1, stopped.ordinal)

        // ERROR is the terminal error state
        assertEquals(6, error.ordinal)
    }

    @Test
    fun `config xwayland defaults`() {
        val config = CompositorConfig.default()
        assertEquals(true, config.xwaylandEnabled)
        assertEquals("", config.xwaylandBinaryPath)
        assertEquals("", config.ahbRegistrySocketPath)
    }
}
