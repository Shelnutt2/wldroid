package nu.shell.wldroid.compositor

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
    val gpuMode: String = "AUTO", // Will be replaced by enum from :virgl module
    val testClientEnabled: Boolean = false,
    /** Path for the AHB registry Unix socket used for GPU buffer sharing with VirGL. */
    val ahbRegistrySocketPath: String = "",
) {
    companion object {
        fun default() = CompositorConfig()
    }
}
