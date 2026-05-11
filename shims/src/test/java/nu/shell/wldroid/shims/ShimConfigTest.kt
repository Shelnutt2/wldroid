package nu.shell.wldroid.shims

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ShimConfigTest {

    @Test
    fun `default config enables all shims`() {
        val config = ShimConfig()
        assertThat(config.enableDrmShim).isTrue()
        assertThat(config.enableDrmWrapper).isTrue()
        assertThat(config.enableGbmShim).isTrue()
        assertThat(config.enableEglOverride).isTrue()
        assertThat(config.enableNetstub).isTrue()
    }

    @Test
    fun `SOFTWARE mode disables drm gbm and egl`() {
        val config = ShimConfig.forGpuMode("SOFTWARE")
        assertThat(config.enableDrmShim).isFalse()
        assertThat(config.enableDrmWrapper).isFalse()
        assertThat(config.enableGbmShim).isFalse()
        assertThat(config.enableEglOverride).isFalse()
        assertThat(config.enableNetstub).isTrue()
    }

    @Test
    fun `TURNIP_DIRECT mode disables egl only`() {
        val config = ShimConfig.forGpuMode("TURNIP_DIRECT")
        assertThat(config.enableDrmShim).isTrue()
        assertThat(config.enableDrmWrapper).isTrue()
        assertThat(config.enableGbmShim).isTrue()
        assertThat(config.enableEglOverride).isFalse()
        assertThat(config.enableNetstub).isTrue()
    }

    @Test
    fun `VIRGL mode enables all shims`() {
        val config = ShimConfig.forGpuMode("VIRGL")
        assertThat(config.enableDrmShim).isTrue()
        assertThat(config.enableDrmWrapper).isTrue()
        assertThat(config.enableGbmShim).isTrue()
        assertThat(config.enableEglOverride).isTrue()
        assertThat(config.enableNetstub).isTrue()
    }

    @Test
    fun `unknown GPU mode enables all shims`() {
        val config = ShimConfig.forGpuMode("SOME_FUTURE_MODE")
        assertThat(config.enableDrmShim).isTrue()
        assertThat(config.enableEglOverride).isTrue()
    }

    @Test
    fun `data class equality works`() {
        val a = ShimConfig(enableGbmShim = false)
        val b = ShimConfig(enableGbmShim = false)
        assertThat(a).isEqualTo(b)
    }

    @Test
    fun `data class copy works`() {
        val original = ShimConfig()
        val modified = original.copy(enableNetstub = false)
        assertThat(modified.enableNetstub).isFalse()
        assertThat(modified.enableDrmShim).isTrue()
    }
}
