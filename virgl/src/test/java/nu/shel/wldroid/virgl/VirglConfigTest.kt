package nu.shel.wldroid.virgl

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class VirglConfigTest {

    @Test
    fun `default config has expected values`() {
        val config = VirglConfig()
        assertThat(config.virglBinaryPath).isEmpty()
        assertThat(config.socketPath).isEmpty()
        assertThat(config.gpuMode).isEqualTo(GpuMode.AUTO)
        assertThat(config.venusEnabled).isFalse()
        assertThat(config.useZinkBackend).isFalse()
    }

    @Test
    fun `config with custom values`() {
        val config = VirglConfig(
            virglBinaryPath = "/path/to/virgl",
            socketPath = "/tmp/.virgl_test",
            gpuMode = GpuMode.VIRGL_ZINK,
            venusEnabled = true,
            useZinkBackend = true,
        )
        assertThat(config.virglBinaryPath).isEqualTo("/path/to/virgl")
        assertThat(config.socketPath).isEqualTo("/tmp/.virgl_test")
        assertThat(config.gpuMode).isEqualTo(GpuMode.VIRGL_ZINK)
        assertThat(config.venusEnabled).isTrue()
        assertThat(config.useZinkBackend).isTrue()
    }

    @Test
    fun `copy with modified gpu mode`() {
        val original = VirglConfig(gpuMode = GpuMode.SOFTWARE)
        val modified = original.copy(gpuMode = GpuMode.VENUS)
        assertThat(modified.gpuMode).isEqualTo(GpuMode.VENUS)
        assertThat(original.gpuMode).isEqualTo(GpuMode.SOFTWARE)
    }
}
