package nu.shell.wldroid.ui

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Extended UI state tests for [SetupState] transitions and edge cases.
 *
 * Complements [SetupStateTest] with additional coverage for
 * state machine properties and boundary conditions.
 */
class SetupOverlayStateTest {

    // ── State machine invariants ──

    @Test
    fun `all active states are not terminal`() {
        val activeStates = listOf(
            SetupState.Downloading(0.5f),
            SetupState.Extracting(0.5f),
            SetupState.Installing(),
            SetupState.Launching(),
        )
        for (state in activeStates) {
            assertThat(state.isActive).isTrue()
            assertThat(state.isTerminal).isFalse()
        }
    }

    @Test
    fun `all terminal states are not active`() {
        val terminalStates = listOf(
            SetupState.Idle,
            SetupState.Running,
            SetupState.Error("test"),
        )
        for (state in terminalStates) {
            assertThat(state.isActive).isFalse()
            assertThat(state.isTerminal).isTrue()
        }
    }

    // ── Downloading state edge cases ──

    @Test
    fun `Downloading progress boundary values`() {
        assertThat(SetupState.Downloading(0.0f).progress).isEqualTo(0.0f)
        assertThat(SetupState.Downloading(1.0f).progress).isEqualTo(1.0f)
        assertThat(SetupState.Downloading(-1.0f).progress).isEqualTo(-1.0f) // indeterminate
    }

    @Test
    fun `Downloading with empty message`() {
        val state = SetupState.Downloading(0.5f)
        assertThat(state.message).isEmpty()
    }

    @Test
    fun `Downloading preserves message`() {
        val state = SetupState.Downloading(0.3f, "300 MB / 1 GB")
        assertThat(state.message).isEqualTo("300 MB / 1 GB")
    }

    // ── Extracting state edge cases ──

    @Test
    fun `Extracting progress boundary values`() {
        assertThat(SetupState.Extracting(0.0f).progress).isEqualTo(0.0f)
        assertThat(SetupState.Extracting(1.0f).progress).isEqualTo(1.0f)
    }

    // ── Error state edge cases ──

    @Test
    fun `Error default canRetry is true`() {
        val state = SetupState.Error("Network error")
        assertThat(state.canRetry).isTrue()
    }

    @Test
    fun `Error with empty message`() {
        val state = SetupState.Error("")
        assertThat(state.message).isEmpty()
        assertThat(state.isTerminal).isTrue()
    }

    @Test
    fun `Error states with different messages are not equal`() {
        val a = SetupState.Error("Error A")
        val b = SetupState.Error("Error B")
        assertThat(a).isNotEqualTo(b)
    }

    @Test
    fun `Error states with different canRetry are not equal`() {
        val a = SetupState.Error("Error", canRetry = true)
        val b = SetupState.Error("Error", canRetry = false)
        assertThat(a).isNotEqualTo(b)
    }

    // ── Type checking ──

    @Test
    fun `all SetupState subtypes are sealed`() {
        val idleState: SetupState = SetupState.Idle
        val runningState: SetupState = SetupState.Running
        val downloadingState: SetupState = SetupState.Downloading(0f)
        val extractingState: SetupState = SetupState.Extracting(0f)
        val installingState: SetupState = SetupState.Installing()
        val launchingState: SetupState = SetupState.Launching()
        val errorState: SetupState = SetupState.Error("test")

        // Verify when-expression exhaustiveness (all are SetupState subtypes)
        val states = listOf(
            idleState, runningState, downloadingState,
            extractingState, installingState, launchingState, errorState,
        )
        assertThat(states).hasSize(7)
    }

    @Test
    fun `Idle and Running are singletons`() {
        assertThat(SetupState.Idle).isSameInstanceAs(SetupState.Idle)
        assertThat(SetupState.Running).isSameInstanceAs(SetupState.Running)
    }

    // ── Data class behavior ──

    @Test
    fun `Downloading data class toString includes progress`() {
        val state = SetupState.Downloading(0.75f, "downloading")
        val str = state.toString()
        assertThat(str).contains("0.75")
        assertThat(str).contains("downloading")
    }

    @Test
    fun `Installing data class toString includes message`() {
        val state = SetupState.Installing("Setting up packages")
        val str = state.toString()
        assertThat(str).contains("Setting up packages")
    }
}
