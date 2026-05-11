package nu.shell.wldroid.launcher

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.withTimeoutOrNull
import nu.shell.wldroid.compositor.CompositorSession
import nu.shell.wldroid.proot.ProotDnsManager
import nu.shell.wldroid.proot.ProotExecutor
import nu.shell.wldroid.proot.RootfsEnvironment
import nu.shell.wldroid.shims.ShimExtractor
import android.system.Os
import nu.shell.wldroid.virgl.GpuMode
import nu.shell.wldroid.virgl.VirglSession
import nu.shell.wldroid.virgl.VirglState
import java.io.File

class DesktopLauncher(
    private val compositorSession: CompositorSession,
    private val virglSession: VirglSession,
    private val shimExtractor: ShimExtractor,
    private val prootExecutor: ProotExecutor,
    private val config: DesktopLauncherConfig,
    private val packageInstaller: PackageInstaller = PackageInstaller(prootExecutor),
    private val gpuSetupManager: GpuSetupManager = GpuSetupManager(prootExecutor, packageInstaller),
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

    /** Emit a message to the process output stream (visible in the log panel). */
    fun emitOutput(message: String) {
        _processOutput.tryEmit(message)
    }

    fun launch(
        environment: RootfsEnvironment,
        command: List<String>,
        requiredPackages: List<String> = emptyList(),
        scope: CoroutineScope,
    ) {
        launchJob?.cancel()
        launchJob = scope.launch {
            try {
                // 0. Set AHB_REGISTRY_SOCKET so compositor↔VirGL GPU buffer sharing works.
                val ahbSocketPath = config.resolvedAhbSocketPath
                Os.setenv("AHB_REGISTRY_SOCKET", ahbSocketPath, true)
                _processOutput.tryEmit("AHB registry socket: $ahbSocketPath")

                // 1. Wait for compositor socket (with timeout)
                _state.value = DesktopLauncherState.StartingCompositor
                _processOutput.tryEmit("Starting compositor...")
                val socketPath = withTimeoutOrNull(10_000) {
                    compositorSession.socketPath.filterNotNull().first()
                } ?: run {
                    val currentState = compositorSession.state.value
                    throw IllegalStateException(
                        "Compositor failed to start within 10s (state=$currentState)"
                    )
                }
                _processOutput.tryEmit("✓ Compositor ready: $socketPath")

                // 2. Detect GPU mode
                _state.value = DesktopLauncherState.DetectingGpu
                _processOutput.tryEmit("Detecting GPU capabilities...")
                virglSession.start()
                val resolvedMode = virglSession.detectedGpuMode.value
                _gpuMode.value = resolvedMode
                _processOutput.tryEmit("✓ GPU mode: ${resolvedMode.displayName}")

                // 3. Start VirGL if needed (with timeout)
                if (resolvedMode.requiresVirglServer) {
                    _state.value = DesktopLauncherState.StartingVirgl
                    _processOutput.tryEmit("Starting VirGL server...")
                    val virglReady = withTimeoutOrNull(10_000) {
                        virglSession.state.first { it == VirglState.RUNNING || it == VirglState.STOPPED }
                    }
                    if (virglReady != VirglState.RUNNING) {
                        _gpuMode.value = GpuMode.SOFTWARE
                        _processOutput.tryEmit("⚠ VirGL server failed to start, falling back to SOFTWARE mode")
                    } else {
                        _processOutput.tryEmit("✓ VirGL server running")
                    }
                }

                // 4. Extract shims
                _state.value = DesktopLauncherState.ExtractingShims
                _processOutput.tryEmit("Extracting shims...")
                val shimSet = shimExtractor.extractAll(config.shimExtractDir)
                val ldPreload = GpuEnvironmentConfig.buildGuestLdPreload(_gpuMode.value, config.shimGuestBasePath)
                _processOutput.tryEmit("✓ Shims extracted")

                // 4.5. GPU setup (Mesa packages + DRI symlinks)
                if (_gpuMode.value != GpuMode.SOFTWARE) {
                    _state.value = DesktopLauncherState.SetupGpu
                    _processOutput.tryEmit("Setting up GPU drivers...")
                    val gpuReady = gpuSetupManager.setup(
                        environment, _gpuMode.value,
                        onOutput = { _processOutput.tryEmit("[gpu] $it") },
                    )
                    if (!gpuReady) {
                        _gpuMode.value = GpuMode.SOFTWARE
                        _processOutput.tryEmit("⚠ GPU setup failed, falling back to SOFTWARE mode")
                        virglSession.stop()
                    } else {
                        _processOutput.tryEmit("✓ GPU drivers configured")
                    }
                }

                // 5. Install packages if needed
                if (requiredPackages.isNotEmpty()) {
                    _state.value = DesktopLauncherState.InstallingPackages
                    _processOutput.tryEmit("Installing packages: ${requiredPackages.joinToString(", ")}")
                    val exitCode = packageInstaller.installPackages(
                        environment,
                        requiredPackages,
                        onOutput = { _processOutput.tryEmit("[install] $it") },
                    )
                    if (exitCode != 0) {
                        _processOutput.tryEmit("⚠ Package install exited with code $exitCode, continuing anyway...")
                    }
                }

                // 6. Build env vars + bind mounts
                val socketName = File(socketPath).name
                val envVars = GpuEnvironmentConfig.buildEnvVars(
                    _gpuMode.value, socketName, config.waylandRuntimeDir, shimSet, ldPreload,
                ) + config.additionalEnvVars

                val bindMounts = GpuEnvironmentConfig.buildBindMounts(
                    _gpuMode.value, config, config.waylandRuntimeDir, shimSet,
                ) + config.additionalBindMounts

                // 7. Write DNS config before launching in proot
                dnsManager?.writeResolvConf(File(environment.rootfsPath))

                // 8. Launch in proot
                _state.value = DesktopLauncherState.LaunchingApp
                _processOutput.tryEmit("Launching: ${command.joinToString(" ")}")
                _state.value = DesktopLauncherState.Running

                val pb = prootExecutor.buildCommand(
                    environment, command, bindMounts = bindMounts, guestEnvVars = envVars,
                )
                val process = pb.start()
                activeProcess = process
                try {
                    val exitCode = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        process.inputStream.bufferedReader().useLines { lines ->
                            for (line in lines) {
                                _processOutput.tryEmit(line)
                            }
                        }
                        process.waitFor()
                    }
                    _processOutput.tryEmit("Process exited with code $exitCode")
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
                _processOutput.tryEmit("✗ Error during $phase: ${e.message}")
                e.cause?.let { cause ->
                    _processOutput.tryEmit("  Caused by: ${cause::class.simpleName}: ${cause.message}")
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

    suspend fun stop() {
        _state.value = DesktopLauncherState.Stopping

        // Gracefully terminate the proot process: SIGTERM → wait → SIGKILL
        activeProcess?.let { process ->
            process.destroy() // SIGTERM
            val exited = withTimeoutOrNull(3_000) {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    process.waitFor()
                }
            }
            if (exited == null) {
                process.destroyForcibly() // SIGKILL
            }
        }

        launchJob?.cancelAndJoin()
        launchJob = null
        virglSession.stop()
        _state.value = DesktopLauncherState.Idle
    }
}
