package nu.shel.wldroid.compositor

enum class CompositorState {
    IDLE,
    STARTING,
    RUNNING,
    PAUSED,
    STOPPING,
    STOPPED,
    ERROR;

    /** True when the compositor is active enough to have connected Wayland clients. */
    val isRunning: Boolean get() = this == RUNNING || this == PAUSED
}
