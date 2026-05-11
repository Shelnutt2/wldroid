package nu.shell.wldroid.launcher

import com.google.common.truth.Truth.assertThat
import nu.shell.wldroid.virgl.GpuMode
import org.junit.Test

class GpuEnvironmentConfigTest {

    @Test fun commonVars_presentInAllModes() {
        GpuMode.entries.filter { it != GpuMode.AUTO }.forEach { mode ->
            val vars = GpuEnvironmentConfig.buildProcessEnvVars(mode, "wayland-0")
            assertThat(vars).containsEntry("WAYLAND_DISPLAY", "wayland-0")
            assertThat(vars).containsEntry("XDG_RUNTIME_DIR", "/tmp/xdg-runtime")
            assertThat(vars).containsEntry("WLDROID_GPU_MODE", mode.name)
            assertThat(vars).containsEntry("GDK_BACKEND", "wayland")
            assertThat(vars).containsEntry("QT_QPA_PLATFORM", "wayland")
            assertThat(vars).containsEntry("UV_USE_IO_URING", "0")
            assertThat(vars).containsEntry("ELECTRON_OZONE_PLATFORM_HINT", "wayland")
            assertThat(vars).containsEntry("ELECTRON_ENABLE_LOGGING", "1")
            assertThat(vars).containsEntry("ELECTRON_ENABLE_STACK_DUMPING", "1")
        }
    }

    @Test fun software_setsLibglAlwaysSoftware() {
        val vars = GpuEnvironmentConfig.buildProcessEnvVars(GpuMode.SOFTWARE, "wayland-0")
        assertThat(vars).containsEntry("LIBGL_ALWAYS_SOFTWARE", "1")
        assertThat(vars).doesNotContainKey("MESA_GL_VERSION_OVERRIDE")
        assertThat(vars).doesNotContainKey("VTEST_SOCK")
    }

    @Test fun virglGles_setsMesaOverrideAndVtestSock() {
        val vars = GpuEnvironmentConfig.buildProcessEnvVars(GpuMode.VIRGL_GLES, "wayland-0")
        assertThat(vars).containsEntry("MESA_GL_VERSION_OVERRIDE", "3.3")
        assertThat(vars).containsEntry("MESA_GLES_VERSION_OVERRIDE", "3.2")
        assertThat(vars).containsEntry("VTEST_SOCK", "/tmp/.virgl_test")
    }

    @Test fun virglZink_setsZinkVars() {
        val vars = GpuEnvironmentConfig.buildProcessEnvVars(GpuMode.VIRGL_ZINK, "wayland-0")
        assertThat(vars).containsEntry("MESA_GL_VERSION_OVERRIDE", "4.0")
        assertThat(vars).containsEntry("MESA_GLES_VERSION_OVERRIDE", "3.2")
        assertThat(vars).containsEntry("VTEST_SOCK", "/tmp/.virgl_test")
        assertThat(vars).doesNotContainKey("GALLIUM_DRIVER")
        assertThat(vars["VK_DRIVER_FILES"]).contains("lvp_icd")
    }

    @Test fun venus_setsVenusVars() {
        val vars = GpuEnvironmentConfig.buildProcessEnvVars(GpuMode.VENUS, "wayland-0")
        assertThat(vars).containsEntry("GALLIUM_DRIVER", "zink")
        assertThat(vars).containsEntry("MESA_GL_VERSION_OVERRIDE", "4.0")
        assertThat(vars).containsEntry("MESA_GLES_VERSION_OVERRIDE", "3.2")
        assertThat(vars["VK_DRIVER_FILES"]).contains("virtio_icd")
        assertThat(vars["VK_DRIVER_FILES"]).doesNotContain("aarch64")
        assertThat(vars).containsEntry("VTEST_SOCK", "/tmp/.virgl_test")
    }

    @Test fun turnipDirect_setsTurnipVars() {
        val vars = GpuEnvironmentConfig.buildProcessEnvVars(GpuMode.TURNIP_DIRECT, "wayland-0")
        assertThat(vars).containsEntry("GALLIUM_DRIVER", "zink")
        assertThat(vars).containsEntry("MESA_VK_WSI_PRESENT_MODE", "fifo")
        assertThat(vars["VK_DRIVER_FILES"]).contains("lvp_icd")
        assertThat(vars).doesNotContainKey("VTEST_SOCK")
    }

    @Test fun noLdPreload_inAnyMode() {
        GpuMode.entries.filter { it != GpuMode.AUTO }.forEach { mode ->
            val vars = GpuEnvironmentConfig.buildProcessEnvVars(mode, "wayland-0")
            assertThat(vars).doesNotContainKey("LD_PRELOAD")
            assertThat(vars).doesNotContainKey("LD_LIBRARY_PATH")
        }
    }

    @Test fun debugEnabled_setsWldroidDebug() {
        val vars = GpuEnvironmentConfig.buildProcessEnvVars(GpuMode.SOFTWARE, "wayland-0", debugEnabled = true)
        assertThat(vars).containsEntry("WLDROID_DEBUG", "1")
    }

    @Test fun debugDisabled_excludesWldroidDebug() {
        val vars = GpuEnvironmentConfig.buildProcessEnvVars(GpuMode.SOFTWARE, "wayland-0")
        assertThat(vars).doesNotContainKey("WLDROID_DEBUG")
    }

    @Test fun wldroidGpuMode_presentInAllModes() {
        GpuMode.entries.filter { it != GpuMode.AUTO }.forEach { mode ->
            val vars = GpuEnvironmentConfig.buildProcessEnvVars(mode, "wayland-0")
            assertThat(vars).containsEntry("WLDROID_GPU_MODE", mode.name)
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun auto_throwsException() {
        GpuEnvironmentConfig.buildProcessEnvVars(GpuMode.AUTO, "wayland-0")
    }
}
