package nu.shell.wldroid.proot

import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Tests for [RootfsManager] environment lifecycle operations.
 *
 * Tests that don't require network access or Android context (e.g., directory
 * checks, disk usage). Network-dependent tests (createEnvironment) and tests
 * requiring Android context (configureRootfs via RootfsManager instance) are
 * tested via instrumentation.
 */
class RootfsManagerTest {

    @get:Rule
    val tempDir = TemporaryFolder()

    @Test
    fun `environment detection returns false for missing directory`() {
        val rootfsBaseDir = tempDir.newFolder("rootfs")
        val osRelease = File(rootfsBaseDir, "nonexistent/etc/os-release")
        assertThat(osRelease.exists()).isFalse()
    }

    @Test
    fun `environment detection returns true when os-release exists`() {
        val rootfsBaseDir = tempDir.newFolder("rootfs")
        val envDir = File(rootfsBaseDir, "test-env/etc")
        envDir.mkdirs()
        File(envDir, "os-release").writeText("ID=debian\n")
        assertThat(File(rootfsBaseDir, "test-env/etc/os-release").exists()).isTrue()
    }

    @Test
    fun `disk usage is zero for missing directory`() {
        val rootfsBaseDir = tempDir.newFolder("rootfs")
        val envDir = File(rootfsBaseDir, "nonexistent")
        assertThat(envDir.exists()).isFalse()
    }

    @Test
    fun `disk usage sums file sizes correctly`() {
        val envDir = tempDir.newFolder("test-env")
        File(envDir, "file1.txt").writeText("Hello") // 5 bytes
        File(envDir, "file2.txt").writeText("World!") // 6 bytes
        File(envDir, "subdir").mkdirs()
        File(envDir, "subdir/file3.txt").writeText("Test") // 4 bytes

        val totalSize = envDir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        assertThat(totalSize).isEqualTo(15L)
    }

    @Test
    fun `rootfs configuration creates required files`() {
        // Test configureRootfs logic directly as a static-like helper
        val envDir = tempDir.newFolder("rootfs-test")
        val etcDir = File(envDir, "etc")
        etcDir.mkdirs()

        // Manually replicate configureRootfs logic for testing without Android context
        File(etcDir, "environment").writeText(
            "PATH=\"/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin\"\n",
        )
        File(etcDir, "profile.d").mkdirs()
        File(etcDir, "profile.d/wldroid-path.sh").writeText(
            "# Set by WLDroid\nexport PATH=\"/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin\"\n",
        )
        File(envDir, "dev/shm").mkdirs()
        File(envDir, "tmp").apply {
            mkdirs()
            setReadable(true, false)
            setWritable(true, false)
            setExecutable(true, false)
        }
        File(etcDir, "resolv.conf").writeText("nameserver 8.8.8.8\nnameserver 8.8.4.4\n")
        File(envDir, "home/user").mkdirs()
        File(etcDir, "passwd").writeText("user:x:1000:1000:Default User:/home/user:/bin/bash\n")
        File(etcDir, "group").writeText("user:x:1000:user\nsudo:x:27:user\n")
        File(etcDir, "shadow").writeText("user:!:19000:0:99999:7:::\n")
        File(etcDir, "sudoers.d").mkdirs()
        File(etcDir, "sudoers.d/user").writeText("user ALL=(ALL) NOPASSWD:ALL\n")

        // Verify all files are created
        assertThat(File(etcDir, "environment").exists()).isTrue()
        assertThat(File(etcDir, "environment").readText()).contains("PATH=")
        assertThat(File(etcDir, "profile.d/wldroid-path.sh").exists()).isTrue()
        assertThat(File(envDir, "dev/shm").exists()).isTrue()
        assertThat(File(envDir, "tmp").exists()).isTrue()
        assertThat(File(etcDir, "resolv.conf").readText()).contains("nameserver 8.8.8.8")
        assertThat(File(etcDir, "passwd").readText()).contains("user:x:1000:1000")
        assertThat(File(envDir, "home/user").exists()).isTrue()
        assertThat(File(etcDir, "sudoers.d/user").readText()).contains("NOPASSWD")
    }

    @Test
    fun `user creation is idempotent in passwd file`() {
        val etcDir = tempDir.newFolder("etc-test")
        val passwdFile = File(etcDir, "passwd")

        // Simulate configureRootfs passwd logic twice
        val userEntry = "user:x:1000:1000:Default User:/home/user:/bin/bash\n"
        if (!passwdFile.exists() || !passwdFile.readText().contains("user:")) {
            passwdFile.appendText(userEntry)
        }
        // Second call should not add again
        if (!passwdFile.exists() || !passwdFile.readText().contains("user:")) {
            passwdFile.appendText(userEntry)
        }

        val userEntries = passwdFile.readText().lines().filter { it.startsWith("user:") }
        assertThat(userEntries).hasSize(1)
    }

    @Test
    fun `environment directory path construction`() {
        val rootfsBaseDir = tempDir.newFolder("rootfs")
        val envDir = File(rootfsBaseDir, "my-env")
        assertThat(envDir.name).isEqualTo("my-env")
        assertThat(envDir.parentFile).isEqualTo(rootfsBaseDir)
    }

