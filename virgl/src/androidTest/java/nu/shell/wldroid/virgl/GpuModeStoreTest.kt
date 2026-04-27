package nu.shell.wldroid.virgl

import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

/**
 * Instrumented tests for [GpuModeStore] — real DataStore persistence
 * on an Android device/emulator.
 */
@RunWith(JUnit4::class)
class GpuModeStoreTest {

    @Test
    fun storeAndRetrieveGpuMode() = runTest {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = GpuModeStore(context)

        // Set a specific mode
        store.setGpuModeOverride(GpuMode.SOFTWARE)
        val stored = store.getGpuModeOverride().first()
        assertThat(stored).isEqualTo(GpuMode.SOFTWARE)

        // Change to a different mode
        store.setGpuModeOverride(GpuMode.VIRGL_ZINK)
        val updated = store.getGpuModeOverride().first()
        assertThat(updated).isEqualTo(GpuMode.VIRGL_ZINK)

        // Revert to auto (null)
        store.setGpuModeOverride(null)
        val auto = store.getGpuModeOverride().first()
        assertThat(auto).isNull()
    }

    @Test
    fun storeNullMeansAutoDetect() = runTest {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = GpuModeStore(context)

        store.setGpuModeOverride(null)
        val mode = store.getGpuModeOverride().first()
        assertThat(mode).isNull()
    }

    @Test
    fun storeAllGpuModes() = runTest {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = GpuModeStore(context)

        // Verify every GpuMode can be stored and retrieved
        for (gpuMode in GpuMode.entries.filter { it != GpuMode.AUTO }) {
            store.setGpuModeOverride(gpuMode)
            val retrieved = store.getGpuModeOverride().first()
            assertThat(retrieved).isEqualTo(gpuMode)
        }

        // Clean up
        store.setGpuModeOverride(null)
    }

    @Test
    fun gpuModeFromStringParsesCorrectly() {
        assertThat(GpuMode.fromString("SOFTWARE")).isEqualTo(GpuMode.SOFTWARE)
        assertThat(GpuMode.fromString("VIRGL_GLES")).isEqualTo(GpuMode.VIRGL_GLES)
        assertThat(GpuMode.fromString("VIRGL_ZINK")).isEqualTo(GpuMode.VIRGL_ZINK)
        assertThat(GpuMode.fromString("VENUS")).isEqualTo(GpuMode.VENUS)
        assertThat(GpuMode.fromString("TURNIP_DIRECT")).isEqualTo(GpuMode.TURNIP_DIRECT)
        assertThat(GpuMode.fromString("AUTO")).isEqualTo(GpuMode.AUTO)
        assertThat(GpuMode.fromString("UNKNOWN")).isEqualTo(GpuMode.AUTO) // fallback
        assertThat(GpuMode.fromString("")).isEqualTo(GpuMode.AUTO)
    }

    @Test
    fun virglConfigPreservesValues() {
        val config = VirglConfig(
            virglBinaryPath = "/data/local/tmp/virgl_test_server",
            socketPath = "/tmp/vtest",
            gpuMode = GpuMode.VIRGL_ZINK,
            venusEnabled = true,
            useZinkBackend = true,
        )
        assertThat(config.virglBinaryPath).isEqualTo("/data/local/tmp/virgl_test_server")
        assertThat(config.socketPath).isEqualTo("/tmp/vtest")
        assertThat(config.gpuMode).isEqualTo(GpuMode.VIRGL_ZINK)
        assertThat(config.venusEnabled).isTrue()
        assertThat(config.useZinkBackend).isTrue()
    }

    @Test
    fun virglStateEnumContainsAllValues() {
        val states = VirglState.entries
        assertThat(states).hasSize(7)
        assertThat(states.map { it.name }).containsExactly(
            "IDLE", "DETECTING_GPU", "STARTING", "RUNNING",
            "UNHEALTHY", "STOPPING", "STOPPED",
        )
    }
}
