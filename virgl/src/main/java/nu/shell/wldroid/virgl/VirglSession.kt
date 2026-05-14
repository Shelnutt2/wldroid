package nu.shell.wldroid.virgl

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * High-level VirGL server session with observable state.
 *
 * Wraps [VirglServerManager] with lifecycle management, automatic
 * GPU detection, health monitoring, and a [StateFlow]-based API
 * for observing session state from UI or other consumers.
 *
 * Usage:
 * ```kotlin
 * val session = VirglSession(config)
 * session.state.collect { state -> /* update UI */ }
 * session.start()
 * // ... later ...
 * session.stop()
 * ```
 */
class VirglSession(
    private val config: VirglConfig,
    private val serverManager: VirglServerManager = VirglServerManager(config),
    private val gpuDetector: GpuCapabilityDetector? = null,
) {
    private val _state = MutableStateFlow(VirglState.IDLE)

    /** Observable session state. */
    val state: StateFlow<VirglState> = _state.asStateFlow()

    private val _detectedGpuMode = MutableStateFlow(config.gpuMode)

    /** The GPU mode selected after detection (or the configured mode if not AUTO). */
    val detectedGpuMode: StateFlow<GpuMode> = _detectedGpuMode.asStateFlow()

    private var sessionScope: CoroutineScope? = null

    /**
     * Start the VirGL server session.
     *
     * If [VirglConfig.gpuMode] is [GpuMode.AUTO] and a [GpuCapabilityDetector]
     * was provided, the best mode is detected first. Modes that don't require
     * a VirGL server (e.g. [GpuMode.SOFTWARE], [GpuMode.TURNIP_DIRECT]) will
     * skip server startup.
     *
     * This method is a no-op if the session is already [VirglState.STARTING]
     * or [VirglState.RUNNING]. It can be called again after [stop] (from
     * [VirglState.STOPPED]) to restart the session.
     */
    suspend fun start() {
        // No-op if already starting or running.
        if (_state.value == VirglState.STARTING || _state.value == VirglState.RUNNING) return

        // Resolve the effective GPU mode.
        val effectiveMode = resolveGpuMode()
        _detectedGpuMode.value = effectiveMode

        if (!effectiveMode.requiresVirglServer) {
            _state.value = VirglState.RUNNING
            return
        }

        // Cancel any lingering scope from a previous partial failure.
        sessionScope?.cancel()
        sessionScope = null

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        sessionScope = scope

        _state.value = VirglState.STARTING
        val started = serverManager.start(effectiveMode, scope)

        if (started) {
            _state.value = VirglState.RUNNING
            // Launch periodic health check.
            scope.launch {
                while (isActive) {
                    delay(HEALTH_CHECK_INTERVAL_MS)
                    if (!serverManager.isRunning) {
                        _state.value = VirglState.UNHEALTHY
                    }
                }
            }
        } else {
            _state.value = VirglState.ERROR
            scope.cancel()
            sessionScope = null
        }
    }

    /**
     * Stop the VirGL server session.
     *
     * No-op if the session is already [VirglState.IDLE] or [VirglState.STOPPED].
     */
    suspend fun stop() {
        // No-op if already idle or stopped.
        if (_state.value == VirglState.IDLE || _state.value == VirglState.STOPPED) return

        _state.value = VirglState.STOPPING
        sessionScope?.cancel()
        sessionScope = null
        serverManager.stop()
        _state.value = VirglState.STOPPED
    }

    /** Quick health check — returns `true` if the session is running and healthy. */
    fun isHealthy(): Boolean =
        _state.value == VirglState.RUNNING && (
            !_detectedGpuMode.value.requiresVirglServer || serverManager.isRunning
        )

    private fun resolveGpuMode(): GpuMode {
        if (config.gpuMode != GpuMode.AUTO) {
            // Validate that TURNIP_DIRECT is actually available — KGSL must be accessible.
            if (config.gpuMode == GpuMode.TURNIP_DIRECT && gpuDetector != null && !gpuDetector.isKgslAccessible()) {
                // KGSL not accessible; fall back to auto-detect instead of using invalid override.
                _state.value = VirglState.DETECTING_GPU
                return gpuDetector.detectBestGpuMode()
            }
            return config.gpuMode
        }
        _state.value = VirglState.DETECTING_GPU
        return gpuDetector?.detectBestGpuMode() ?: GpuMode.VIRGL_GLES
    }

    companion object {
        internal const val HEALTH_CHECK_INTERVAL_MS = 5_000L
    }
}
