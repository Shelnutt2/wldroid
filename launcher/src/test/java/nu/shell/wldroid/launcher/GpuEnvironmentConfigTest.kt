package nu.shell.wldroid.launcher

import com.google.common.truth.Truth.assertThat
import nu.shell.wldroid.shims.ShimExtractor
import nu.shell.wldroid.virgl.GpuMode
import org.junit.Test

class GpuEnvironmentConfigTest {
    private val testShimSet = ShimExtractor.ShimSet(
        drmShim = "/test/drm-shim/libdrm_shim.so",
        drmWrapper = "/test/drm-wrapper/libdrm_wrapper.so",
        gbmShim = "/test/gbm-shim/libgbm_shim.so",
        eglOverride = "/test/egl-override/libegl_override.so",
        netstub = "/test/netstub/libnetstub.so",
    )

    @Test fun commonVars_presentInAllModes() {
        GpuMode.entries.filter { it != GpuMode.AUTO }.forEach { mode ->
            val vars = GpuEnvironmentConfig.buildEnvVars(mode, "wayland-0", "/tmp/runtime", testShimSet, "preload.so")
            assertThat(vars).containsEntry("WAYLAND_DISPLAY", "wayland-0")
            assertThat(vars).containsEntry("XDG_RUNTIME_DIR", "/tmp/xdg-runtime")
            assertThat(vars).containsEntry("GDK_BACKEND", "wayland")
            assertThat(vars).containsEntry("QT_QPA_PLATFORM", "wayland")
            assertThat(vars).containsEntry("UV_USE_IO_URING", "0")
            assertThat(vars).containsEntry("ELECTRON_OZONE_PLATFORM_HINT", "wayland")
        }
    }

    @Test fun software_setsLibglAlwaysSoftware() {
        val vars = GpuEnvironmentConfig.buildEnvVars(GpuMode.SOFTWARE, "wayland-0", "/tmp", testShimSet, "")
        assertThat(vars).containsEntry("LIBGL_ALWAYS_SOFTWARE", "1")
        assertThat(vars).doesNotContainKey("MESA_GL_VERSION_OVERRIDE")
        assertThat(vars).doesNotContainKey("VTEST_SOCK")
    }

    @Test fun virglGles_setsMesaOverrideAndVtestSock() {
        val vars = GpuEnvironmentConfig.buildEnvVars(GpuMode.VIRGL_GLES, "wayland-0", "/tmp", testShimSet, "")
        assertThat(vars).containsEntry("MESA_GL_VERSION_OVERRIDE", "3.3")
        assertThat(vars).containsEntry("MESA_GLES_VERSION_OVERRIDE", "3.2")
        assertThat(vars).containsEntry("VTEST_SOCK", "/tmp/.virgl_test")
    }

    @Test fun virglZink_setsZinkVars() {
        val vars = GpuEnvironmentConfig.buildEnvVars(GpuMode.VIRGL_ZINK, "wayland-0", "/tmp", testShimSet, "")
        assertThat(vars).containsEntry("MESA_GL_VERSION_OVERRIDE", "4.0")
        assertThat(vars).containsEntry("MESA_GLES_VERSION_OVERRIDE", "3.2")
        assertThat(vars).containsEntry("VTEST_SOCK", "/tmp/.virgl_test")
        assertThat(vars).doesNotContainKey("GALLIUM_DRIVER")
        assertThat(vars["VK_DRIVER_FILES"]).contains("lvp_icd")
    }

    @Test fun venus_setsVenusVars() {
        val vars = GpuEnvironmentConfig.buildEnvVars(GpuMode.VENUS, "wayland-0", "/tmp", testShimSet, "")
        assertThat(vars).containsEntry("GALLIUM_DRIVER", "zink")
        assertThat(vars).containsEntry("MESA_GLES_VERSION_OVERRIDE", "3.2")
        assertThat(vars["VK_DRIVER_FILES"]).contains("virtio_icd")
        assertThat(vars["VK_DRIVER_FILES"]).doesNotContain("aarch64")
        assertThat(vars).containsEntry("VTEST_SOCK", "/tmp/.virgl_test")
    }

