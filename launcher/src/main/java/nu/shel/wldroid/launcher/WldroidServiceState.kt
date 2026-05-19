package nu.shel.wldroid.launcher

import nu.shel.wldroid.virgl.GpuMode

/**
 * Phases of environment setup that the service progresses through.
 */
enum class SetupPhase(val displayName: String) {
    DOWNLOADING("Downloading rootfs"),
    EXTRACTING("Extracting rootfs"),
    CONFIGURING("Configuring environment"),
    INSTALLING_PACKAGES("Installing packages"),
    LAUNCHING("Launching desktop"),
}

/**
 * State of the [WldroidService] foreground service.
 *
 * Follows the same sealed-class pattern as [DesktopLauncherState],
 * with [isActive] and [isTerminal] convenience properties.
 */
sealed class WldroidServiceState {

    /** Whether the service is actively doing work (setup or session). */
    open val isActive: Boolean get() = false

    /** Whether this state is terminal and the service may stop. */
    open val isTerminal: Boolean get() = false

    /** Service is idle — no setup or session in progress. */
    data object Inactive : WldroidServiceState() {
        override val isTerminal: Boolean get() = true
    }

    /**
     * Environment setup is in progress.
     *
     * @param phase current setup phase
     * @param progress fractional progress in `0f..1f`, or `null` for indeterminate
     * @param envName human-readable environment name
     */
    data class Setup(
        val phase: SetupPhase,
        val progress: Float? = null,
        val envName: String,
    ) : WldroidServiceState() {
        override val isActive: Boolean get() = true
    }

    /**
     * A desktop session is running.
     *
     * @param envName human-readable environment name
     * @param gpuMode the GPU rendering mode in use
     */
    data class SessionActive(
        val envName: String,
        val gpuMode: GpuMode,
    ) : WldroidServiceState() {
        override val isActive: Boolean get() = true
    }

    /**
     * A recoverable error occurred.
     *
     * @param message user-visible error description
     * @param phase the setup phase that failed, if applicable
     * @param canRetry whether the operation can be retried
     */
    data class Error(
        val message: String,
        val phase: SetupPhase? = null,
        val canRetry: Boolean = true,
    ) : WldroidServiceState() {
        override val isTerminal: Boolean get() = !canRetry
    }
}
