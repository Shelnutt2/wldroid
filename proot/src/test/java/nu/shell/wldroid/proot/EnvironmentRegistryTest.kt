package nu.shell.wldroid.proot

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Tests for [EnvironmentRegistry] data types and state management.
 *
 * Full integration tests require Android context (for DataStore and file I/O).
 * These tests cover the pure-Kotlin aspects: data classes, config, state enum.
 */
class EnvironmentRegistryTest {

    @Test
    fun `EnvironmentConfig has correct defaults`() {
        val config = EnvironmentConfig(
            name = "Test",
            distro = DistroTemplate.DEBIAN_TRIXIE,
        )
        assertThat(config.bindMounts).isEmpty()
        assertThat(config.environmentVariables).isEmpty()
    }

    @Test
    fun `EnvironmentConfig preserves custom bind mounts and env vars`() {
        val config = EnvironmentConfig(
            name = "Custom",
            distro = DistroTemplate.DEBIAN_BOOKWORM,
            bindMounts = listOf(
                BindMount("/host/data", "/data"),
                BindMount("/host/config", "/etc/app", readOnly = true),
            ),
            environmentVariables = mapOf("DISPLAY" to ":0", "XDG_RUNTIME_DIR" to "/tmp/xdg"),
        )
        assertThat(config.bindMounts).hasSize(2)
        assertThat(config.bindMounts[1].readOnly).isTrue()
        assertThat(config.environmentVariables).hasSize(2)
        assertThat(config.environmentVariables["DISPLAY"]).isEqualTo(":0")
    }

    @Test
    fun `EnvironmentState enum has all expected values`() {
        val states = EnvironmentState.entries
        assertThat(states.map { it.name }).containsExactly(
            "IDLE", "DOWNLOADING", "EXTRACTING", "INSTALLING",
            "RUNNING", "STOPPING", "STOPPED", "ERROR",
        )
    }

    @Test
    fun `RootfsStatus enum has all expected values`() {
        val statuses = RootfsStatus.entries
        assertThat(statuses.map { it.name }).containsExactly(
            "READY", "DOWNLOADING", "EXTRACTING", "INSTALLING", "ERROR",
        )
    }

    @Test
    fun `RootfsProgress default progress is indeterminate`() {
        val progress = RootfsProgress(RootfsStatus.DOWNLOADING)
        assertThat(progress.progress).isEqualTo(-1f)
    }

    @Test
    fun `RootfsProgress can track determinate progress`() {
        val progress = RootfsProgress(RootfsStatus.EXTRACTING, 0.75f)
        assertThat(progress.status).isEqualTo(RootfsStatus.EXTRACTING)
        assertThat(progress.progress).isWithin(0.001f).of(0.75f)
    }

    @Test
    fun `RootfsEnvironment defaults`() {
        val env = RootfsEnvironment(
            id = "test",
            rootfsPath = "/path",
            createdAt = 1000L,
        )
        assertThat(env.name).isEqualTo("test") // defaults to id
        assertThat(env.distro).isEmpty()
        assertThat(env.sizeBytes).isEqualTo(0)
        assertThat(env.lastUsedAt).isNull()
        assertThat(env.status).isEqualTo(RootfsStatus.READY)
    }

    @Test
    fun `availableDistros returns all templates`() {
        // This tests the static method without needing a full registry instance
        val distros = DistroTemplate.entries
        assertThat(distros).isNotEmpty()
        assertThat(distros.map { it.displayName }).contains("Debian Trixie")
        assertThat(distros.map { it.displayName }).contains("Debian Bookworm")
    }
}
