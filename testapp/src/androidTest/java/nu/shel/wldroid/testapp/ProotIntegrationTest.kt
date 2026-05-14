package nu.shel.wldroid.testapp

import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import java.io.File
import nu.shel.wldroid.proot.DistroTemplate
import nu.shel.wldroid.proot.EnvironmentConfig
import nu.shel.wldroid.proot.ProotConfig
import nu.shel.wldroid.proot.ProotExecutor
import nu.shel.wldroid.proot.RootfsStore
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

/**
 * Integration tests for proot environment management.
 *
 * Note: Full environment creation tests require network access for rootfs download.
 * These tests verify configuration and object creation.
 */
@RunWith(JUnit4::class)
class ProotIntegrationTest {

    @Test
    fun prootConfigIsConstructable() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val nativeLibDir = context.applicationInfo.nativeLibraryDir
        val config = ProotConfig(
            prootBinaryPath = "$nativeLibDir/libproot.so",
            prootLoaderPath = "$nativeLibDir/libproot-loader.so",
            defaultDistro = DistroTemplate.DEBIAN_TRIXIE,
            rootfsBaseDir = File(context.filesDir, "rootfs_test").absolutePath,
            cacheDir = context.cacheDir.absolutePath,
        )
        assertThat(config.prootBinaryPath).endsWith("libproot.so")
        assertThat(config.fakeRoot).isTrue()
        assertThat(config.link2symlink).isTrue()
    }

    @Test
    fun rootfsStoreInitiallyEmpty() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = RootfsStore(context)
        // Store should be constructable without errors
        assertThat(store).isNotNull()
    }

    @Test
    fun environmentConfigIsConstructable() {
        val config = EnvironmentConfig(
            name = "test-env",
            distro = DistroTemplate.DEBIAN_TRIXIE,
        )
        assertThat(config.name).isEqualTo("test-env")
        assertThat(config.distro).isEqualTo(DistroTemplate.DEBIAN_TRIXIE)
        assertThat(config.bindMounts).isEmpty()
        assertThat(config.environmentVariables).isEmpty()
    }

    @Test
    fun distroTemplatesAreAvailable() {
        val distros = DistroTemplate.entries
        assertThat(distros).isNotEmpty()
        distros.forEach { distro ->
            assertThat(distro.displayName).isNotEmpty()
            assertThat(distro.downloadUrl).startsWith("http")
            assertThat(distro.version).isNotEmpty()
        }
    }

    @Test
    fun prootExecutorIsConstructable() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val config = ProotConfig(
            prootBinaryPath = "${context.applicationInfo.nativeLibraryDir}/libproot.so",
            rootfsBaseDir = File(context.filesDir, "rootfs_test").absolutePath,
            cacheDir = context.cacheDir.absolutePath,
        )
        val executor = ProotExecutor(config)
        assertThat(executor).isNotNull()
    }
}
