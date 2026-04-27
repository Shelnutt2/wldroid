package nu.shell.wldroid.proot

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ProotConfigTest {

    @Test
    fun `default config uses Debian Trixie`() {
        val config = ProotConfig(prootBinaryPath = "/data/app/lib/libproot.so")
        assertThat(config.defaultDistro).isEqualTo(DistroTemplate.DEBIAN_TRIXIE)
    }

    @Test
    fun `default config enables fakeRoot and link2symlink`() {
        val config = ProotConfig(prootBinaryPath = "/path/to/proot")
        assertThat(config.fakeRoot).isTrue()
        assertThat(config.link2symlink).isTrue()
    }

    @Test
    fun `default config has empty optional paths`() {
        val config = ProotConfig(prootBinaryPath = "/path/to/proot")
        assertThat(config.prootLoaderPath).isEmpty()
        assertThat(config.rootfsBaseDir).isEmpty()
        assertThat(config.cacheDir).isEmpty()
    }

    @Test
    fun `DistroTemplate entries have non-empty URLs and checksums`() {
        for (distro in DistroTemplate.entries) {
            assertThat(distro.displayName).isNotEmpty()
            assertThat(distro.downloadUrl).startsWith("https://")
            assertThat(distro.sha256).hasLength(64)
            assertThat(distro.version).startsWith("v")
        }
    }

    @Test
    fun `DistroTemplate has expected entries`() {
        assertThat(DistroTemplate.entries).hasSize(2)
        assertThat(DistroTemplate.entries.map { it.name })
            .containsExactly("DEBIAN_TRIXIE", "DEBIAN_BOOKWORM")
    }

    @Test
    fun `BindMount defaults to writable`() {
        val mount = BindMount(hostPath = "/host/path", guestPath = "/guest/path")
        assertThat(mount.readOnly).isFalse()
    }

    @Test
    fun `BindMount can be read-only`() {
        val mount = BindMount(
            hostPath = "/host/path",
            guestPath = "/guest/path",
            readOnly = true,
        )
        assertThat(mount.readOnly).isTrue()
    }
}
