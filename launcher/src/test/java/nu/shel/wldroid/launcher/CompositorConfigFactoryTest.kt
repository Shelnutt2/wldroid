package nu.shel.wldroid.launcher

import com.google.common.truth.Truth.assertThat
import nu.shel.wldroid.proot.ProotConfig
import nu.shel.wldroid.proot.RootfsEnvironment
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CompositorConfigFactoryTest {

    @get:Rule
    val tmpDir = TemporaryFolder()

    private fun createManager(): XWaylandManager {
        val config = ProotConfig(
            prootBinaryPath = "/data/app/lib/libproot.so",
            prootLoaderPath = "",
        )
        return XWaylandManager(config, tmpDir.root.absolutePath)
    }

    private fun createEnvironment(): RootfsEnvironment {
        return RootfsEnvironment(
            id = "test-env",
            rootfsPath = "/data/rootfs/debian",
            createdAt = System.currentTimeMillis(),
        )
    }

    @Test fun createWithXWayland_wrapperFileExists() {
        val manager = createManager()
        val factory = CompositorConfigFactory(manager)
        val env = createEnvironment()
        val tempDir = tmpDir.newFolder("temp")

        val config = factory.createWithXWayland(
            environment = env,
            cacheDir = tmpDir.root.absolutePath,
            tempDir = tempDir.absolutePath,
        )

        assertThat(config.xwaylandEnabled).isTrue()
        assertThat(config.xwaylandBinaryPath).isNotEmpty()
        assertThat(java.io.File(config.xwaylandBinaryPath).exists()).isTrue()
    }

    @Test fun createWithXWayland_wrapperIsExecutable() {
        val manager = createManager()
        val factory = CompositorConfigFactory(manager)
        val env = createEnvironment()
        val tempDir = tmpDir.newFolder("temp")

        val config = factory.createWithXWayland(
            environment = env,
            cacheDir = tmpDir.root.absolutePath,
            tempDir = tempDir.absolutePath,
        )

        assertThat(java.io.File(config.xwaylandBinaryPath).canExecute()).isTrue()
    }

    @Test fun createWithXWayland_setsAllFields() {
        val manager = createManager()
        val factory = CompositorConfigFactory(manager)
        val env = createEnvironment()
        val tempDir = tmpDir.newFolder("temp")

        val config = factory.createWithXWayland(
            environment = env,
            cacheDir = "/data/cache",
            tempDir = tempDir.absolutePath,
            xkbBasePath = "/data/xkb",
            gpuMode = "VIRGL_ZINK",
            testClientEnabled = true,
            ahbRegistrySocketPath = "/data/ahb",
        )

        assertThat(config.cacheDir).isEqualTo("/data/cache")
        assertThat(config.xkbBasePath).isEqualTo("/data/xkb")
        assertThat(config.gpuMode).isEqualTo("VIRGL_ZINK")
        assertThat(config.testClientEnabled).isTrue()
        assertThat(config.ahbRegistrySocketPath).isEqualTo("/data/ahb")
    }

    @Test fun createWithXWayland_disabledXWayland_emptyPath() {
        val manager = createManager()
        val factory = CompositorConfigFactory(manager)
        val env = createEnvironment()
        val tempDir = tmpDir.newFolder("temp")

        val config = factory.createWithXWayland(
            environment = env,
            cacheDir = tmpDir.root.absolutePath,
            tempDir = tempDir.absolutePath,
            xwaylandConfig = XWaylandConfig(enabled = false),
        )

        assertThat(config.xwaylandEnabled).isFalse()
        assertThat(config.xwaylandBinaryPath).isEmpty()
    }

    @Test fun createWithXWayland_disabledXWayland_noWrapperExtracted() {
        val manager = createManager()
        val factory = CompositorConfigFactory(manager)
        val env = createEnvironment()
        val tempDir = tmpDir.newFolder("temp")

        factory.createWithXWayland(
            environment = env,
            cacheDir = tmpDir.root.absolutePath,
            tempDir = tempDir.absolutePath,
            xwaylandConfig = XWaylandConfig(enabled = false),
        )

        // Wrapper should NOT be extracted when disabled
        assertThat(java.io.File(manager.wrapperScriptPath).exists()).isFalse()
    }

    @Test fun createWithXWayland_createsX11UnixDir() {
        val manager = createManager()
        val factory = CompositorConfigFactory(manager)
        val env = createEnvironment()
        val tempDir = tmpDir.newFolder("temp")

        factory.createWithXWayland(
            environment = env,
            cacheDir = tmpDir.root.absolutePath,
            tempDir = tempDir.absolutePath,
        )

        assertThat(java.io.File(tempDir, ".X11-unix").exists()).isTrue()
        assertThat(java.io.File(tempDir, ".X11-unix").isDirectory).isTrue()
    }

    @Test fun createWithXWayland_configPassesValidation() {
        val manager = createManager()
        val factory = CompositorConfigFactory(manager)
        val env = createEnvironment()
        val tempDir = tmpDir.newFolder("temp")

        val config = factory.createWithXWayland(
            environment = env,
            cacheDir = tmpDir.root.absolutePath,
            tempDir = tempDir.absolutePath,
        )

        // Should not throw
        config.validate()
    }
}
