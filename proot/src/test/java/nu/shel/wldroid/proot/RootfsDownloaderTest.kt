package nu.shel.wldroid.proot

import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Unit tests for [RootfsDownloader].
 *
 * Tests SHA-256 verification, cache file path construction, and
 * cached tarball detection. Network download tests are omitted as they
 * require a real HTTP server (use instrumented tests for those).
 */
class RootfsDownloaderTest {

    @get:Rule
    val tempDir = TemporaryFolder()

    private lateinit var downloader: RootfsDownloader

    @Before
    fun setUp() {
        downloader = RootfsDownloader(tempDir.root)
    }

    // ── SHA-256 verification ──

    @Test
    fun `verifySha256 returns true for correct hash`() {
        val file = tempDir.newFile("test.bin")
        val content = "Hello, WLDroid!"
        file.writeText(content)
        // SHA-256 of "Hello, WLDroid!"
        val expectedHash = sha256Hex(content.toByteArray())
        assertThat(downloader.verifySha256(file, expectedHash)).isTrue()
    }

    @Test
    fun `verifySha256 returns false for wrong hash`() {
        val file = tempDir.newFile("test.bin")
        file.writeText("Hello, WLDroid!")
        assertThat(downloader.verifySha256(file, "0000000000000000000000000000000000000000000000000000000000000000")).isFalse()
    }

    @Test
    fun `verifySha256 is case insensitive`() {
        val file = tempDir.newFile("test.bin")
        val content = "test data"
        file.writeText(content)
        val hash = sha256Hex(content.toByteArray())
        assertThat(downloader.verifySha256(file, hash.uppercase())).isTrue()
        assertThat(downloader.verifySha256(file, hash.lowercase())).isTrue()
    }

    @Test
    fun `verifySha256 handles empty file`() {
        val file = tempDir.newFile("empty.bin")
        // SHA-256 of empty input
        val emptyHash = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
        assertThat(downloader.verifySha256(file, emptyHash)).isTrue()
    }

    @Test
    fun `verifySha256 handles large file`() {
        val file = tempDir.newFile("large.bin")
        val data = ByteArray(100_000) { (it % 256).toByte() }
        file.writeBytes(data)
        val hash = sha256Hex(data)
        assertThat(downloader.verifySha256(file, hash)).isTrue()
    }

    // ── Cache file path construction ──

    @Test
    fun `getCacheFile extracts filename from URL`() {
        val url = "https://github.com/example/releases/download/v1.0/debian-bookworm-arm64.tar.xz"
        val cacheFile = downloader.getCacheFile(url)
        assertThat(cacheFile.name).isEqualTo("debian-bookworm-arm64.tar.xz")
        assertThat(cacheFile.parentFile).isEqualTo(tempDir.root)
    }

    @Test
    fun `getCacheFile handles URL with query parameters`() {
        // substringAfterLast('/') captures the query params too, which is fine for cache keys
        val url = "https://example.com/files/rootfs.tar.xz?token=abc"
        val cacheFile = downloader.getCacheFile(url)
        assertThat(cacheFile.name).isEqualTo("rootfs.tar.xz?token=abc")
    }

    @Test
    fun `getCacheFile returns file in cache directory`() {
        val url = "https://example.com/rootfs.tar.xz"
        val cacheFile = downloader.getCacheFile(url)
        assertThat(cacheFile.absolutePath).startsWith(tempDir.root.absolutePath)
    }

    // ── Cached tarball detection ──

    @Test
    fun `getCachedTarball returns null for non-existent file`() {
        val url = "https://example.com/nonexistent.tar.xz"
        assertThat(downloader.getCachedTarball(url)).isNull()
    }

    @Test
    fun `getCachedTarball returns null for file smaller than min size`() {
        val url = "https://example.com/small.tar.xz"
        val file = File(tempDir.root, "small.tar.xz")
        file.writeText("too small to be a rootfs")
        assertThat(downloader.getCachedTarball(url)).isNull()
    }

    @Test
    fun `getCachedTarball returns file when large enough`() {
        val url = "https://example.com/rootfs.tar.xz"
        val file = File(tempDir.root, "rootfs.tar.xz")
        // Write more than MIN_TARBALL_SIZE (1MB)
        val data = ByteArray(1_100_000) { 0 }
        file.writeBytes(data)
        val cached = downloader.getCachedTarball(url)
        assertThat(cached).isNotNull()
        assertThat(cached!!.name).isEqualTo("rootfs.tar.xz")
    }

    // ── Constructor ──

    @Test
    fun `constructor creates cache directory`() {
        val newDir = File(tempDir.root, "subcache/nested")
        assertThat(newDir.exists()).isFalse()
        RootfsDownloader(newDir)
        assertThat(newDir.exists()).isTrue()
        assertThat(newDir.isDirectory).isTrue()
    }

    // ── Helpers ──

    private fun sha256Hex(data: ByteArray): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        return digest.digest(data).joinToString("") { "%02x".format(it) }
    }
}
