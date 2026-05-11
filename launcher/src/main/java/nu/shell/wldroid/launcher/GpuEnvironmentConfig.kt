package nu.shell.wldroid.launcher

import nu.shell.wldroid.proot.BindMount
import nu.shell.wldroid.shims.ShimConfig
import nu.shell.wldroid.shims.ShimExtractor
import nu.shell.wldroid.virgl.GpuMode

object GpuEnvironmentConfig {

    fun buildEnvVars(
        gpuMode: GpuMode,
        waylandSocketName: String,
        waylandRuntimeDir: String,
        shimSet: ShimExtractor.ShimSet,
        ldPreload: String,
    ): Map<String, String> {
        val vars = mutableMapOf(
            "WAYLAND_DISPLAY" to waylandSocketName,
            "XDG_RUNTIME_DIR" to "/tmp/xdg-runtime",
            "ELECTRON_OZONE_PLATFORM_HINT" to "wayland",
            "GDK_BACKEND" to "wayland",
            "QT_QPA_PLATFORM" to "wayland",
            "UV_USE_IO_URING" to "0",
        )
        if (ldPreload.isNotEmpty()) {
            vars["LD_PRELOAD"] = ldPreload
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
                vars["MESA_GLES_VERSION_OVERRIDE"] = "3.2"
                vars["VK_DRIVER_FILES"] = "/usr/share/vulkan/icd.d/virtio_icd.json"
                vars["VTEST_SOCK"] = "/tmp/.virgl_test"
            }
            GpuMode.TURNIP_DIRECT -> {
                vars["MESA_VK_WSI_PRESENT_MODE"] = "fifo"
                vars["VK_DRIVER_FILES"] = "/usr/share/vulkan/icd.d/lvp_icd.aarch64.json"
            }
            GpuMode.AUTO -> {
                throw IllegalArgumentException("GPU mode must be resolved before building env vars")
            }
        }
        return vars
    }

    /**
     * Build guest-side LD_PRELOAD string using paths relative to [shimGuestBasePath].
     * These are the paths visible inside proot, not host-side extraction paths.
     */
    fun buildGuestLdPreload(gpuMode: GpuMode, shimGuestBasePath: String): String {
        val config = ShimConfig.forGpuMode(gpuMode.name)
        return buildList {
            if (config.enableDrmShim) add("$shimGuestBasePath/drm-shim/libdrm-shim.so")
            if (config.enableGbmShim) add("$shimGuestBasePath/gbm-shim/libgbm.so.1")
            if (config.enableEglOverride) add("$shimGuestBasePath/egl-override/libegl_override.so")
            if (config.enableNetstub) add("$shimGuestBasePath/netstub/libnetstub.so")
        }.joinToString(":")
    }

    fun buildBindMounts(
        gpuMode: GpuMode,
        config: DesktopLauncherConfig,
        waylandRuntimeDir: String,
        shimSet: ShimExtractor.ShimSet,
    ): List<BindMount> {
        val mounts = mutableListOf<BindMount>()

        // Wayland runtime dir → XDG_RUNTIME_DIR
        mounts.add(BindMount(hostPath = waylandRuntimeDir, guestPath = "/tmp/xdg-runtime"))

        // Netstub (all modes)
        val netstubDir = java.io.File(shimSet.netstub).parent ?: shimSet.netstub
        mounts.add(BindMount(hostPath = netstubDir, guestPath = config.netstubGuestPath))

        // DRM shim (all modes except pure software could benefit, include always)
        val drmShimDir = java.io.File(shimSet.drmShim).parent ?: shimSet.drmShim
        mounts.add(BindMount(hostPath = drmShimDir, guestPath = "${config.shimGuestBasePath}/drm-shim"))

        // GBM shim
        val gbmShimDir = java.io.File(shimSet.gbmShim).parent ?: shimSet.gbmShim
        mounts.add(BindMount(hostPath = gbmShimDir, guestPath = "${config.shimGuestBasePath}/gbm-shim"))

        // EGL override
        val eglOverrideDir = java.io.File(shimSet.eglOverride).parent ?: shimSet.eglOverride
        mounts.add(BindMount(hostPath = eglOverrideDir, guestPath = "${config.shimGuestBasePath}/egl-override"))

        return mounts
    }
}
