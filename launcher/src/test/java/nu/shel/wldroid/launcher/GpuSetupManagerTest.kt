package nu.shel.wldroid.launcher

import com.google.common.truth.Truth.assertThat
import nu.shel.wldroid.shims.ShimExtractor
import nu.shel.wldroid.virgl.GpuMode
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder

class GpuSetupManagerTest {

    @get:Rule val tempFolder = TemporaryFolder()

    private val testShimSet = ShimExtractor.ShimSet(
        drmShim = "/test/drm-shim/libdrm_shim.so",
        drmWrapper = "/test/drm-wrapper/libdrm_wrapper.so",
        gbmShim = "/test/gbm-shim/libgbm_shim.so",
        eglOverride = "/test/egl-override/libegl_override.so",
        netstub = "/test/netstub/libnetstub.so",
    )

    // --- GpuSetupResult tests ---

    @Test fun `GpuSetupResult success has correct fields`() {
        val result = GpuSetupManager.GpuSetupResult(
            success = true, effectiveMode = GpuMode.VIRGL_GLES,
        )
        assertThat(result.success).isTrue()
        assertThat(result.effectiveMode).isEqualTo(GpuMode.VIRGL_GLES)
        assertThat(result.errorMessage).isNull()
    }

    @Test fun `GpuSetupResult failure carries error message`() {
        val result = GpuSetupManager.GpuSetupResult(
            success = false,
            effectiveMode = GpuMode.SOFTWARE,
            errorMessage = "setup-gpu.sh exited with code 1",
        )
        assertThat(result.success).isFalse()
        assertThat(result.effectiveMode).isEqualTo(GpuMode.SOFTWARE)
        assertThat(result.errorMessage).isNotEmpty()
    }

    // --- Setup env vars tests ---

    @Test fun `buildSetupEnvVars includes GPU mode`() {
        val manager = GpuSetupManager(prootExecutor = throwingProotExecutor())
        val vars = manager.buildSetupEnvVars(GpuMode.VIRGL_GLES, GpuSetupConfig())
        assertThat(vars).containsEntry("WLDROID_GPU_MODE", "VIRGL_GLES")
    }

    @Test fun `buildSetupEnvVars skips mesa when disabled`() {
        val manager = GpuSetupManager(prootExecutor = throwingProotExecutor())
        val config = GpuSetupConfig(installMesaPackages = false)
        val vars = manager.buildSetupEnvVars(GpuMode.VIRGL_GLES, config)
        assertThat(vars).containsEntry("WLDROID_SKIP_MESA_INSTALL", "1")
    }

    @Test fun `buildSetupEnvVars includes debug when enabled`() {
        val manager = GpuSetupManager(prootExecutor = throwingProotExecutor())
        val config = GpuSetupConfig(gpuDebugEnabled = true)
        val vars = manager.buildSetupEnvVars(GpuMode.VIRGL_GLES, config)
        assertThat(vars).containsEntry("WLDROID_DEBUG", "1")
    }

    @Test fun `buildSetupEnvVars excludes debug by default`() {
        val manager = GpuSetupManager(prootExecutor = throwingProotExecutor())
        val vars = manager.buildSetupEnvVars(GpuMode.VIRGL_GLES, GpuSetupConfig())
        assertThat(vars).doesNotContainKey("WLDROID_DEBUG")
    }

    // --- Bind mount tests ---

    @Test fun `buildSetupBindMounts includes shim dirs and script`() {
        val manager = GpuSetupManager(prootExecutor = throwingProotExecutor())
        val scriptFile = tempFolder.newFile("setup-gpu.sh")
        val config = GpuSetupConfig(shimBasePath = "/opt/wldroid")
        val mounts = manager.buildSetupBindMounts(testShimSet, scriptFile, config)

        assertThat(mounts.any { it.guestPath == "/opt/wldroid/drm-shim" }).isTrue()
        assertThat(mounts.any { it.guestPath == "/opt/wldroid/gbm-shim" }).isTrue()
        assertThat(mounts.any { it.guestPath == "/opt/wldroid/egl-override" }).isTrue()
        assertThat(mounts.any { it.guestPath == "/opt/wldroid/setup-gpu.sh" }).isTrue()
    }

    // --- Legacy package list tests (backward compat) ---

    @Suppress("DEPRECATION")
    @Test fun `virgl packages contain mesa-dri`() {
        assertThat(GpuSetupManager.VIRGL_PACKAGES).contains("libgl1-mesa-dri")
    }

    @Suppress("DEPRECATION")
    @Test fun `turnip packages contain vulkan drivers`() {
        assertThat(GpuSetupManager.TURNIP_PACKAGES).contains("mesa-vulkan-drivers")
    }

    @Suppress("DEPRECATION")
    @Test fun `venus packages contain vulkan drivers`() {
        assertThat(GpuSetupManager.VENUS_PACKAGES).contains("mesa-vulkan-drivers")
    }

    /** Creates a ProotExecutor that throws if used — for unit tests that don't run proot. */
    private fun throwingProotExecutor(): nu.shel.wldroid.proot.ProotExecutor {
        return nu.shel.wldroid.proot.ProotExecutor(
            nu.shel.wldroid.proot.ProotConfig(
                prootBinaryPath = "/nonexistent",
                rootfsBaseDir = "/nonexistent",
            ),
        )
    }
}
