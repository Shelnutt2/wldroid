package nu.shel.wldroid.launcher

import android.view.Surface
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import nu.shel.wldroid.compositor.CompositorSession
import nu.shel.wldroid.compositor.CompositorState
import nu.shel.wldroid.proot.RootfsEnvironment

/**
 * Unified lifecycle manager for a desktop session.
 *
 * Wraps [CompositorSession] and [DesktopLauncher] to provide a single
 * entry point for starting, stopping, and restarting a desktop session.
 * Downstream apps should use this class instead of orchestrating the
 * compositor and launcher independently.
 *
 * Typical usage:
 * ```kotlin
 * val session = DesktopSession(compositorSession, launcher)
 *
 * // Start the session when the Surface is ready.
 * session.start(surface, environment, command, scope)
 *
 * // Stop the session on Activity destroy or user action.
 * session.stop()
 *
 * // Restart after a failure or configuration change.
 * session.restart(surface, environment, command, scope)
 * ```
 *
 * [stop] deterministically tears down all resources in the correct order:
 * 1. Stops the desktop launcher (kills proot process, stops VirGL, cleans stale files).
 * 2. Stops the compositor (shuts down the Wayland server, cleans sockets).
 *
 * The session can be restarted after [stop] completes.
 */
class DesktopSession(
    private val compositorSession: CompositorSession,
    private val launcher: DesktopLauncher,
) {
    private val _state = MutableStateFlow(DesktopSessionState.IDLE)

    /** Observable session state. */
    val state: StateFlow<DesktopSessionState> = _state.asStateFlow()

    /** Whether the session is currently running (started and not yet stopped/errored). */
    val isRunning: Boolean
        get() = _state.value == DesktopSessionState.RUNNING

    /**
     * Start the desktop session.
     *
     * Starts the compositor with the given [surface], then launches the
     * desktop app inside proot using [DesktopLauncher.launch].
     *
     * @param surface Android Surface for compositor rendering.
     * @param environment The proot rootfs environment.
     * @param command The command to run inside proot (e.g. VS Code).
     * @param scope Coroutine scope for the launch pipeline.
     * @param requiredPackages Packages to install before launching.
     */
    fun start(
        surface: Surface,
        environment: RootfsEnvironment,
        command: List<String>,
        scope: CoroutineScope,
        requiredPackages: List<String> = emptyList(),
    ) {
        val currentState = _state.value
        if (currentState != DesktopSessionState.IDLE && currentState != DesktopSessionState.STOPPED) {
            android.util.Log.w("DesktopSession", "start() called in state $currentState — ignoring")
            return
        }
        _state.value = DesktopSessionState.STARTING
        try {
            compositorSession.start(surface)
        } catch (e: Exception) {
            _state.value = DesktopSessionState.ERROR
            throw e
        }
        _state.value = DesktopSessionState.RUNNING
        launcher.launch(environment, command, requiredPackages, scope)
    }

    /**
     * Stop the entire desktop session deterministically.
     *
     * Tears down resources in the correct order:
     * 1. Launcher (proot process, VirGL server, stale file cleanup).
     * 2. Compositor (native Wayland server, wayland sockets).
     *
     * Safe to call multiple times; subsequent calls are no-ops if
     * the compositor is already stopped.
     */
    suspend fun stop() {
        _state.value = DesktopSessionState.STOPPING
        try {
            // Stop launcher first: kills proot, stops VirGL, cleans files.
            launcher.stop()
        } finally {
            // Always stop compositor even if launcher teardown fails.
            try {
                val compositorState = compositorSession.state.value
                if (compositorState != CompositorState.STOPPED && compositorState != CompositorState.IDLE) {
                    compositorSession.stopAsync()
                }
            } catch (_: Exception) {
                // Best-effort compositor shutdown.
            }
            _state.value = DesktopSessionState.STOPPED
        }
    }

    /**
     * Stop the current session and start a new one.
     *
     * Convenience method for retry flows. Equivalent to calling
     * [stop] followed by [start].
     */
    suspend fun restart(
        surface: Surface,
        environment: RootfsEnvironment,
        command: List<String>,
        scope: CoroutineScope,
        requiredPackages: List<String> = emptyList(),
    ) {
        stop()
        start(surface, environment, command, scope, requiredPackages)
    }
}

/**
 * Lifecycle states for a [DesktopSession].
 *
 * This is a coarse-grained state for the overall session. Use
 * [CompositorSession.state] and [DesktopLauncher.state] for
 * fine-grained compositor and launcher state respectively.
 */
enum class DesktopSessionState {
    /** No session is active. */
    IDLE,

    /** The session is starting (compositor + launcher pipeline). */
    STARTING,

    /** The session is running. */
    RUNNING,

    /** The session is stopping (tearing down all resources). */
    STOPPING,

    /** The session has stopped. Can be restarted via [DesktopSession.start]. */
    STOPPED,

    /** The session encountered an error during startup. */
    ERROR,
}
