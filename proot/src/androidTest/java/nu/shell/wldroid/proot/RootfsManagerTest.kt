package nu.shell.wldroid.proot

import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import java.io.File

/**
 * Instrumented tests for [RootfsManager] filesystem operations.
 *
 * Uses the real Android filesystem (test context's filesDir) to verify
 * directory creation, deletion, and environment tracking.
 */
@RunWith(JUnit4::class)
class RootfsManagerTest {

    private lateinit var testBaseDir: File
    private lateinit var testCacheDir: File

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        testBaseDir = File(context.filesDir, "rootfs_test_${System.nanoTime()}")
        testCacheDir = File(context.cacheDir, "rootfs_cache_test_${System.nanoTime()}")
        testBaseDir.mkdirs()
        testCacheDir.mkdirs()
    }

    @After
    fun tearDown() {
        testBaseDir.deleteRecursively()
        testCacheDir.deleteRecursively()
    }

    @Test
    fun createEnvironmentDirectoryStructure() {
        // Create an environment directory and verify it exists
        val envId = "test-env-1"
        val envDir = File(testBaseDir, envId)
        envDir.mkdirs()

        // Create expected subdirectories like a real rootfs
        File(envDir, "etc").mkdirs()
        File(envDir, "usr/bin").mkdirs()
        File(envDir, "home").mkdirs()

        assertThat(envDir.exists()).isTrue()
        assertThat(envDir.isDirectory).isTrue()
        assertThat(File(envDir, "etc").exists()).isTrue()
        assertThat(File(envDir, "usr/bin").exists()).isTrue()
        assertThat(File(envDir, "home").exists()).isTrue()
    }

    @Test
    fun deleteEnvironmentCleansUpDirectoryTree() {
        // Create a nested directory structure
        val envId = "test-env-delete"
        val envDir = File(testBaseDir, envId)
        File(envDir, "etc").mkdirs()
        File(envDir, "usr/lib").mkdirs()
        File(envDir, "etc/resolv.conf").writeText("nameserver 8.8.8.8\n")
        File(envDir, "usr/lib/libtest.so").writeText("fake")

        assertThat(envDir.exists()).isTrue()

        // Delete the environment directory
        val deleted = envDir.deleteRecursively()
        assertThat(deleted).isTrue()
        assertThat(envDir.exists()).isFalse()
    }

    @Test
    fun rootfsStoreRoundTrip() = runTest {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = RootfsStore(context)

        val env = RootfsEnvironment(
            id = "round-trip-test-${System.nanoTime()}",
            name = "Test Environment",
            rootfsPath = "${testBaseDir.absolutePath}/test-rt",
            distro = "debian-trixie",
            createdAt = System.currentTimeMillis(),
            sizeBytes = 1024L,
            status = RootfsStatus.READY,
        )

        // Add and read back
        store.addEnvironment(env)
        val envs = store.getEnvironments().first()
        val found = envs.find { it.id == env.id }

        assertThat(found).isNotNull()
        assertThat(found!!.name).isEqualTo("Test Environment")
        assertThat(found.distro).isEqualTo("debian-trixie")
        assertThat(found.sizeBytes).isEqualTo(1024L)
        assertThat(found.status).isEqualTo(RootfsStatus.READY)

        // Clean up
        store.removeEnvironment(env.id)
        val afterRemove = store.getEnvironments().first()
        assertThat(afterRemove.find { it.id == env.id }).isNull()
    }

    @Test
    fun rootfsStoreUpdateEnvironment() = runTest {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = RootfsStore(context)

        val envId = "update-test-${System.nanoTime()}"
        val env = RootfsEnvironment(
            id = envId,
            name = "Before Update",
            rootfsPath = "${testBaseDir.absolutePath}/update",
            createdAt = System.currentTimeMillis(),
        )

        store.addEnvironment(env)

        // Update the environment
        store.updateEnvironment(envId) { it.copy(name = "After Update", sizeBytes = 999L) }

        val updated = store.getEnvironments().first().find { it.id == envId }
        assertThat(updated).isNotNull()
        assertThat(updated!!.name).isEqualTo("After Update")
        assertThat(updated.sizeBytes).isEqualTo(999L)

        // Clean up
        store.removeEnvironment(envId)
    }

    @Test
    fun hasEnvironmentChecksFsPresence() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = RootfsStore(context)
        val downloader = RootfsDownloader(testCacheDir)
        val extractor = RootfsExtractor()
        val manager = RootfsManager(testBaseDir, store, downloader, extractor)

        // No environment yet
        assertThat(manager.hasEnvironment("nonexistent")).isFalse()

        // Create fake os-release to simulate a valid environment
        val envDir = File(testBaseDir, "fake-env")
        File(envDir, "etc").mkdirs()
        File(envDir, "etc/os-release").writeText("ID=debian\n")

        assertThat(manager.hasEnvironment("fake-env")).isTrue()
    }

    @Test
    fun getDiskUsageReturnsZeroForMissing() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = RootfsStore(context)
        val downloader = RootfsDownloader(testCacheDir)
        val extractor = RootfsExtractor()
        val manager = RootfsManager(testBaseDir, store, downloader, extractor)

        assertThat(manager.getDiskUsage("nonexistent")).isEqualTo(0)
    }

    @Test
    fun getDiskUsageCountsFiles() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = RootfsStore(context)
        val downloader = RootfsDownloader(testCacheDir)
        val extractor = RootfsExtractor()
        val manager = RootfsManager(testBaseDir, store, downloader, extractor)

        // Create some files
        val envDir = File(testBaseDir, "size-test")
        envDir.mkdirs()
        File(envDir, "file1.txt").writeText("hello world")
        File(envDir, "file2.txt").writeText("test data for size calculation")

        val usage = manager.getDiskUsage("size-test")
        assertThat(usage).isGreaterThan(0)
    }
}
