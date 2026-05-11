package nu.shell.wldroid.launcher

import nu.shell.wldroid.virgl.GpuMode

/**
 * Builds process-level environment variables for the app launch phase.
 *
 * These vars are set on the [ProcessBuilder] and inherited by proot into the guest.
 * LD_PRELOAD and LD_LIBRARY_PATH are NOT set here — they are managed by `launch-app.sh`
 * inside the guest to avoid proot interference with linker paths.
 */
object GpuEnvironmentConfig {

    /**
     * Builds environment variables for the process (set on [ProcessBuilder]).
     *
     * Proot passes these through to the guest environment. Does NOT include
     * LD_PRELOAD or LD_LIBRARY_PATH — those are set by launch-app.sh inside the guest.
     *
     * @param gpuMode The resolved GPU mode (must not be [GpuMode.AUTO])
     * @param waylandSocketName Wayland socket filename (e.g., "wayland-0")
     * @param waylandRuntimeDir Host path to the Wayland runtime directory (used as XDG_RUNTIME_DIR
     *   with an identity bind mount so proot resolves it without /tmp shadow conflicts)
     * @param debugEnabled Whether to enable verbose Mesa/EGL debug logging
     * @return Map of environment variable name to value
     */
    fun buildProcessEnvVars(
        gpuMode: GpuMode,
        waylandSocketName: String,
        waylandRuntimeDir: String,
        debugEnabled: Boolean = false,
    ): Map<String, String> {
        val vars = mutableMapOf(
            "WAYLAND_DISPLAY" to waylandSocketName,
            "XDG_RUNTIME_DIR" to waylandRuntimeDir,
            "WLDROID_GPU_MODE" to gpuMode.name,
            "ELECTRON_OZONE_PLATFORM_HINT" to "wayland",
            "GDK_BACKEND" to "wayland",
            "QT_QPA_PLATFORM" to "wayland",
            "UV_USE_IO_URING" to "0",
            "ELECTRON_ENABLE_LOGGING" to "1",
            "ELECTRON_ENABLE_STACK_DUMPING" to "1",
        )

        if (debugEnabled) {
            vars["WLDROID_DEBUG"] = "1"
        }

        when (gpuMode) {
            GpuMode.SOFTWARE -> {
                vars["LIBGL_ALWAYS_SOFTWARE"] = "1"
            }
            GpuMode.VIRGL_GLES -> {
                vars["MESA_GL_VERSION_OVERRIDE"] = "3.3"
                vars["MESA_GLES_VERSION_OVERRIDE"] = "3.2"
                vars["VTEST_SOCK"] = "/tmp/.virgl_test"
            }
            GpuMode.VIRGL_ZINK -> {
                vars["MESA_GL_VERSION_OVERRIDE"] = "4.0"
                vars["MESA_GLES_VERSION_OVERRIDE"] = "3.2"
                vars["VTEST_SOCK"] = "/tmp/.virgl_test"
                vars["VK_DRIVER_FILES"] = "/usr/share/vulkan/icd.d/lvp_icd.aarch64.json"
            }
            GpuMode.VENUS -> {
                vars["GALLIUM_DRIVER"] = "zink"
                vars["MESA_GL_VERSION_OVERRIDE"] = "4.0"
                vars["MESA_GLES_VERSION_OVERRIDE"] = "3.2"
                vars["VK_DRIVER_FILES"] = "/usr/share/vulkan/icd.d/virtio_icd.json"
                vars["VTEST_SOCK"] = "/tmp/.virgl_test"
            }
            GpuMode.TURNIP_DIRECT -> {
                vars["GALLIUM_DRIVER"] = "zink"
                vars["MESA_VK_WSI_PRESENT_MODE"] = "fifo"
                vars["VK_DRIVER_FILES"] = "/usr/share/vulkan/icd.d/lvp_icd.aarch64.json"
            }
            GpuMode.AUTO -> {
                throw IllegalArgumentException("GPU mode must be resolved before building env vars")
            }
        }
        return vars
    }
}
