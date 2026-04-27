package nu.shell.wldroid.virgl

/** Observable lifecycle state of a VirGL server session. */
enum class VirglState {
    IDLE,
    DETECTING_GPU,
    STARTING,
    RUNNING,
    UNHEALTHY,
    STOPPING,
    STOPPED,
    ERROR,
}
