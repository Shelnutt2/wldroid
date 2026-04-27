package nu.shell.wldroid.testapp

import com.google.common.truth.Truth.assertThat
import nu.shell.wldroid.shims.ShimConfig
import org.junit.Test

class ShimConfigTest {

    @Test
    fun `software mode configures appropriate shims`() {
        val config = ShimConfig.forGpuMode("SOFTWARE")
        // Software mode should have DRM and netstub at minimum
        assertThat(config.enableDrmShim).isTrue()
        assertThat(config.enableNetstub).isTrue()
    }

    @Test
    fun `default shim config enables all shims`() {
        val config = ShimConfig()
        assertThat(config.enableDrmShim).isTrue()
        assertThat(config.enableGbmShim).isTrue()
        assertThat(config.enableEglOverride).isTrue()
        assertThat(config.enableNetstub).isTrue()
        assertThat(config.enableDrmWrapper).isTrue()
    }
}
