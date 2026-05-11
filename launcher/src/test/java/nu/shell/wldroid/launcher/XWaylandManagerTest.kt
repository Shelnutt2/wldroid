package nu.shell.wldroid.launcher

import com.google.common.truth.Truth.assertThat
import nu.shell.wldroid.proot.ProotConfig
import nu.shell.wldroid.proot.RootfsEnvironment
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class XWaylandManagerTest {

    @get:Rule
    val tmpDir = TemporaryFolder()

    private fun createManager(
        prootBinaryPath: String = "/data/app/lib/libproot.so",
        prootLoaderPath: String = "",
    ): XWaylandManager {
        val config = ProotConfig(
            prootBinaryPath = prootBinaryPath,
            prootLoaderPath = prootLoaderPath,
        )
        return XWaylandManager(config, tmpDir.root.absolutePath)
    }

    private fun createEnvironment(rootfsPath: String = "/data/rootfs/debian"): RootfsEnvironment {
        return RootfsEnvironment(
            id = "test-env",
            rootfsPath = rootfsPath,
            createdAt = System.currentTimeMillis(),
        )
    }

    @Test fun wrapperScriptPath_underCacheDir() {
        val manager = createManager()
        assertThat(manager.wrapperScriptPath).isEqualTo("${tmpDir.root.absolutePath}/xwayland-wrapper.sh")
    }

    @Test fun extractWrapperScript_createsExecutableFile() {
        val manager = createManager()
        val env = createEnvironment()

        val path = manager.extractWrapperScript(env)

        val file = java.io.File(path)
        assertThat(file.exists()).isTrue()
        assertThat(file.canExecute()).isTrue()
    }

    @Test fun extractWrapperScript_returnsWrapperPath() {
        val manager = createManager()
        val env = createEnvironment()

        val path = manager.extractWrapperScript(env)

        assertThat(path).isEqualTo(manager.wrapperScriptPath)
    }

    @Test fun buildWrapperScript_containsShebang() {
        val manager = createManager()
        val env = createEnvironment()

        val script = manager.buildWrapperScript(env)

        assertThat(script).startsWith("#!/system/bin/sh")
    }

    @Test fun buildWrapperScript_containsProotBinary() {
        val manager = createManager(prootBinaryPath = "/data/app/lib/libproot.so")
        val env = createEnvironment()

        val script = manager.buildWrapperScript(env)

        assertThat(script).contains("/data/app/lib/libproot.so")
    }

    @Test fun buildWrapperScript_containsRootfsPath() {
        val manager = createManager()
        val env = createEnvironment(rootfsPath = "/data/rootfs/my-debian")

        val script = manager.buildWrapperScript(env)

        assertThat(script).contains("/data/rootfs/my-debian")
    }

    @Test fun buildWrapperScript_containsXwaylandExec() {
        val manager = createManager()
        val env = createEnvironment()

        val script = manager.buildWrapperScript(env)

        assertThat(script).contains("/usr/bin/Xwayland")
        assertThat(script).contains("exec")
    }

    @Test fun buildWrapperScript_passesArguments() {
        val manager = createManager()
        val env = createEnvironment()

        val script = manager.buildWrapperScript(env)

        assertThat(script).contains("\"\$@\"")
    }

    @Test fun buildWrapperScript_bindsRequiredPaths() {
        val manager = createManager()
        val env = createEnvironment()

        val script = manager.buildWrapperScript(env)

        assertThat(script).contains("-b /dev")
        assertThat(script).contains("-b /proc")
        assertThat(script).contains("-b /tmp")
    }

    @Test fun buildWrapperScript_runsFakeRoot() {
        val manager = createManager()
        val env = createEnvironment()

        val script = manager.buildWrapperScript(env)

        assertThat(script).contains("-0")
    }

    @Test fun buildWrapperScript_withLoader_setsProotLoader() {
        val manager = createManager(prootLoaderPath = "/data/app/lib/libproot-loader.so")
        val env = createEnvironment()

        val script = manager.buildWrapperScript(env)

        assertThat(script).contains("export PROOT_LOADER=\"/data/app/lib/libproot-loader.so\"")
    }

    @Test fun buildWrapperScript_withoutLoader_noProotLoaderExport() {
        val manager = createManager(prootLoaderPath = "")
        val env = createEnvironment()

        val script = manager.buildWrapperScript(env)

        assertThat(script).doesNotContain("PROOT_LOADER")
    }

    @Test fun buildWrapperScript_setsProotTmpDir() {
        val manager = createManager()
        val env = createEnvironment()

        val script = manager.buildWrapperScript(env)

        assertThat(script).contains("PROOT_TMP_DIR=\"${tmpDir.root.absolutePath}/proot-tmp\"")
    }

    @Test fun ensureTmpDirReady_createsX11UnixDir() {
        val manager = createManager()
        val tempDir = tmpDir.newFolder("proot-tmp")

        manager.ensureTmpDirReady(tempDir.absolutePath)

        val x11Dir = java.io.File(tempDir, ".X11-unix")
        assertThat(x11Dir.exists()).isTrue()
        assertThat(x11Dir.isDirectory).isTrue()
    }
}
