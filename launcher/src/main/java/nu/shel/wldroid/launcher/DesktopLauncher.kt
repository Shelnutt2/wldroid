package nu.shel.wldroid.launcher

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import nu.shel.wldroid.compositor.CompositorSession
import nu.shel.wldroid.proot.ProotDnsManager
import nu.shel.wldroid.proot.ProotExecutor
import nu.shel.wldroid.proot.RootfsEnvironment
import nu.shel.wldroid.shims.ShimExtractor
import android.system.Os
import nu.shel.wldroid.virgl.GpuMode
import nu.shel.wldroid.virgl.VirglSession
import nu.shel.wldroid.virgl.VirglState
import java.io.File
import java.io.IOException

/**
 * Orchestrates the two-phase desktop app launch pipeline:
 *
 * - **Phase 1 (GPU Setup):** Runs `setup-gpu.sh` inside proot to install Mesa packages,
 *   copy shim libraries to system paths, and create DRI symlinks.
 * - **Phase 2 (App Launch):** Runs `launch-app.sh` inside proot which sets LD_PRELOAD,
 *   LD_LIBRARY_PATH, waits for the Wayland socket, and exec's the user's command.
 */
class DesktopLauncher(
    private val context: Context,
    private val compositorSession: CompositorSession,
    private val virglSession: VirglSession,
    private val shimExtractor: ShimExtractor,
    private val prootExecutor: ProotExecutor,
    private val config: DesktopLauncherConfig,
    private val packageInstaller: PackageInstaller = PackageInstaller(prootExecutor),
    private val gpuSetupManager: GpuSetupManager = GpuSetupManager(prootExecutor),
    private val launchScriptManager: LaunchScriptManager = LaunchScriptManager(),
    private val xwaylandManager: XWaylandManager? = null,
    private val dnsManager: ProotDnsManager? = null,
) {
    private val _state = MutableStateFlow<DesktopLauncherState>(DesktopLauncherState.Idle)
    val state: StateFlow<DesktopLauncherState> = _state.asStateFlow()

    private val _processOutput = MutableSharedFlow<String>(
        replay = 100,
        extraBufferCapacity = 256,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val processOutput: SharedFlow<String> = _processOutput.asSharedFlow()

    private val _gpuMode = MutableStateFlow(GpuMode.AUTO)
    val gpuMode: StateFlow<GpuMode> = _gpuMode.asStateFlow()

    @Volatile
    private var launchJob: Job? = null

    @Volatile
    private var activeProcess: Process? = null

    companion object {
        private const val TAG = "DesktopLauncher"

        /**
         * How often to poll `CompositorSession.refreshClientCount()` after the
         * launch process exits, to determine whether Wayland clients are still
         * connected.
         */
        private const val CLIENT_POLL_INTERVAL_MS = 1_000L

        /**
         * Grace period after the last Wayland client disconnects before
         * transitioning to [DesktopLauncherState.Idle].  This avoids premature
         * teardown when a client temporarily disconnects and reconnects (e.g.
         * Electron respawning a renderer process).
         */
        private const val CLIENT_DRAIN_GRACE_MS = 5_000L
    }

    /** Emit a message to the process output stream (visible in the log panel and logcat). */
    fun emitOutput(message: String) {
        Log.d(TAG, message)
        _processOutput.tryEmit(message)
    }

    fun launch(
        environment: RootfsEnvironment,
        command: List<String>,
        requiredPackages: List<String> = emptyList(),
        scope: CoroutineScope,
    ) {
        launchJob?.cancel()
        _gpuMode.value = GpuMode.AUTO
        launchJob = scope.launch {
            try {
                // 0. Set AHB_REGISTRY_SOCKET so compositor↔VirGL GPU buffer sharing works.
                val ahbSocketPath = config.resolvedAhbSocketPath
                Os.setenv("AHB_REGISTRY_SOCKET", ahbSocketPath, true)
                emitOutput("AHB registry socket: $ahbSocketPath")

                // 1. Wait for compositor socket (with timeout)
                _state.value = DesktopLauncherState.StartingCompositor
                emitOutput("Starting compositor...")
                val socketPath = withTimeoutOrNull(10_000) {
                    compositorSession.socketPath.filterNotNull().first()
                } ?: run {
                    val currentState = compositorSession.state.value
                    throw IllegalStateException(
                        "Compositor failed to start within 10s (state=$currentState)"
                    )
                }
                emitOutput("✓ Compositor ready: $socketPath")

                // 2. Detect GPU mode
                _state.value = DesktopLauncherState.DetectingGpu
                emitOutput("Detecting GPU capabilities...")
                virglSession.start()
                val resolvedMode = virglSession.detectedGpuMode.value
                _gpuMode.value = resolvedMode
                emitOutput("✓ GPU mode: ${resolvedMode.displayName}")

                // 3. Start VirGL if needed (with timeout)
                if (resolvedMode.requiresVirglServer) {
                    _state.value = DesktopLauncherState.StartingVirgl
                    emitOutput("Starting VirGL server...")
                    val virglReady = withTimeoutOrNull(10_000) {
                        virglSession.state.first {
                            it == VirglState.RUNNING || it == VirglState.STOPPED || it == VirglState.ERROR
                        }
                    }
                    if (virglReady != VirglState.RUNNING) {
                        _gpuMode.value = GpuMode.SOFTWARE
                        val reason = when (virglReady) {
                            VirglState.ERROR -> "VirGL entered ERROR state"
                            VirglState.STOPPED -> "VirGL server stopped unexpectedly"
                            null -> "VirGL server timed out after 10s"
                            else -> "VirGL server in unexpected state: $virglReady"
                        }
                        emitOutput("⚠ $reason, falling back to SOFTWARE mode")
                    } else {
                        emitOutput("✓ VirGL server running")
                    }
                }

                // 4. Extract shims
                _state.value = DesktopLauncherState.ExtractingShims
                emitOutput("Extracting shims...")
                val shimSet = shimExtractor.extractAll(config.shimExtractDir)
                emitOutput("✓ Shims extracted")

                // ===== Phase 1: GPU Setup (separate proot invocation) =====
                if (_gpuMode.value != GpuMode.SOFTWARE) {
                    _state.value = DesktopLauncherState.SetupGpu
                    emitOutput("Setting up GPU drivers...")
                    val setupResult = gpuSetupManager.setup(
                        context = context,
                        environment = environment,
                        gpuMode = _gpuMode.value,
                        shimSet = shimSet,
                        config = config.gpuSetupConfig,
                        onOutput = { emitOutput("[gpu] $it") },
                    )
                    if (!setupResult.success) {
                        _gpuMode.value = GpuMode.SOFTWARE
                        emitOutput(
                            "⚠ GPU setup failed (${setupResult.errorMessage}), falling back to SOFTWARE mode",
                        )
                        virglSession.stop()
                    } else {
                        emitOutput("✓ GPU drivers configured")
                    }
                }

                // 5. Install app-specific packages if needed (+ xwayland if enabled)
                val allPackages = buildList {
                    addAll(requiredPackages)
                    if (config.xwaylandConfig.enabled) {
                        add("xwayland")
                        addAll(config.xwaylandConfig.additionalPackages)
                    }
                }
                if (allPackages.isNotEmpty()) {
                    _state.value = DesktopLauncherState.InstallingPackages
                    emitOutput("Installing packages: ${allPackages.joinToString(", ")}")
                    val exitCode = packageInstaller.installPackages(
                        environment,
                        allPackages,
                        onOutput = { emitOutput("[install] $it") },
                    )
                    if (exitCode != 0) {
                        emitOutput("⚠ Package install exited with code $exitCode, continuing anyway...")
                    }
                }

                // 5b. Set up XWayland wrapper script and /tmp/.X11-unix
                if (config.xwaylandConfig.enabled && xwaylandManager != null) {
                    val wrapperPath = xwaylandManager.extractWrapperScript(environment)
                    xwaylandManager.ensureTmpDirReady(config.tempDir)
                    emitOutput("✓ XWayland wrapper ready: $wrapperPath")
                }

                // 7. Write DNS config before launching in proot
                dnsManager?.writeResolvConf(File(environment.rootfsPath))

                // ===== Phase 2: App Launch (separate proot invocation) =====
                _state.value = DesktopLauncherState.LaunchingApp
                emitOutput("Launching: ${command.joinToString(" ")}")

                // Extract launch-app.sh from assets
                val cacheDir = File(config.tempDir)
                val launchScript = launchScriptManager.extractLaunchScript(context, cacheDir)

                // Build bind mounts for launch phase
                val bindMounts = launchScriptManager.buildLaunchBindMounts(
                    launchScript, shimSet, config.waylandRuntimeDir, config,
                ) + config.additionalBindMounts

                // Build launch command: bash launch-app.sh <user command>
                val launchCommand = launchScriptManager.buildLaunchCommand(command, config)

                // Build process env vars (safe vars — no LD_PRELOAD)
                val socketName = File(socketPath).name
                val xwaylandDisplay = if (config.xwaylandConfig.enabled) {
                    config.xwaylandConfig.displayName
                } else {
                    ""
                }
                val envVars = GpuEnvironmentConfig.buildProcessEnvVars(
                    _gpuMode.value,
                    socketName,
                    config.waylandRuntimeDir,
                    config.gpuSetupConfig.gpuDebugEnabled,
                    xwaylandDisplayName = xwaylandDisplay,
                ) + config.additionalEnvVars

                val pb = prootExecutor.buildCommand(
                    environment, launchCommand, bindMounts = bindMounts,
                )
                // Set env vars on ProcessBuilder — proot passes them through to the guest
                pb.environment().putAll(envVars)

                _state.value = DesktopLauncherState.Running

                val process = pb.start()
                activeProcess = process
                try {
                    val exitCode = withContext(Dispatchers.IO) {
                        try {
                            process.inputStream.bufferedReader().useLines { lines ->
                                for (line in lines) {
                                    emitOutput(line)
                                }
                            }
                        } catch (e: IOException) {
                            if (_state.value != DesktopLauncherState.Stopping) {
                                throw e
                            }
                            // Expected during stop — silently break out of read loop.
                        }
                        process.waitFor()
                    }
                    emitOutput("Process exited with code $exitCode")

                    // The launch process (proot) exited, but Wayland clients may
                    // still be connected to the compositor (e.g. Electron forks to
                    // background).  Stay in Running state while clients are active,
                    // matching the lifecycle model from the original compositor.
                    awaitClientsDrained()

                    _state.value = DesktopLauncherState.Idle
                } finally {
                    activeProcess = null
                }
            } catch (e: CancellationException) {
                _state.value = DesktopLauncherState.Idle
                throw e
            } catch (e: Exception) {
                val phase = _state.value::class.simpleName ?: "unknown"
                _state.value = DesktopLauncherState.Error(
                    message = e.message ?: "Unknown error",
                    phase = phase,
                    cause = e,
                )
                emitOutput("✗ Error during $phase: ${e.message}")
                e.cause?.let { cause ->
                    emitOutput("  Caused by: ${cause::class.simpleName}: ${cause.message}")
                }
            }
        }
    }

    fun launchPreset(
        environment: RootfsEnvironment,
        preset: DesktopAppPreset,
        scope: CoroutineScope,
    ) {
        launch(environment, preset.command, preset.requiredPackages, scope)
    }

    /**
     * After the launch process exits, poll the compositor's Wayland client count
     * and stay in [DesktopLauncherState.Running] as long as clients are connected.
     *
     * Some apps (e.g. VS Code / Electron) fork to background — the proot launch
     * process exits immediately while the Wayland client keeps rendering.  Instead
     * of tearing down the session on process exit, we use the compositor's client
     * count as the lifecycle authority, matching the old CompositorActivity model.
     *
     * After all clients disconnect, a [CLIENT_DRAIN_GRACE_MS] grace period avoids
     * premature teardown when a client temporarily disconnects and reconnects.
     */
    private suspend fun awaitClientsDrained() {
        // Guard: skip polling if already stopping or compositor is not running.
        if (_state.value != DesktopLauncherState.Running) return
        if (!compositorSession.state.value.isRunning) {
            emitOutput("Compositor not running, session complete")
            return
        }

        val initialClients = compositorSession.getClientCount()
        if (initialClients <= 0) {
            emitOutput("No Wayland clients connected, session complete")
            return
        }

        emitOutput("Process exited but $initialClients Wayland client(s) still connected — staying alive")
        var drainStartNanos = 0L

        while (_state.value == DesktopLauncherState.Running) {
            delay(CLIENT_POLL_INTERVAL_MS)

            // Exit immediately if the compositor has stopped (e.g. surface destroyed).
            if (!compositorSession.state.value.isRunning) {
                emitOutput("Compositor stopped, session complete")
                return
            }

            val count = compositorSession.getClientCount()

            if (count > 0) {
                // Clients still connected — reset drain timer.
                drainStartNanos = 0L
            } else {
                // No clients — start or continue the drain grace period.
                if (drainStartNanos == 0L) {
                    drainStartNanos = System.nanoTime()
                    emitOutput("All Wayland clients disconnected, starting ${CLIENT_DRAIN_GRACE_MS}ms grace period")
                }
                val elapsedMs = (System.nanoTime() - drainStartNanos) / 1_000_000
                if (elapsedMs >= CLIENT_DRAIN_GRACE_MS) {
                    emitOutput("Grace period elapsed, session complete")
                    return
                }
            }
        }
    }

    suspend fun stop() {
        _state.value = DesktopLauncherState.Stopping

        // Gracefully terminate the proot process: SIGTERM → wait → SIGKILL.
        // We use Process.waitFor(timeout, unit) instead of coroutine timeout
        // because Process.waitFor() is a blocking call that doesn't respond to
        // coroutine cancellation — withTimeoutOrNull would never actually fire.
        activeProcess?.let { process ->
            process.destroy() // SIGTERM
            val exited = withContext(Dispatchers.IO) {
                process.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)
            }
            if (!exited) {
                process.destroyForcibly() // SIGKILL
                withContext(Dispatchers.IO) {
                    process.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)
                }
            }
        }

        launchJob?.cancelAndJoin()
        launchJob = null
        virglSession.stop()
        cleanupStaleFiles()
        _state.value = DesktopLauncherState.Idle
    }

    /**
     * Remove stale sockets and temp files left by a previous session so the
     * next launch starts from a clean slate.
     */
    private fun cleanupStaleFiles() {
        // Wayland sockets
        val waylandDir = File(config.waylandRuntimeDir)
        if (waylandDir.isDirectory) {
            waylandDir.listFiles()?.filter { it.name.startsWith("wayland-") }?.forEach { file ->
                if (file.delete()) {
                    emitOutput("Cleaned stale Wayland socket: ${file.name}")
                }
            }
        }

        // AHB registry socket
        val ahbSocket = File(config.resolvedAhbSocketPath)
        if (ahbSocket.exists() && ahbSocket.delete()) {
            emitOutput("Cleaned stale AHB registry socket: ${ahbSocket.name}")
        }

        // X11 socket dir (remove contents, keep the directory)
        val x11Dir = File(config.tempDir, ".X11-unix")
        if (x11Dir.isDirectory) {
            x11Dir.listFiles()?.forEach { file ->
                if (file.delete()) {
                    emitOutput("Cleaned stale X11 socket: ${file.name}")
                }
            }
        }
    }
}
