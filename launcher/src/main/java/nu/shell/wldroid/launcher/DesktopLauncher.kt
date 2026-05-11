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
import nu.shell.wldroid.compositor.CompositorSession
import nu.shell.wldroid.proot.ProotExecutor
import nu.shell.wldroid.proot.RootfsEnvironment
import nu.shell.wldroid.shims.ShimExtractor
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

    private var launchJob: Job? = null

    suspend fun launch(
        environment: RootfsEnvironment,
        command: List<String>,
        requiredPackages: List<String> = emptyList(),
        scope: CoroutineScope,
    ) {
        launchJob?.cancel()
        launchJob = scope.launch {
            try {
                // 1. Wait for compositor socket
                _state.value = DesktopLauncherState.StartingCompositor
                val socketPath = compositorSession.socketPath.filterNotNull().first()
                _processOutput.tryEmit("✓ Compositor ready: $socketPath")

                // 2. Detect GPU mode
                _state.value = DesktopLauncherState.DetectingGpu
                virglSession.start()
                val resolvedMode = virglSession.detectedGpuMode.value
                _gpuMode.value = resolvedMode
                _processOutput.tryEmit("✓ GPU mode: ${resolvedMode.displayName}")

                // 3. Start VirGL if needed
                if (resolvedMode.requiresVirglServer) {
                    _state.value = DesktopLauncherState.StartingVirgl
                    if (virglSession.state.value != VirglState.RUNNING) {
                        _gpuMode.value = GpuMode.SOFTWARE
                        _processOutput.tryEmit("⚠ VirGL server failed to start, falling back to SOFTWARE mode")
                    } else {
                        _processOutput.tryEmit("✓ VirGL server running")
                    }
                }

                // 4. Extract shims
                _state.value = DesktopLauncherState.ExtractingShims
                val shimSet = shimExtractor.extractAll(config.shimExtractDir)
                val ldPreload = shimExtractor.getLdPreloadString(shimSet, _gpuMode.value.name)
                _processOutput.tryEmit("✓ Shims extracted")

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

                val virglSocketDir = if (_gpuMode.value.requiresVirglServer) {
                    // VirglConfig socketPath parent dir
                    null // Will be configured via additionalBindMounts if needed
                } else {
                    null
                }

                val bindMounts = GpuEnvironmentConfig.buildBindMounts(
                    _gpuMode.value, config, config.waylandRuntimeDir, shimSet, virglSocketDir,
                ) + config.additionalBindMounts

                // 7. Launch in proot
                _state.value = DesktopLauncherState.LaunchingApp
                _processOutput.tryEmit("Launching: ${command.joinToString(" ")}")
                _state.value = DesktopLauncherState.Running

                val exitCode = prootExecutor.runInProot(
                    environment = environment,
                    command = command,
                    envVars = envVars,
                    bindMounts = bindMounts,
                    onOutput = { _processOutput.tryEmit(it) },
                )

                _processOutput.tryEmit("Process exited with code $exitCode")
                _state.value = DesktopLauncherState.Idle
            } catch (e: CancellationException) {
                _state.value = DesktopLauncherState.Idle
                throw e
            } catch (e: Exception) {
                _state.value = DesktopLauncherState.Error(
                    message = e.message ?: "Unknown error",
                    phase = _state.value::class.simpleName ?: "unknown",
                    cause = e,
                )
                _processOutput.tryEmit("✗ Error: ${e.message}")
            }
        }
    }

    suspend fun launchPreset(
        environment: RootfsEnvironment,
        preset: DesktopAppPreset,
        scope: CoroutineScope,
    ) {
        launch(environment, preset.command, preset.requiredPackages, scope)
    }

    suspend fun stop() {
        _state.value = DesktopLauncherState.Stopping
        launchJob?.cancel()
        launchJob = null
        virglSession.stop()
        _state.value = DesktopLauncherState.Idle
    }
}
