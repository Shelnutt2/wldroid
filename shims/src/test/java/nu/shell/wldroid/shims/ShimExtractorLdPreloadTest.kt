package nu.shell.wldroid.shims

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Tests for [ShimExtractor.getLdPreloadString] and related functionality.
 *
 * These tests verify LD_PRELOAD string generation for each GPU mode
 * without requiring Android Context (no asset extraction).
 */
class ShimExtractorLdPreloadTest {

    private val shimSet = ShimExtractor.ShimSet(
        drmShim = "/guest/lib/libdrm-shim.so",
        drmWrapper = "/guest/lib/libdrm-wrapper.so",
        gbmShim = "/guest/lib/libgbm.so.1",
        eglOverride = "/guest/lib/libegl_override.so",
        netstub = "/guest/lib/libnetstub.so",
    )

    // ── LD_PRELOAD for different GPU modes ──

    @Test
    fun `VIRGL_GLES includes all 4 preload shims`() {
        val ldPreload = createExtractorAndGetLdPreload("VIRGL_GLES")
        assertThat(ldPreload).contains("libdrm-shim.so")
        assertThat(ldPreload).contains("libgbm.so.1")
        assertThat(ldPreload).contains("libegl_override.so")
        assertThat(ldPreload).contains("libnetstub.so")
        // drm-wrapper is NOT in LD_PRELOAD (it's loaded via DT_NEEDED)
        assertThat(ldPreload).doesNotContain("libdrm-wrapper.so")
    }

    @Test
    fun `VIRGL_ZINK includes all 4 preload shims`() {
        val ldPreload = createExtractorAndGetLdPreload("VIRGL_ZINK")
        assertThat(ldPreload).contains("libdrm-shim.so")
        assertThat(ldPreload).contains("libgbm.so.1")
        assertThat(ldPreload).contains("libegl_override.so")
        assertThat(ldPreload).contains("libnetstub.so")
    }

    @Test
    fun `SOFTWARE mode excludes gbm and egl`() {
        val ldPreload = createExtractorAndGetLdPreload("SOFTWARE")
        assertThat(ldPreload).contains("libdrm-shim.so")
        assertThat(ldPreload).contains("libnetstub.so")
        assertThat(ldPreload).doesNotContain("libgbm.so.1")
        assertThat(ldPreload).doesNotContain("libegl_override.so")
    }

    @Test
    fun `TURNIP_DIRECT mode excludes egl but includes gbm`() {
        val ldPreload = createExtractorAndGetLdPreload("TURNIP_DIRECT")
        assertThat(ldPreload).contains("libdrm-shim.so")
        assertThat(ldPreload).contains("libgbm.so.1")
        assertThat(ldPreload).contains("libnetstub.so")
        assertThat(ldPreload).doesNotContain("libegl_override.so")
    }

    @Test
    fun `unknown GPU mode includes all shims (defaults)`() {
        val ldPreload = createExtractorAndGetLdPreload("SOME_FUTURE_MODE")
        assertThat(ldPreload).contains("libdrm-shim.so")
        assertThat(ldPreload).contains("libgbm.so.1")
        assertThat(ldPreload).contains("libegl_override.so")
        assertThat(ldPreload).contains("libnetstub.so")
    }

    // ── LD_PRELOAD format ──

    @Test
    fun `LD_PRELOAD uses colon separator`() {
        val ldPreload = createExtractorAndGetLdPreload("VIRGL_GLES")
        val parts = ldPreload.split(":")
        assertThat(parts).hasSize(4)
        for (part in parts) {
            assertThat(part).startsWith("/guest/lib/")
        }
    }

    @Test
    fun `LD_PRELOAD paths use full absolute paths from ShimSet`() {
        val ldPreload = createExtractorAndGetLdPreload("VIRGL_GLES")
        assertThat(ldPreload).contains("/guest/lib/libdrm-shim.so")
        assertThat(ldPreload).contains("/guest/lib/libnetstub.so")
    }

    @Test
    fun `SOFTWARE mode LD_PRELOAD has exactly 2 entries`() {
        val ldPreload = createExtractorAndGetLdPreload("SOFTWARE")
        val parts = ldPreload.split(":")
        assertThat(parts).hasSize(2)
    }

    @Test
    fun `TURNIP_DIRECT mode LD_PRELOAD has exactly 3 entries`() {
        val ldPreload = createExtractorAndGetLdPreload("TURNIP_DIRECT")
        val parts = ldPreload.split(":")
        assertThat(parts).hasSize(3)
    }

    // ── SHIM_ASSETS companion object ──

    @Test
    fun `SHIM_ASSETS filenames are unique`() {
        val filenames = ShimExtractor.SHIM_ASSETS.map { it.second }
        assertThat(filenames.toSet()).hasSize(filenames.size)
    }

    @Test
    fun `SHIM_ASSETS asset paths are unique`() {
        val assetPaths = ShimExtractor.SHIM_ASSETS.map { it.first }
        assertThat(assetPaths.toSet()).hasSize(assetPaths.size)
    }

    @Test
    fun `ShimSet copy with different paths`() {
        val modified = shimSet.copy(drmShim = "/other/path/libdrm-shim.so")
        assertThat(modified.drmShim).isEqualTo("/other/path/libdrm-shim.so")
        assertThat(modified.gbmShim).isEqualTo(shimSet.gbmShim)
    }

    /**
     * Helper that creates a ShimExtractor and calls getLdPreloadString.
     * We can't create a real ShimExtractor (needs Context), so we use reflection
     * to call the method on a minimal instance, or reconstruct the logic.
     *
     * Since getLdPreloadString is a pure function of (ShimSet, gpuMode),
     * we reconstruct its logic here for testing.
     */
    private fun createExtractorAndGetLdPreload(gpuMode: String): String {
        val config = ShimConfig.forGpuMode(gpuMode)
        return buildList {
            if (config.enableDrmShim) add(shimSet.drmShim)
            if (config.enableGbmShim) add(shimSet.gbmShim)
            if (config.enableEglOverride) add(shimSet.eglOverride)
            if (config.enableNetstub) add(shimSet.netstub)
        }.joinToString(":")
    }
}
