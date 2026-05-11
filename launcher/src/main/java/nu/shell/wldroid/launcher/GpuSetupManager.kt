package nu.shell.wldroid.launcher

import nu.shell.wldroid.proot.ProotExecutor
import nu.shell.wldroid.proot.RootfsEnvironment
import nu.shell.wldroid.virgl.GpuMode
import java.io.File

class GpuSetupManager(
    private val prootExecutor: ProotExecutor,
    private val packageInstaller: PackageInstaller,
) {
    companion object {
        internal const val MARKER_PREFIX = ".gpu_setup_"

        val VIRGL_PACKAGES = listOf(
            "libegl-mesa0", "libgl1-mesa-dri", "libgles2-mesa",
            "libgbm1", "mesa-utils",
        )
        val TURNIP_PACKAGES = listOf(
            "mesa-vulkan-drivers", "libegl-mesa0", "libgl1-mesa-dri",
            "libgles2-mesa", "libgbm1",
        )
        val VENUS_PACKAGES = listOf(
            "mesa-vulkan-drivers", "libegl-mesa0", "libgles2-mesa",
            "libgbm1",
        )
    }

    suspend fun setup(
        environment: RootfsEnvironment,
        gpuMode: GpuMode,
        onOutput: ((String) -> Unit)? = null,
    ): Boolean {
        if (gpuMode == GpuMode.SOFTWARE) return true

        val markerFile = "${environment.rootfsPath}/tmp/$MARKER_PREFIX${gpuMode.name}"
        if (File(markerFile).exists()) {
            onOutput?.invoke("GPU setup already done for ${gpuMode.name}")
            return true
        }

        val packages = packagesForMode(gpuMode)
        if (packages.isNotEmpty()) {
            val exitCode = packageInstaller.installPackages(
                environment, packages, onOutput,
            )
            if (exitCode != 0) {
                onOutput?.invoke("Mesa package installation failed (exit=$exitCode)")
                return false
            }
        }

        if (gpuMode in listOf(GpuMode.VIRGL_GLES, GpuMode.VIRGL_ZINK, GpuMode.VENUS)) {
            createDriSymlink(environment, onOutput)
        }

        File(markerFile).apply {
            parentFile?.mkdirs()
            createNewFile()
        }
        onOutput?.invoke("GPU setup complete for ${gpuMode.name}")
        return true
    }

    fun isSetUp(environment: RootfsEnvironment, gpuMode: GpuMode): Boolean {
        if (gpuMode == GpuMode.SOFTWARE) return true
        return File("${environment.rootfsPath}/tmp/$MARKER_PREFIX${gpuMode.name}").exists()
    }

    internal fun packagesForMode(gpuMode: GpuMode): List<String> = when (gpuMode) {
        GpuMode.VIRGL_GLES, GpuMode.VIRGL_ZINK -> VIRGL_PACKAGES
        GpuMode.TURNIP_DIRECT -> TURNIP_PACKAGES
        GpuMode.VENUS -> VENUS_PACKAGES
        else -> emptyList()
    }

    private suspend fun createDriSymlink(
        environment: RootfsEnvironment,
        onOutput: ((String) -> Unit)?,
    ) {
        val cmd = listOf("bash", "-c", """
            DRI_DIR="/usr/lib/aarch64-linux-gnu/dri"
            if [ -f "${'$'}DRI_DIR/armv8_dri.so" ] && [ ! -e "${'$'}DRI_DIR/virtio_gpu_dri.so" ]; then
                ln -s armv8_dri.so "${'$'}DRI_DIR/virtio_gpu_dri.so"
            fi
        """.trimIndent())
        prootExecutor.runInProot(
            environment = environment,
            command = cmd,
            onOutput = onOutput,
        )
    }
}
