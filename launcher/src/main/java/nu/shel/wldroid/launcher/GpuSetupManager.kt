package nu.shel.wldroid.launcher

import android.content.Context
import nu.shel.wldroid.proot.BindMount
import nu.shel.wldroid.proot.ProotExecutor
import nu.shel.wldroid.proot.RootfsEnvironment
import nu.shel.wldroid.shims.ShimExtractor
import nu.shel.wldroid.virgl.GpuMode
import java.io.File

/**
 * Manages GPU setup (Phase 1) by running `setup-gpu.sh` inside proot.
 *
 * The script installs Mesa packages, copies shim libraries to system paths,
 * creates DRI symlinks, and caches shim checksums for incremental updates.
 */
class GpuSetupManager(
    private val prootExecutor: ProotExecutor,
) {
    companion object {
        private const val ASSET_PATH = "scripts/setup-gpu.sh"
        private const val SCRIPT_NAME = "setup-gpu.sh"

        /** Mesa packages for VirGL modes. Kept for backward compatibility. */
        @Deprecated("Package lists are now managed by setup-gpu.sh")
        val VIRGL_PACKAGES = listOf(
            "libegl-mesa0", "libgl1-mesa-dri", "libgles2-mesa",
            "libgbm1", "mesa-utils",
        )

        /** Mesa packages for Turnip mode. Kept for backward compatibility. */
        @Deprecated("Package lists are now managed by setup-gpu.sh")
        val TURNIP_PACKAGES = listOf(
            "mesa-vulkan-drivers", "libegl-mesa0", "libgl1-mesa-dri",
            "libgles2-mesa", "libgbm1",
        )

        /** Mesa packages for Venus mode. Kept for backward compatibility. */
        @Deprecated("Package lists are now managed by setup-gpu.sh")
        val VENUS_PACKAGES = listOf(
            "mesa-vulkan-drivers", "libegl-mesa0", "libgles2-mesa",
            "libgbm1",
        )
    }

    /**
     * Result of GPU setup.
     *
     * @property success Whether setup completed successfully
     * @property effectiveMode The GPU mode to use (may differ from requested if setup failed)
     * @property errorMessage Human-readable error description when [success] is false
     */
    data class GpuSetupResult(
        val success: Boolean,
        val effectiveMode: GpuMode,
        val errorMessage: String? = null,
    )

    /**
     * Runs setup-gpu.sh inside proot to install Mesa packages, copy shim libraries,
     * and create DRI symlinks for the given [gpuMode].
     *
     * @param context Android context for asset extraction
     * @param environment The rootfs environment to run setup in
     * @param gpuMode The resolved GPU mode (must not be [GpuMode.AUTO])
     * @param shimSet Host-side shim paths from [ShimExtractor.extractAll]
     * @param config GPU setup configuration
     * @param onOutput Optional callback for script output lines
     * @return [GpuSetupResult] with success status and effective mode
     */
    suspend fun setup(
        context: Context,
        environment: RootfsEnvironment,
        gpuMode: GpuMode,
        shimSet: ShimExtractor.ShimSet,
        config: GpuSetupConfig = GpuSetupConfig(),
        onOutput: ((String) -> Unit)? = null,
    ): GpuSetupResult {
        if (gpuMode == GpuMode.SOFTWARE) {
            return GpuSetupResult(success = true, effectiveMode = GpuMode.SOFTWARE)
        }

        // 1. Extract setup-gpu.sh from launcher assets to host cache
        val scriptFile = extractSetupScript(context)

        // 2. Build bind mounts: shim dirs + setup script
        val bindMounts = buildSetupBindMounts(shimSet, scriptFile, config)

        // 3. Build env vars for the setup proot invocation
        val envVars = buildSetupEnvVars(gpuMode, config)

        // 4. Run setup-gpu.sh in proot
        val exitCode = prootExecutor.runInProot(
            environment = environment,
            command = listOf("bash", "${config.shimBasePath}/$SCRIPT_NAME"),
            bindMounts = bindMounts,
            guestEnvVars = envVars,
            onOutput = onOutput,
        )

        return if (exitCode == 0) {
            GpuSetupResult(success = true, effectiveMode = gpuMode)
        } else {
            GpuSetupResult(
                success = false,
                effectiveMode = GpuMode.SOFTWARE,
                errorMessage = "setup-gpu.sh exited with code $exitCode",
            )
        }
    }

    internal fun extractSetupScript(context: Context): File {
        val scriptsDir = File(context.cacheDir, "scripts")
        scriptsDir.mkdirs()
        val scriptFile = File(scriptsDir, SCRIPT_NAME)

        context.assets.open(ASSET_PATH).use { input ->
            scriptFile.outputStream().use { output ->
                input.copyTo(output)
            }
        }
        scriptFile.setExecutable(true)
        return scriptFile
    }

    internal fun buildSetupBindMounts(
        shimSet: ShimExtractor.ShimSet,
        scriptFile: File,
        config: GpuSetupConfig,
    ): List<BindMount> {
        val mounts = mutableListOf<BindMount>()

        // Shim directories → /opt/wldroid/<shim-name>
        val drmShimDir = File(shimSet.drmShim).parent ?: shimSet.drmShim
        mounts.add(BindMount(hostPath = drmShimDir, guestPath = "${config.shimBasePath}/drm-shim"))

        val gbmShimDir = File(shimSet.gbmShim).parent ?: shimSet.gbmShim
        mounts.add(BindMount(hostPath = gbmShimDir, guestPath = "${config.shimBasePath}/gbm-shim"))

        val eglOverrideDir = File(shimSet.eglOverride).parent ?: shimSet.eglOverride
        mounts.add(BindMount(hostPath = eglOverrideDir, guestPath = "${config.shimBasePath}/egl-override"))

        // Setup script → /opt/wldroid/setup-gpu.sh
        mounts.add(
            BindMount(
                hostPath = scriptFile.absolutePath,
                guestPath = "${config.shimBasePath}/$SCRIPT_NAME",
            ),
        )

        return mounts
    }

    internal fun buildSetupEnvVars(
        gpuMode: GpuMode,
        config: GpuSetupConfig,
    ): Map<String, String> {
        val vars = mutableMapOf(
            "WLDROID_GPU_MODE" to gpuMode.name,
        )
        if (!config.installMesaPackages) {
            vars["WLDROID_SKIP_MESA_INSTALL"] = "1"
        }
        if (config.gpuDebugEnabled) {
            vars["WLDROID_DEBUG"] = "1"
        }
        return vars
    }
}
