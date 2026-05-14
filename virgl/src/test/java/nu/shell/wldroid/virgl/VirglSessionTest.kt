package nu.shell.wldroid.virgl

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test

class VirglSessionTest {

    @Test
    fun `initial state is IDLE`() {
        val session = VirglSession(VirglConfig())
        assertThat(session.state.value).isEqualTo(VirglState.IDLE)
    }

    @Test
    fun `initial detectedGpuMode matches config`() {
        val session = VirglSession(VirglConfig(gpuMode = GpuMode.VIRGL_ZINK))
        assertThat(session.detectedGpuMode.value).isEqualTo(GpuMode.VIRGL_ZINK)
    }

    @Test
    fun `software mode transitions to RUNNING without server`() = runTest {
        val config = VirglConfig(gpuMode = GpuMode.SOFTWARE)
        val session = VirglSession(config)
        session.start()
        assertThat(session.state.value).isEqualTo(VirglState.RUNNING)
        assertThat(session.detectedGpuMode.value).isEqualTo(GpuMode.SOFTWARE)
    }

    @Test
    fun `turnip direct mode transitions to RUNNING without server`() = runTest {
        val config = VirglConfig(gpuMode = GpuMode.TURNIP_DIRECT)
        val session = VirglSession(config)
        session.start()
        assertThat(session.state.value).isEqualTo(VirglState.RUNNING)
    }

    @Test
    fun `isHealthy returns false when IDLE`() {
        val session = VirglSession(VirglConfig())
        assertThat(session.isHealthy()).isFalse()
    }

    @Test
    fun `isHealthy returns true for running software mode`() = runTest {
        val config = VirglConfig(gpuMode = GpuMode.SOFTWARE)
        val session = VirglSession(config)
        session.start()
        assertThat(session.isHealthy()).isTrue()
    }

    @Test
    fun `stop transitions through STOPPING to STOPPED`() = runTest {
        val config = VirglConfig(gpuMode = GpuMode.SOFTWARE)
        val session = VirglSession(config)
        session.start()
        session.stop()
        assertThat(session.state.value).isEqualTo(VirglState.STOPPED)
    }

    @Test
    fun `virgl mode with missing binary transitions to ERROR`() = runTest {
        val config = VirglConfig(
            gpuMode = GpuMode.VIRGL_GLES,
            virglBinaryPath = "/nonexistent/virgl_test_server",
            socketPath = "/tmp/test/.virgl_test",
        )
        val session = VirglSession(config)
        session.start()
        assertThat(session.state.value).isEqualTo(VirglState.ERROR)
    }

    @Test
    fun `VirglState has expected entries`() {
        assertThat(VirglState.entries).containsExactly(
            VirglState.IDLE,
            VirglState.DETECTING_GPU,
            VirglState.STARTING,
            VirglState.RUNNING,
            VirglState.UNHEALTHY,
            VirglState.STOPPING,
            VirglState.STOPPED,
            VirglState.ERROR,
        )
    }

    @Test
    fun `restart cycle - start stop start works for software mode`() = runTest {
        val config = VirglConfig(gpuMode = GpuMode.SOFTWARE)
        val session = VirglSession(config)

        // First cycle
        session.start()
        assertThat(session.state.value).isEqualTo(VirglState.RUNNING)
        session.stop()
        assertThat(session.state.value).isEqualTo(VirglState.STOPPED)

        // Second cycle
        session.start()
        assertThat(session.state.value).isEqualTo(VirglState.RUNNING)
        assertThat(session.isHealthy()).isTrue()
        session.stop()
        assertThat(session.state.value).isEqualTo(VirglState.STOPPED)
    }

    @Test
    fun `double start is no-op when already running`() = runTest {
        val config = VirglConfig(gpuMode = GpuMode.SOFTWARE)
        val session = VirglSession(config)
        session.start()
        assertThat(session.state.value).isEqualTo(VirglState.RUNNING)

        // Second start should be a no-op
        session.start()
        assertThat(session.state.value).isEqualTo(VirglState.RUNNING)
    }

    @Test
    fun `double stop is no-op when already stopped`() = runTest {
        val config = VirglConfig(gpuMode = GpuMode.SOFTWARE)
        val session = VirglSession(config)
        session.start()
        session.stop()
        assertThat(session.state.value).isEqualTo(VirglState.STOPPED)

        // Second stop should be a no-op (no crash)
        session.stop()
        assertThat(session.state.value).isEqualTo(VirglState.STOPPED)
    }

    @Test
    fun `stop when idle is no-op`() = runTest {
        val session = VirglSession(VirglConfig())
        session.stop()
        assertThat(session.state.value).isEqualTo(VirglState.IDLE)
    }
}
