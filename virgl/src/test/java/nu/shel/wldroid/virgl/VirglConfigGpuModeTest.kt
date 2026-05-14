package nu.shel.wldroid.virgl

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Extended tests for [VirglConfig] behavior across all GPU modes.
 *
 * Complements [VirglConfigTest] which covers basic data class operations.
 * These tests verify config correctness for each rendering pipeline.
 */
class VirglConfigGpuModeTest {

    @Test
    fun `SOFTWARE mode does not require VirGL server`() {
        val config = VirglConfig(gpuMode = GpuMode.SOFTWARE)
        assertThat(config.gpuMode.requiresVirglServer).isFalse()
    }

    @Test
    fun `VIRGL_GLES mode requires VirGL server`() {
        val config = VirglConfig(gpuMode = GpuMode.VIRGL_GLES)
        assertThat(config.gpuMode.requiresVirglServer).isTrue()
    }

    @Test
    fun `VIRGL_ZINK mode requires VirGL server`() {
        val config = VirglConfig(gpuMode = GpuMode.VIRGL_ZINK)
        assertThat(config.gpuMode.requiresVirglServer).isTrue()
    }

    @Test
    fun `VENUS mode requires VirGL server`() {
        val config = VirglConfig(gpuMode = GpuMode.VENUS)
        assertThat(config.gpuMode.requiresVirglServer).isTrue()
    }

    @Test
    fun `TURNIP_DIRECT mode does not require VirGL server`() {
        val config = VirglConfig(gpuMode = GpuMode.TURNIP_DIRECT)
        assertThat(config.gpuMode.requiresVirglServer).isFalse()
    }

    @Test
    fun `AUTO mode does not require VirGL server by default`() {
        val config = VirglConfig(gpuMode = GpuMode.AUTO)
        assertThat(config.gpuMode.requiresVirglServer).isFalse()
    }

    @Test
    fun `config for VIRGL_ZINK should enable zink backend`() {
        val config = VirglConfig(
            gpuMode = GpuMode.VIRGL_ZINK,
            useZinkBackend = true,
        )
        assertThat(config.useZinkBackend).isTrue()
        assertThat(config.gpuMode).isEqualTo(GpuMode.VIRGL_ZINK)
    }

    @Test
    fun `config for VENUS should enable venus`() {
        val config = VirglConfig(
            gpuMode = GpuMode.VENUS,
            venusEnabled = true,
        )
        assertThat(config.venusEnabled).isTrue()
        assertThat(config.gpuMode).isEqualTo(GpuMode.VENUS)
    }

    @Test
    fun `config for each mode preserves socket and binary paths`() {
        for (mode in GpuMode.entries) {
            val config = VirglConfig(
                virglBinaryPath = "/opt/virgl",
                socketPath = "/tmp/.virgl-$mode",
                gpuMode = mode,
            )
            assertThat(config.virglBinaryPath).isEqualTo("/opt/virgl")
            assertThat(config.socketPath).isEqualTo("/tmp/.virgl-$mode")
            assertThat(config.gpuMode).isEqualTo(mode)
        }
    }

    @Test
    fun `GpuMode fromString parses all valid mode names`() {
        for (mode in GpuMode.entries) {
            assertThat(GpuMode.fromString(mode.name)).isEqualTo(mode)
        }
    }

    @Test
    fun `GpuMode fromString returns AUTO for unknown mode`() {
        assertThat(GpuMode.fromString("UNKNOWN_MODE")).isEqualTo(GpuMode.AUTO)
        assertThat(GpuMode.fromString("")).isEqualTo(GpuMode.AUTO)
    }

    @Test
    fun `all GPU modes have non-empty display names and descriptions`() {
        for (mode in GpuMode.entries) {
            assertThat(mode.displayName).isNotEmpty()
            assertThat(mode.description).isNotEmpty()
        }
    }
}
