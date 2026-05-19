package nu.shel.wldroid.ui

/**
 * Represents the stages of environment setup (download, extract, install, launch).
 * Used by [SetupOverlay] to show the appropriate progress UI.
 */
sealed class SetupState {
    /** No setup in progress. */
    object Idle : SetupState()

    /** Downloading the rootfs tarball. [progress] is 0.0–1.0, or -1 if indeterminate. */
    data class Downloading(val progress: Float, val message: String = "") : SetupState()

    /** Extracting the rootfs tarball. [progress] is 0.0–1.0, or -1 if indeterminate. */
    data class Extracting(val progress: Float, val message: String = "") : SetupState()

    /** Installing packages or configuring the environment. */
    data class Installing(val message: String = "") : SetupState()

    /** Launching the compositor and/or desktop application. */
    data class Launching(val message: String = "") : SetupState()

    /** Setup complete — compositor is running. */
    object Running : SetupState()

    /** An error occurred during setup. */
    data class Error(val message: String, val canRetry: Boolean = true) : SetupState()

    /** Whether this state represents an active (non-terminal) operation. */
    val isActive: Boolean
        get() = this is Downloading || this is Extracting || this is Installing || this is Launching

    /** Whether this state represents a completed or idle state. */
    val isTerminal: Boolean
        get() = this is Idle || this is Running || this is Error
}

/**
 * Status of a single step in the setup stepper.
 */
enum class StepStatus { PENDING, ACTIVE, COMPLETED, ERROR }

/**
 * A single step shown in the [SetupScreen] stepper.
 *
 * @param label Human-readable step name (e.g. "Downloading environment")
 * @param status Current visual status of this step
 * @param progress Fractional progress 0.0–1.0, or -1f for indeterminate/not-applicable
 * @param detail Extra text shown when the step is active (e.g. "48.2 MB — 72%")
 */
data class SetupStep(
    val label: String,
    val status: StepStatus,
    val progress: Float = -1f,
    val detail: String = "",
)

