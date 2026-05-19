package nu.shel.wldroid.compositor

import java.io.File

data class CompositorConfig(
    val cacheDir: String = "",
    val xkbBasePath: String = "",
    val xwaylandEnabled: Boolean = true,
    /**
     * Path to the XWayland binary (or wrapper script) that wlroots will fork+exec.
     * Set via the `WLR_XWAYLAND` environment variable before the compositor starts.
     * Empty string means wlroots uses its default search (which won't find Xwayland on Android).
     */
    val xwaylandBinaryPath: String = "",
    /**
     * Host directory used for XWayland's X11 sockets and lock files.
     * Set via the `WLR_XWAYLAND_TMPDIR` environment variable before the compositor starts.
     * On Android, `/tmp` is not writable by apps, so this must point to an app-private
     * directory (typically the proot-tmp cache dir). Empty string uses the wlroots default (`/tmp`).
     */
    val xwaylandTmpDir: String = "",
    val gpuMode: String = "AUTO", // Will be replaced by enum from :virgl module
    val testClientEnabled: Boolean = false,
    /** Path for the AHB registry Unix socket used for GPU buffer sharing with VirGL. */
    val ahbRegistrySocketPath: String = "",
) {
    /**
     * Validate that this config is internally consistent.
     *
     * @throws IllegalStateException if [xwaylandEnabled] is true and
     *     [xwaylandBinaryPath] is non-empty but does not point to an existing file.
     *     Use [nu.shel.wldroid.launcher.CompositorConfigFactory.createWithXWayland]
     *     or call `XWaylandManager.prepare()` before constructing the config to
     *     avoid this error.
     */
    fun validate() {
        if (xwaylandEnabled && xwaylandBinaryPath.isNotEmpty()) {
            check(File(xwaylandBinaryPath).exists()) {
                "XWayland wrapper script not found at $xwaylandBinaryPath. " +
                    "Use CompositorConfigFactory.createWithXWayland() or call " +
                    "XWaylandManager.prepare() before constructing CompositorConfig."
            }
        }
    }

    companion object {
        fun default() = CompositorConfig()
    }
}
