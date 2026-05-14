package nu.shel.wldroid.proot

import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import java.io.File

/**
 * Instrumented tests for [ProotExecutor] command building with real device paths.
 */
@RunWith(JUnit4::class)
class ProotExecutorTest {

    @Test
    fun buildCommandProducesValidProcessBuilder() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val nativeLibDir = context.applicationInfo.nativeLibraryDir
        val rootfsDir = File(context.filesDir, "executor_test_rootfs")
        rootfsDir.mkdirs()
        File(rootfsDir, "etc").mkdirs()
        File(rootfsDir, "etc/os-release").writeText("ID=test\n")

        val config = ProotConfig(
            prootBinaryPath = "$nativeLibDir/libproot.so",
            prootLoaderPath = "$nativeLibDir/libproot-loader.so",
            rootfsBaseDir = rootfsDir.absolutePath,
            cacheDir = context.cacheDir.absolutePath,
        )
        val executor = ProotExecutor(config)

        val env = RootfsEnvironment(
            id = "test",
            name = "Test",
            rootfsPath = rootfsDir.absolutePath,
            createdAt = System.currentTimeMillis(),
        )

        // buildCommand should produce a ProcessBuilder with the proot binary as first arg
        val pb = executor.buildCommand(
            environment = env,
            command = listOf("/bin/bash", "-c", "echo hello"),
        )
        val args = pb.command()
        assertThat(args).isNotEmpty()
        assertThat(args[0]).endsWith("libproot.so")
        assertThat(args).contains("-0") // fakeRoot is true by default
        assertThat(pb.redirectErrorStream()).isTrue()

        // Environment should be set (not inherited Android env)
        val pbEnv = pb.environment()
        assertThat(pbEnv).isNotEmpty()
        assertThat(pbEnv.containsKey("BOOTCLASSPATH")).isFalse()

        rootfsDir.deleteRecursively()
    }

    @Test
    fun buildCommandWithCustomBindMounts() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val nativeLibDir = context.applicationInfo.nativeLibraryDir
        val rootfsDir = File(context.filesDir, "executor_bind_test")
        rootfsDir.mkdirs()
        File(rootfsDir, "etc").mkdirs()
        File(rootfsDir, "etc/os-release").writeText("ID=test\n")

        val config = ProotConfig(
            prootBinaryPath = "$nativeLibDir/libproot.so",
            rootfsBaseDir = rootfsDir.absolutePath,
            cacheDir = context.cacheDir.absolutePath,
        )
        val executor = ProotExecutor(config)

        val env = RootfsEnvironment(
            id = "bind-test",
            rootfsPath = rootfsDir.absolutePath,
            createdAt = System.currentTimeMillis(),
        )

        val extraBinds = listOf(
            BindMount(hostPath = "/sdcard", guestPath = "/mnt/sdcard"),
        )

        val pb = executor.buildCommand(
            environment = env,
            command = listOf("ls"),
            bindMounts = extraBinds,
        )
        val fullCmd = pb.command().joinToString(" ")
        // The bind mount should appear in the command
        assertThat(fullCmd).contains("/sdcard")

        rootfsDir.deleteRecursively()
    }

    @Test
    fun buildCommandWithCustomEnvVars() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val nativeLibDir = context.applicationInfo.nativeLibraryDir
        val rootfsDir = File(context.filesDir, "executor_env_test")
        rootfsDir.mkdirs()
        File(rootfsDir, "etc").mkdirs()
        File(rootfsDir, "etc/os-release").writeText("ID=test\n")

        val config = ProotConfig(
            prootBinaryPath = "$nativeLibDir/libproot.so",
            rootfsBaseDir = rootfsDir.absolutePath,
            cacheDir = context.cacheDir.absolutePath,
        )
        val executor = ProotExecutor(config)

        val env = RootfsEnvironment(
            id = "env-test",
            rootfsPath = rootfsDir.absolutePath,
            createdAt = System.currentTimeMillis(),
        )

        val pb = executor.buildCommand(
            environment = env,
            command = listOf("env"),
            envVars = mapOf("CUSTOM_VAR" to "custom_value"),
        )

        val pbEnv = pb.environment()
        assertThat(pbEnv["CUSTOM_VAR"]).isEqualTo("custom_value")

        rootfsDir.deleteRecursively()
    }

    @Test
    fun prootConfigPreservesAllFields() {
        val config = ProotConfig(
            prootBinaryPath = "/usr/bin/proot",
            prootLoaderPath = "/usr/lib/proot-loader",
            defaultDistro = DistroTemplate.DEBIAN_BOOKWORM,
            rootfsBaseDir = "/data/rootfs",
            cacheDir = "/data/cache",
            fakeRoot = false,
            link2symlink = false,
        )
        assertThat(config.prootBinaryPath).isEqualTo("/usr/bin/proot")
        assertThat(config.prootLoaderPath).isEqualTo("/usr/lib/proot-loader")
        assertThat(config.defaultDistro).isEqualTo(DistroTemplate.DEBIAN_BOOKWORM)
        assertThat(config.rootfsBaseDir).isEqualTo("/data/rootfs")
        assertThat(config.cacheDir).isEqualTo("/data/cache")
        assertThat(config.fakeRoot).isFalse()
        assertThat(config.link2symlink).isFalse()
    }

    @Test
    fun distroTemplateMetadataIsComplete() {
        for (template in DistroTemplate.entries) {
            assertThat(template.displayName).isNotEmpty()
            assertThat(template.downloadUrl).startsWith("https://")
            assertThat(template.sha256).hasLength(64)
            assertThat(template.version).isNotEmpty()
        }
    }
}