    @Test fun turnipDirect_setsTurnipVars() {
        val vars = GpuEnvironmentConfig.buildEnvVars(GpuMode.TURNIP_DIRECT, "wayland-0", "/tmp", testShimSet, "")
        assertThat(vars).doesNotContainKey("GALLIUM_DRIVER")
        assertThat(vars).containsEntry("MESA_VK_WSI_PRESENT_MODE", "fifo")
        assertThat(vars["VK_DRIVER_FILES"]).contains("lvp_icd")
        assertThat(vars).doesNotContainKey("VTEST_SOCK")
    }

    @Test fun ldPreload_includedWhenNonEmpty() {
        val vars = GpuEnvironmentConfig.buildEnvVars(GpuMode.SOFTWARE, "wayland-0", "/tmp", testShimSet, "/opt/preload.so")
        assertThat(vars).containsEntry("LD_PRELOAD", "/opt/preload.so")
    }

    @Test fun ldPreload_excludedWhenEmpty() {
        val vars = GpuEnvironmentConfig.buildEnvVars(GpuMode.SOFTWARE, "wayland-0", "/tmp", testShimSet, "")
        assertThat(vars).doesNotContainKey("LD_PRELOAD")
    }

    @Test fun bindMounts_includeWaylandRuntime() {
        val config = DesktopLauncherConfig(shimExtractDir = "/test", waylandRuntimeDir = "/host/runtime", tempDir = "/host/tmp")
        val mounts = GpuEnvironmentConfig.buildBindMounts(GpuMode.SOFTWARE, config, "/host/runtime", testShimSet)
        assertThat(mounts.any { it.guestPath == "/tmp/xdg-runtime" }).isTrue()
    }

    @Test fun bindMounts_noVirglSocketDirMount() {
        val config = DesktopLauncherConfig(shimExtractDir = "/test", waylandRuntimeDir = "/host/runtime", tempDir = "/host/tmp")
        val mounts = GpuEnvironmentConfig.buildBindMounts(GpuMode.VIRGL_GLES, config, "/host/runtime", testShimSet)
        // VirGL socket is now in proot-tmp which is mapped to /tmp by proot itself,
        // so no explicit virgl socket bind mount should exist
        assertThat(mounts.none { it.guestPath == "/tmp" }).isTrue()
    }

    @Test fun guestLdPreload_virglGlesIncludesAll4() {
        val ldPreload = GpuEnvironmentConfig.buildGuestLdPreload(GpuMode.VIRGL_GLES, "/opt/wldroid")
        assertThat(ldPreload).contains("/opt/wldroid/drm-shim/libdrm-shim.so")
        assertThat(ldPreload).contains("/opt/wldroid/gbm-shim/libgbm.so.1")
        assertThat(ldPreload).contains("/opt/wldroid/egl-override/libegl_override.so")
        assertThat(ldPreload).contains("/opt/wldroid/netstub/libnetstub.so")
        assertThat(ldPreload.split(":")).hasSize(4)
    }

    @Test fun guestLdPreload_softwareExcludesGbmAndEgl() {
        val ldPreload = GpuEnvironmentConfig.buildGuestLdPreload(GpuMode.SOFTWARE, "/opt/wldroid")
        assertThat(ldPreload).contains("/opt/wldroid/drm-shim/libdrm-shim.so")
        assertThat(ldPreload).contains("/opt/wldroid/netstub/libnetstub.so")
        assertThat(ldPreload).doesNotContain("libgbm.so.1")
        assertThat(ldPreload).doesNotContain("libegl_override.so")
        assertThat(ldPreload.split(":")).hasSize(2)
    }

    @Test fun guestLdPreload_usesCustomBasePath() {
        val ldPreload = GpuEnvironmentConfig.buildGuestLdPreload(GpuMode.VIRGL_GLES, "/custom/path")
        assertThat(ldPreload).contains("/custom/path/drm-shim/libdrm-shim.so")
    }
}
