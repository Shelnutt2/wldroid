package nu.shel.wldroid.proot

/**
 * Represents a single rootfs environment managed by proot.
 *
 * Each environment corresponds to a directory on disk containing a Linux
 * root filesystem that can be entered via proot.
 *
 * @property id Unique identifier for this environment
 * @property name Human-readable display name
 * @property rootfsPath Absolute path to the rootfs directory on disk
 * @property distro The distro template this environment was created from
 * @property createdAt Timestamp (epoch millis) when this environment was created
 * @property sizeBytes Approximate disk usage in bytes (may be stale)
 * @property lastUsedAt Timestamp of last proot invocation, or null if never used
 * @property status Current status of the environment
 */
data class RootfsEnvironment(
    val id: String,
    val name: String = id,
    val rootfsPath: String,
    val distro: String = "",
    val createdAt: Long,
    val sizeBytes: Long = 0,
    val lastUsedAt: Long? = null,
    val status: RootfsStatus = RootfsStatus.READY,
)

/**
 * Possible states of a rootfs environment lifecycle.
 */
enum class RootfsStatus {
    /** Environment is ready for use. */
    READY,

    /** Rootfs tarball is being downloaded. */
    DOWNLOADING,

    /** Rootfs tarball is being extracted. */
    EXTRACTING,

    /** Post-extraction setup is in progress. */
    INSTALLING,

    /** An error occurred during creation or usage. */
    ERROR,
}

/**
 * Wraps [RootfsStatus] with an optional progress value for download/extraction phases.
 *
 * @property status The current status
 * @property progress Progress as 0.0–1.0, or -1f for indeterminate
 */
data class RootfsProgress(
    val status: RootfsStatus,
    val progress: Float = -1f,
)
