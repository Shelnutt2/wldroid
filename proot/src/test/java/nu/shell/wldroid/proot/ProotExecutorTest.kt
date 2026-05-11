package nu.shell.wldroid.proot

import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Tests for [ProotExecutor] command building.
 *
 * These tests verify that proot commands are constructed correctly
 * without actually executing proot (no Android device needed).
 */
class ProotExecutorTest {

    @get:Rule
    val tempDir = TemporaryFolder()

    private lateinit var cacheDir: File
    private lateinit var rootfsDir: File
    private lateinit var executor: ProotExecutor

    @Before
    fun setUp() {
        cacheDir = tempDir.newFolder("cache")
        rootfsDir = tempDir.newFolder("rootfs", "test-env")

        // Create minimal rootfs structure so validation passes
        File(rootfsDir, "etc").mkdirs()
        File(rootfsDir, "etc/os-release").writeText("ID=debian\n")

        executor = ProotExecutor(
            ProotConfig(
                prootBinaryPath = "/app/lib/libproot.so",
                prootLoaderPath = "/app/lib/libproot-loader.so",
                cacheDir = cacheDir.absolutePath,
                fakeRoot = true,
                link2symlink = true,
            ),
        )
    }

    private fun testEnv() = RootfsEnvironment(
        id = "test-env",
        rootfsPath = rootfsDir.absolutePath,
        createdAt = System.currentTimeMillis(),
    )

    @Test
    fun `buildProotCommand includes proot binary as first argument`() {
        val cmd = executor.buildProotCommand(
            environment = testEnv(),
            command = listOf("/bin/bash"),
        )
        assertThat(cmd.args.first()).isEqualTo("/app/lib/libproot.so")
    }

    @Test
    fun `buildProotCommand includes fake root flag when enabled`() {
        val cmd = executor.buildProotCommand(
            environment = testEnv(),
            command = listOf("/bin/bash"),
        )
        assertThat(cmd.args).contains("-0")
    }

    @Test
    fun `buildProotCommand omits fake root flag when disabled`() {
        val noRootExecutor = ProotExecutor(
            ProotConfig(
                prootBinaryPath = "/app/lib/libproot.so",
                cacheDir = cacheDir.absolutePath,
                fakeRoot = false,
                link2symlink = true,
            ),
        )
        val cmd = noRootExecutor.buildProotCommand(
            environment = testEnv(),
            command = listOf("/bin/bash"),
        )
        assertThat(cmd.args).doesNotContain("-0")
    }

    @Test
    fun `buildProotCommand includes link2symlink when enabled`() {
        val cmd = executor.buildProotCommand(
            environment = testEnv(),
            command = listOf("/bin/bash"),
        )
        assertThat(cmd.args).contains("--link2symlink")
    }

    @Test
    fun `buildProotCommand sets rootfs path`() {
        val cmd = executor.buildProotCommand(
            environment = testEnv(),
            command = listOf("/bin/bash"),
        )
        val rIdx = cmd.args.indexOf("-r")
        assertThat(rIdx).isAtLeast(0)
        assertThat(cmd.args[rIdx + 1]).isEqualTo(rootfsDir.absolutePath)
    }

    @Test
    fun `buildProotCommand includes default bind mounts`() {
        val cmd = executor.buildProotCommand(
            environment = testEnv(),
            command = listOf("/bin/bash"),
        )
        // Should bind /dev, /dev/shm, /tmp, /proc, /dev/dri, /sys
        val bindArgs = cmd.args.windowed(2).filter { it[0] == "-b" }.map { it[1] }
        assertThat(bindArgs).contains("/dev")
        assertThat(bindArgs).contains("/proc")
        assertThat(bindArgs).contains("/sys")
        assertThat(bindArgs.any { it.endsWith(":/dev/shm") }).isTrue()
        assertThat(bindArgs.any { it.endsWith(":/tmp") }).isTrue()
        assertThat(bindArgs.any { it.endsWith(":/dev/dri") }).isTrue()
    }

    @Test
    fun `buildProotCommand appends custom bind mounts`() {
        val cmd = executor.buildProotCommand(
            environment = testEnv(),
            command = listOf("/bin/bash"),
            extraBindMounts = listOf(
                BindMount("/host/data", "/guest/data"),
                BindMount("/host/config", "/guest/config"),
            ),
        )
        val bindArgs = cmd.args.windowed(2).filter { it[0] == "-b" }.map { it[1] }
        assertThat(bindArgs).contains("/host/data:/guest/data")
        assertThat(bindArgs).contains("/host/config:/guest/config")
    }

    @Test
    fun `buildProotCommand appends user command at the end`() {
        val cmd = executor.buildProotCommand(
            environment = testEnv(),
            command = listOf("/bin/bash", "-c", "echo hello"),
        )
        assertThat(cmd.args.takeLast(3)).containsExactly("/bin/bash", "-c", "echo hello").inOrder()
    }

