package nu.shel.wldroid.ui

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SetupStateTest {

    @Test
    fun `Idle is not active and is terminal`() {
        val state: SetupState = SetupState.Idle
        assertThat(state.isActive).isFalse()
        assertThat(state.isTerminal).isTrue()
    }

    @Test
    fun `Running is not active and is terminal`() {
        val state: SetupState = SetupState.Running
        assertThat(state.isActive).isFalse()
        assertThat(state.isTerminal).isTrue()
    }

    @Test
    fun `Downloading is active and not terminal`() {
        val state = SetupState.Downloading(progress = 0.5f, message = "50%")
        assertThat(state.isActive).isTrue()
        assertThat(state.isTerminal).isFalse()
        assertThat(state.progress).isEqualTo(0.5f)
        assertThat(state.message).isEqualTo("50%")
    }

    @Test
    fun `Downloading with indeterminate progress`() {
        val state = SetupState.Downloading(progress = -1f)
        assertThat(state.progress).isEqualTo(-1f)
        assertThat(state.message).isEmpty()
    }

    @Test
    fun `Extracting is active and not terminal`() {
        val state = SetupState.Extracting(progress = 0.75f, message = "rootfs")
        assertThat(state.isActive).isTrue()
        assertThat(state.isTerminal).isFalse()
        assertThat(state.progress).isEqualTo(0.75f)
        assertThat(state.message).isEqualTo("rootfs")
    }

    @Test
    fun `Installing is active`() {
        val state = SetupState.Installing(message = "Configuring packages")
        assertThat(state.isActive).isTrue()
        assertThat(state.isTerminal).isFalse()
        assertThat(state.message).isEqualTo("Configuring packages")
    }

    @Test
    fun `Installing with default message`() {
        val state = SetupState.Installing()
        assertThat(state.message).isEmpty()
    }

    @Test
    fun `Launching is active`() {
        val state = SetupState.Launching(message = "Starting compositor")
        assertThat(state.isActive).isTrue()
        assertThat(state.isTerminal).isFalse()
    }

    @Test
    fun `Error is terminal and not active`() {
        val state = SetupState.Error(message = "Download failed", canRetry = true)
        assertThat(state.isActive).isFalse()
        assertThat(state.isTerminal).isTrue()
        assertThat(state.message).isEqualTo("Download failed")
        assertThat(state.canRetry).isTrue()
    }

    @Test
    fun `Error with canRetry false`() {
        val state = SetupState.Error(message = "Fatal error", canRetry = false)
        assertThat(state.canRetry).isFalse()
    }

    @Test
    fun `progress values are preserved correctly`() {
        assertThat(SetupState.Downloading(0.0f).progress).isEqualTo(0.0f)
        assertThat(SetupState.Downloading(1.0f).progress).isEqualTo(1.0f)
        assertThat(SetupState.Extracting(0.0f).progress).isEqualTo(0.0f)
        assertThat(SetupState.Extracting(1.0f).progress).isEqualTo(1.0f)
    }

    @Test
    fun `data class equality works`() {
        assertThat(SetupState.Downloading(0.5f, "test"))
            .isEqualTo(SetupState.Downloading(0.5f, "test"))
        assertThat(SetupState.Error("msg", true))
            .isEqualTo(SetupState.Error("msg", true))
    }

    @Test
    fun `object identity works`() {
        assertThat(SetupState.Idle).isSameInstanceAs(SetupState.Idle)
        assertThat(SetupState.Running).isSameInstanceAs(SetupState.Running)
    }
}

class StepStatusTest {

    @Test fun `StepStatus has all expected values`() {
        val values = StepStatus.entries
        assertThat(values.map { it.name }).containsExactly(
            "PENDING", "ACTIVE", "COMPLETED", "ERROR",
        )
    }
}

class SetupStepTest {

    @Test fun `SetupStep construction with defaults`() {
        val step = SetupStep(label = "Download", status = StepStatus.PENDING)
        assertThat(step.label).isEqualTo("Download")
        assertThat(step.status).isEqualTo(StepStatus.PENDING)
        assertThat(step.progress).isEqualTo(-1f)
        assertThat(step.detail).isEmpty()
    }

    @Test fun `SetupStep preserves all fields`() {
        val step = SetupStep(
            label = "Extracting",
            status = StepStatus.ACTIVE,
            progress = 0.42f,
            detail = "48.2 MB — 72%",
        )
        assertThat(step.label).isEqualTo("Extracting")
        assertThat(step.status).isEqualTo(StepStatus.ACTIVE)
        assertThat(step.progress).isWithin(0.001f).of(0.42f)
        assertThat(step.detail).isEqualTo("48.2 MB — 72%")
    }

    @Test fun `SetupStep data class equality`() {
        val a = SetupStep("Download", StepStatus.COMPLETED, 1.0f, "Done")
        val b = SetupStep("Download", StepStatus.COMPLETED, 1.0f, "Done")
        assertThat(a).isEqualTo(b)
    }

    @Test fun `SetupStep with error status`() {
        val step = SetupStep(
            label = "Install packages",
            status = StepStatus.ERROR,
            detail = "apt failed",
        )
        assertThat(step.status).isEqualTo(StepStatus.ERROR)
        assertThat(step.detail).isEqualTo("apt failed")
        assertThat(step.progress).isEqualTo(-1f)
    }
}

