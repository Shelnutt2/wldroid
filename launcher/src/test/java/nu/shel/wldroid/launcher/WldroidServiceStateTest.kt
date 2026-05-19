package nu.shel.wldroid.launcher

import com.google.common.truth.Truth.assertThat
import nu.shel.wldroid.virgl.GpuMode
import org.junit.Test

class WldroidServiceStateTest {

    // --- isActive / isTerminal ---

    @Test fun `Inactive is terminal and not active`() {
        assertThat(WldroidServiceState.Inactive.isTerminal).isTrue()
        assertThat(WldroidServiceState.Inactive.isActive).isFalse()
    }

    @Test fun `Setup is active and not terminal`() {
        val state = WldroidServiceState.Setup(
            phase = SetupPhase.DOWNLOADING,
            envName = "test",
        )
        assertThat(state.isActive).isTrue()
        assertThat(state.isTerminal).isFalse()
    }

    @Test fun `SessionActive is active and not terminal`() {
        val state = WldroidServiceState.SessionActive(
            envName = "test",
            gpuMode = GpuMode.SOFTWARE,
        )
        assertThat(state.isActive).isTrue()
        assertThat(state.isTerminal).isFalse()
    }

    @Test fun `Error with canRetry true is not terminal`() {
        val state = WldroidServiceState.Error(
            message = "oops",
            canRetry = true,
        )
        assertThat(state.isTerminal).isFalse()
        assertThat(state.isActive).isFalse()
    }

    @Test fun `Error with canRetry false is terminal`() {
        val state = WldroidServiceState.Error(
            message = "fatal",
            canRetry = false,
        )
        assertThat(state.isTerminal).isTrue()
        assertThat(state.isActive).isFalse()
    }

    // --- SetupPhase enum ---

    @Test fun `SetupPhase has all expected values`() {
        val phases = SetupPhase.entries
        assertThat(phases.map { it.name }).containsExactly(
            "DOWNLOADING", "EXTRACTING", "CONFIGURING",
            "INSTALLING_PACKAGES", "LAUNCHING",
        )
    }

    @Test fun `SetupPhase displayNames are non-empty`() {
        SetupPhase.entries.forEach { phase ->
            assertThat(phase.displayName).isNotEmpty()
        }
    }

    // --- Setup state properties ---

    @Test fun `Setup preserves progress and envName`() {
        val state = WldroidServiceState.Setup(
            phase = SetupPhase.EXTRACTING,
            progress = 0.75f,
            envName = "Debian Trixie",
        )
        assertThat(state.phase).isEqualTo(SetupPhase.EXTRACTING)
        assertThat(state.progress).isWithin(0.001f).of(0.75f)
        assertThat(state.envName).isEqualTo("Debian Trixie")
    }

    @Test fun `Setup progress defaults to null`() {
        val state = WldroidServiceState.Setup(
            phase = SetupPhase.DOWNLOADING,
            envName = "env",
        )
        assertThat(state.progress).isNull()
    }

    // --- Error state properties ---

    @Test fun `Error preserves message, phase, and canRetry`() {
        val state = WldroidServiceState.Error(
            message = "network failure",
            phase = SetupPhase.DOWNLOADING,
            canRetry = true,
        )
        assertThat(state.message).isEqualTo("network failure")
        assertThat(state.phase).isEqualTo(SetupPhase.DOWNLOADING)
        assertThat(state.canRetry).isTrue()
    }

    @Test fun `Error defaults phase to null and canRetry to true`() {
        val state = WldroidServiceState.Error(message = "unknown")
        assertThat(state.phase).isNull()
        assertThat(state.canRetry).isTrue()
    }

    // --- SessionActive properties ---

    @Test fun `SessionActive preserves envName and gpuMode`() {
        val state = WldroidServiceState.SessionActive(
            envName = "Debian",
            gpuMode = GpuMode.TURNIP_DIRECT,
        )
        assertThat(state.envName).isEqualTo("Debian")
        assertThat(state.gpuMode).isEqualTo(GpuMode.TURNIP_DIRECT)
    }
}
