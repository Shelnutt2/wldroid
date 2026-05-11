package nu.shell.wldroid.shims

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

/**
 * Instrumented tests for [ShimConfig] — verifies shim selection per GPU mode.
 */
@RunWith(JUnit4::class)
class ShimConfigTest {

    @Test
    fun defaultConfigEnablesAllShims() {
        val config = ShimConfig()
        assertThat(config.enableDrmShim).isTrue()
        assertThat(config.enableGbmShim).isTrue()
        assertThat(config.enableEglOverride).isTrue()
        assertThat(config.enableNetstub).isTrue()
        assertThat(config.enableDrmWrapper).isTrue()
    }

    @Test
    fun softwareModeDisablesGbmAndEgl() {
        val config = ShimConfig.forGpuMode("SOFTWARE")
        assertThat(config.enableDrmShim).isFalse()
        assertThat(config.enableGbmShim).isFalse()
        assertThat(config.enableEglOverride).isFalse()
        assertThat(config.enableNetstub).isTrue()
        assertThat(config.enableDrmWrapper).isFalse()
    }

    @Test
    fun turnipDirectDisablesEglOverride() {
        val config = ShimConfig.forGpuMode("TURNIP_DIRECT")
        assertThat(config.enableDrmShim).isTrue()
        assertThat(config.enableGbmShim).isTrue()
        assertThat(config.enableEglOverride).isFalse()
        assertThat(config.enableNetstub).isTrue()
        assertThat(config.enableDrmWrapper).isTrue()
    }

    @Test
    fun virglModesEnableAllShims() {
        for (mode in listOf("VIRGL_GLES", "VIRGL_ZINK", "VENUS")) {
            val config = ShimConfig.forGpuMode(mode)
            assertThat(config.enableDrmShim).isTrue()
            assertThat(config.enableGbmShim).isTrue()
            assertThat(config.enableEglOverride).isTrue()
            assertThat(config.enableNetstub).isTrue()
            assertThat(config.enableDrmWrapper).isTrue()
        }
    }

    @Test
    fun unknownGpuModeDefaultsToAllShims() {
        val config = ShimConfig.forGpuMode("UNKNOWN_MODE")
        assertThat(config.enableDrmShim).isTrue()
        assertThat(config.enableGbmShim).isTrue()
        assertThat(config.enableEglOverride).isTrue()
    }

    @Test
    fun shimConfigCopyWorks() {
        val original = ShimConfig()
        val modified = original.copy(enableNetstub = false, enableDrmWrapper = false)
        assertThat(modified.enableNetstub).isFalse()
        assertThat(modified.enableDrmWrapper).isFalse()
        // Others preserved
        assertThat(modified.enableDrmShim).isTrue()
        assertThat(modified.enableGbmShim).isTrue()
        assertThat(modified.enableEglOverride).isTrue()
    }
}
