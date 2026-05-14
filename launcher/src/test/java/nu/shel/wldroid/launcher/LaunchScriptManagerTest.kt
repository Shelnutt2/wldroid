package nu.shel.wldroid.launcher

import com.google.common.truth.Truth.assertThat
import nu.shel.wldroid.shims.ShimExtractor
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder

class LaunchScriptManagerTest {

    @get:Rule val tempFolder = TemporaryFolder()

    private val manager = LaunchScriptManager()

    private val testShimSet = ShimExtractor.ShimSet(
        drmShim = "/test/drm-shim/libdrm_shim.so",
        drmWrapper = "/test/drm-wrapper/libdrm_wrapper.so",
        gbmShim = "/test/gbm-shim/libgbm_shim.so",
        eglOverride = "/test/egl-override/libegl_override.so",
        netstub = "/test/netstub/libnetstub.so",
    )

    private val testConfig = DesktopLauncherConfig(
        shimExtractDir = "/test",
        waylandRuntimeDir = "/host/runtime",
        tempDir = "/host/tmp",
    )

    @Test fun `buildLaunchCommand includes launch script and user command`() {
        val command = manager.buildLaunchCommand(listOf("code", "--no-sandbox"), testConfig)
        assertThat(command[0]).isEqualTo("bash")
        assertThat(command[1]).isEqualTo("/opt/wldroid/launch-app.sh")
        assertThat(command[2]).isEqualTo("code")
        assertThat(command[3]).isEqualTo("--no-sandbox")
    }

    @Test fun `buildLaunchBindMounts includes wayland runtime`() {
        val scriptFile = tempFolder.newFile("launch-app.sh")
        val mounts = manager.buildLaunchBindMounts(
            scriptFile, testShimSet, "/host/runtime", testConfig,
        )
        assertThat(mounts.any { it.guestPath == "/host/runtime" && it.hostPath == "/host/runtime" }).isTrue()
    }

    @Test fun `buildLaunchBindMounts includes netstub`() {
        val scriptFile = tempFolder.newFile("launch-app.sh")
        val mounts = manager.buildLaunchBindMounts(
            scriptFile, testShimSet, "/host/runtime", testConfig,
        )
        assertThat(mounts.any { it.guestPath == "/opt/wldroid/netstub" }).isTrue()
    }

    @Test fun `buildLaunchBindMounts includes launch script`() {
        val scriptFile = tempFolder.newFile("launch-app.sh")
        val mounts = manager.buildLaunchBindMounts(
            scriptFile, testShimSet, "/host/runtime", testConfig,
        )
        assertThat(mounts.any { it.guestPath == "/opt/wldroid/launch-app.sh" }).isTrue()
    }

    @Test fun `buildLaunchBindMounts uses custom shimGuestBasePath`() {
        val customConfig = testConfig.copy(shimGuestBasePath = "/custom/path")
        val scriptFile = tempFolder.newFile("launch-app.sh")
        val mounts = manager.buildLaunchBindMounts(
            scriptFile, testShimSet, "/host/runtime", customConfig,
        )
        assertThat(mounts.any { it.guestPath == "/custom/path/launch-app.sh" }).isTrue()
    }
}
