package nu.shel.wldroid.proot

/**
 * Rich progress descriptor for environment lifecycle operations.
 *
 * Combines the discrete [EnvironmentState] phase with a continuous
 * [progress] value so callers can display both a phase label and a
 * percentage indicator.
 *
 * @property state The current lifecycle phase.
 * @property progress Progress as 0.0–1.0, or -1f for indeterminate.
 * @property message Optional human-readable detail (e.g. error text).
 */
data class EnvironmentProgress(
    val state: EnvironmentState,
    val progress: Float = -1f,
    val message: String = "",
)
