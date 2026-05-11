package nu.shell.wldroid.testapp

import com.google.common.truth.Truth.assertThat
import nu.shell.wldroid.shims.ShimConfig
import org.junit.Test

class ShimConfigTest {

    @Test
    fun `software mode configures appropriate shims`() {
        val config = ShimConfig.forGpuMode("SOFTWARE")
        // Software mode disables GPU shims, keeps only netstub
        assertThat(config.enableDrmShim).isFalse()
        assertThat(config.enableDrmWrapper).isFalse()
        assertThat(config.enableGbmShim).isFalse()
        assertThat(config.enableEglOverride).isFalse()
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