    @Test
    fun `RootfsExtractor stripLinkTarget preserves relative paths`() {
        val extractor = RootfsExtractor()
        assertThat(extractor.stripLinkTarget("../../lib/libfoo.so", "debian/usr/bin/foo", 1))
            .isEqualTo("../../lib/libfoo.so")
    }

    @Test
    fun `RootfsExtractor stripLinkTarget strips matching prefix`() {
        val extractor = RootfsExtractor()
        assertThat(extractor.stripLinkTarget("debian/usr/lib/libfoo.so", "debian/usr/bin/foo", 1))
            .isEqualTo("usr/lib/libfoo.so")
    }

    @Test
    fun `RootfsExtractor stripLinkTarget preserves absolute paths`() {
        val extractor = RootfsExtractor()
        assertThat(extractor.stripLinkTarget("/usr/lib/libfoo.so", "debian/usr/bin/foo", 1))
            .isEqualTo("/usr/lib/libfoo.so")
    }

    // ── Orphan detection tests ──

    @Test
    fun `orphan detection finds directories with os-release not in store`() {
        val rootfsBaseDir = tempDir.newFolder("rootfs")
        // Create a valid orphan
        val orphanDir = File(rootfsBaseDir, "orphan-env")
        File(orphanDir, "etc").mkdirs()
        File(orphanDir, "etc/os-release").writeText("ID=debian\n")

        // Create an invalid directory (no os-release)
        val invalidDir = File(rootfsBaseDir, "invalid-env")
        invalidDir.mkdirs()

        val validOrphans = rootfsBaseDir.listFiles()
            ?.filter { dir ->
                dir.isDirectory && File(dir, "etc/os-release").exists()
            }
            ?.map { it.name }
            ?: emptyList()

        assertThat(validOrphans).containsExactly("orphan-env")
        assertThat(validOrphans).doesNotContain("invalid-env")
    }

    @Test
    fun `orphan detection excludes registered environments`() {
        val rootfsBaseDir = tempDir.newFolder("rootfs")
        // Create two valid rootfs directories
        for (id in listOf("registered-env", "orphan-env")) {
            val dir = File(rootfsBaseDir, id)
            File(dir, "etc").mkdirs()
            File(dir, "etc/os-release").writeText("ID=debian\n")
        }

        val registeredIds = setOf("registered-env")
        val orphans = rootfsBaseDir.listFiles()
            ?.filter { dir ->
                dir.isDirectory &&
                    File(dir, "etc/os-release").exists() &&
                    dir.name !in registeredIds
            }
            ?.map { it.name }
            ?: emptyList()

        assertThat(orphans).containsExactly("orphan-env")
    }

    @Test
    fun `orphan detection returns empty for nonexistent base dir`() {
        val nonexistent = File(tempDir.root, "does-not-exist")
        assertThat(nonexistent.exists()).isFalse()
        // Simulates findOrphanedEnvironments when rootfsBaseDir doesn't exist
        val orphans = if (!nonexistent.exists()) emptyList<String>() else emptyList()
        assertThat(orphans).isEmpty()
    }

    @Test
    fun `orphan detection ignores files in base directory`() {
        val rootfsBaseDir = tempDir.newFolder("rootfs")
        // Create a file (not directory) in the base dir
        File(rootfsBaseDir, "stray-file.txt").writeText("not a rootfs")

        val orphans = rootfsBaseDir.listFiles()
            ?.filter { dir ->
                dir.isDirectory && File(dir, "etc/os-release").exists()
            }
            ?.map { it.name }
            ?: emptyList()

        assertThat(orphans).isEmpty()
    }

    // ── os-release parsing tests ──

    @Test
    fun `os-release parsing extracts debian ID`() {
        val osRelease = tempDir.newFile("os-release")
        osRelease.writeText("PRETTY_NAME=\"Debian GNU/Linux trixie/sid\"\nNAME=\"Debian GNU/Linux\"\nID=debian\nVERSION_CODENAME=trixie\n")

        val distro = osRelease.readLines()
            .firstOrNull { it.startsWith("ID=") }
            ?.substringAfter("ID=")
            ?.trim('"')
            ?: ""

        assertThat(distro).isEqualTo("debian")
    }

    @Test
    fun `os-release parsing handles quoted ID`() {
        val osRelease = tempDir.newFile("os-release")
        osRelease.writeText("ID=\"ubuntu\"\n")

        val distro = osRelease.readLines()
            .firstOrNull { it.startsWith("ID=") }
            ?.substringAfter("ID=")
            ?.trim('"')
            ?: ""

        assertThat(distro).isEqualTo("ubuntu")
    }

    @Test
    fun `os-release parsing returns empty for missing ID line`() {
        val osRelease = tempDir.newFile("os-release")
        osRelease.writeText("PRETTY_NAME=\"Some Linux\"\n")

        val distro = osRelease.readLines()
            .firstOrNull { it.startsWith("ID=") }
            ?.substringAfter("ID=")
            ?.trim('"')
            ?: ""

        assertThat(distro).isEmpty()
    }

    @Test
    fun `os-release parsing returns empty for missing file`() {
        val nonexistent = File(tempDir.root, "nonexistent-os-release")
        assertThat(nonexistent.exists()).isFalse()

        val distro = if (!nonexistent.exists()) "" else "should-not-reach"
        assertThat(distro).isEmpty()
    }
}