    @Test
    fun `buildProotCommand sets standard environment variables`() {
        val cmd = executor.buildProotCommand(
            environment = testEnv(),
            command = listOf("/bin/bash"),
        )
        assertThat(cmd.environment["PROOT_TMP_DIR"]).isEqualTo(cacheDir.absolutePath)
        assertThat(cmd.environment["PROOT_LOADER"]).isEqualTo("/app/lib/libproot-loader.so")
        assertThat(cmd.environment["PATH"]).contains("/usr/bin")
        assertThat(cmd.environment["HOME"]).isEqualTo("/root")
        assertThat(cmd.environment["USER"]).isEqualTo("root")
        assertThat(cmd.environment["LANG"]).isEqualTo("C.UTF-8")
        assertThat(cmd.environment["ELECTRON_NO_SANDBOX"]).isEqualTo("1")
    }

    @Test
    fun `buildProotCommand allows env var overrides`() {
        val cmd = executor.buildProotCommand(
            environment = testEnv(),
            command = listOf("/bin/bash"),
            envVars = mapOf("HOME" to "/home/custom", "MY_VAR" to "my_value"),
        )
        assertThat(cmd.environment["HOME"]).isEqualTo("/home/custom")
        assertThat(cmd.environment["MY_VAR"]).isEqualTo("my_value")
    }

    @Test
    fun `buildProotCommand guest env vars are injected via env command not host environment`() {
        val cmd = executor.buildProotCommand(
            environment = testEnv(),
            command = listOf("/bin/bash"),
            guestEnvVars = mapOf(
                "LD_PRELOAD" to "/opt/wldroid/drm-shim/libdrm-shim.so",
                "WAYLAND_DISPLAY" to "wayland-0",
            ),
        )
        // Guest env vars must NOT be in the host process environment
        assertThat(cmd.environment).doesNotContainKey("LD_PRELOAD")
        assertThat(cmd.environment).doesNotContainKey("WAYLAND_DISPLAY")

        // Guest env vars must be passed via /usr/bin/env before the user command
        val envIdx = cmd.args.indexOf("/usr/bin/env")
        assertThat(envIdx).isAtLeast(0)
        val bashIdx = cmd.args.indexOf("/bin/bash")
        assertThat(bashIdx).isGreaterThan(envIdx)

        // The env assignments should be between /usr/bin/env and the user command
        val envAssignments = cmd.args.subList(envIdx + 1, bashIdx)
        assertThat(envAssignments).contains("LD_PRELOAD=/opt/wldroid/drm-shim/libdrm-shim.so")
        assertThat(envAssignments).contains("WAYLAND_DISPLAY=wayland-0")
    }

    @Test
    fun `buildProotCommand without guest env vars does not inject env command`() {
        val cmd = executor.buildProotCommand(
            environment = testEnv(),
            command = listOf("/bin/bash"),
        )
        assertThat(cmd.args).doesNotContain("/usr/bin/env")
    }

    @Test
    fun `buildProotCommand omits PROOT_LOADER when loaderPath is empty`() {
        val noLoaderExecutor = ProotExecutor(
            ProotConfig(
                prootBinaryPath = "/app/lib/libproot.so",
                prootLoaderPath = "",
                cacheDir = cacheDir.absolutePath,
            ),
        )
        val cmd = noLoaderExecutor.buildProotCommand(
            environment = testEnv(),
            command = listOf("/bin/bash"),
        )
        assertThat(cmd.environment).doesNotContainKey("PROOT_LOADER")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `buildProotCommand throws on missing rootfs`() {
        // Create an env pointing to a non-existent rootfs
        val badEnv = RootfsEnvironment(
            id = "missing",
            rootfsPath = "/nonexistent/path",
            createdAt = System.currentTimeMillis(),
        )
        executor.buildProotCommand(badEnv, listOf("/bin/bash"))
    }

    @Test
    fun `buildProotCommand sets working directory to root`() {
        val cmd = executor.buildProotCommand(
            environment = testEnv(),
            command = listOf("/bin/bash"),
        )
        val wIdx = cmd.args.indexOf("-w")
        assertThat(wIdx).isAtLeast(0)
        assertThat(cmd.args[wIdx + 1]).isEqualTo("/root")
    }

    @Test
    fun `buildProotCommand creates fake sysfs directories`() {
        executor.buildProotCommand(
            environment = testEnv(),
            command = listOf("/bin/bash"),
        )
        // Verify fake sysfs was set up
        val sysDir = File(cacheDir, "proot-sys")
        assertThat(sysDir.exists()).isTrue()
        assertThat(File(sysDir, "bus/platform").exists()).isTrue()
        assertThat(File(sysDir, "dev/char/226:128/dev").exists()).isTrue()
        assertThat(File(sysDir, "dev/char/226:128/device/uevent").exists()).isTrue()
    }
}
