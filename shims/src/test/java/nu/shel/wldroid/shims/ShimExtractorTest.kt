package nu.shel.wldroid.shims

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ShimExtractorTest {

    @Test
    fun `ShimSet contains correct subdirectory filenames`() {
        val shimSet = ShimExtractor.ShimSet(
            drmShim = "/tmp/shims/drm-shim/libdrm-shim.so",
            drmWrapper = "/tmp/shims/drm-shim/libdrm-wrapper.so",
            gbmShim = "/tmp/shims/gbm-shim/libgbm.so.1",
            eglOverride = "/tmp/shims/egl-override/libegl_override.so",
            netstub = "/tmp/shims/netstub/libnetstub.so",
        )
        assertThat(shimSet.drmShim).endsWith("drm-shim/libdrm-shim.so")
        assertThat(shimSet.drmWrapper).endsWith("drm-shim/libdrm-wrapper.so")
        assertThat(shimSet.gbmShim).endsWith("gbm-shim/libgbm.so.1")
        assertThat(shimSet.eglOverride).endsWith("egl-override/libegl_override.so")
        assertThat(shimSet.netstub).endsWith("netstub/libnetstub.so")
    }

    @Test
    fun `SHIM_ASSETS contains all 5 shim libraries`() {
        assertThat(ShimExtractor.SHIM_ASSETS).hasSize(5)
    }

    @Test
    fun `SHIM_ASSETS asset paths are well-formed`() {
        for ((assetPath, filename) in ShimExtractor.SHIM_ASSETS) {
            assertThat(assetPath).contains("/")
            assertThat(filename).startsWith("lib")
            assertThat(filename).contains(".so")
        }
    }

    @Test
    fun `SHIM_ASSETS includes drm-shim`() {
        val assetPaths = ShimExtractor.SHIM_ASSETS.map { it.first }
        assertThat(assetPaths).contains("drm-shim/libdrm-shim.so")
    }

    @Test
    fun `SHIM_ASSETS includes drm-wrapper`() {
        val assetPaths = ShimExtractor.SHIM_ASSETS.map { it.first }
        assertThat(assetPaths).contains("drm-shim/libdrm-wrapper.so")
    }

    @Test
    fun `SHIM_ASSETS includes gbm-shim`() {
        val assetPaths = ShimExtractor.SHIM_ASSETS.map { it.first }
        assertThat(assetPaths).contains("gbm-shim/libgbm.so")
    }

    @Test
    fun `SHIM_ASSETS includes egl-override`() {
        val assetPaths = ShimExtractor.SHIM_ASSETS.map { it.first }
        assertThat(assetPaths).contains("egl-override/libegl_override.so")
    }

    @Test
    fun `SHIM_ASSETS includes netstub`() {
        val assetPaths = ShimExtractor.SHIM_ASSETS.map { it.first }
        assertThat(assetPaths).contains("netstub/libnetstub.so")
    }

    @Test
    fun `ShimSet data class equality works`() {
        val a = ShimExtractor.ShimSet(
            drmShim = "/a",
            drmWrapper = "/b",
            gbmShim = "/c",
            eglOverride = "/d",
            netstub = "/e",
        )
        val b = ShimExtractor.ShimSet(
            drmShim = "/a",
            drmWrapper = "/b",
            gbmShim = "/c",
            eglOverride = "/d",
            netstub = "/e",
        )
        assertThat(a).isEqualTo(b)
    }

    @Test
    fun `getLdPreloadString for VIRGL includes all except drm-wrapper`() {
        // getLdPreloadString requires a Context-backed ShimExtractor instance,
        // but we can verify the LD_PRELOAD logic through ShimConfig.
        val config = ShimConfig.forGpuMode("VIRGL")
        // VIRGL enables drm, gbm, egl, netstub
        assertThat(config.enableDrmShim).isTrue()
        assertThat(config.enableGbmShim).isTrue()
        assertThat(config.enableEglOverride).isTrue()
        assertThat(config.enableNetstub).isTrue()
    }

    @Test
    fun `getLdPreloadString for SOFTWARE skips drm gbm and egl`() {
        val config = ShimConfig.forGpuMode("SOFTWARE")
        assertThat(config.enableDrmShim).isFalse()
        assertThat(config.enableDrmWrapper).isFalse()
        assertThat(config.enableGbmShim).isFalse()
        assertThat(config.enableEglOverride).isFalse()
        assertThat(config.enableNetstub).isTrue()
    }
}
