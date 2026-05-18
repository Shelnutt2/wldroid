package nu.shel.wldroid.compositor

enum class CompositorState {
    IDLE,
    STARTING,
    RUNNING,
    PAUSED,
    STOPPING,
    STOPPED,
    ERROR
}
