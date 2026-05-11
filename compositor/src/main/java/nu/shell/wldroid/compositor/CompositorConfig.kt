package nu.shell.wldroid.compositor

data class CompositorConfig(
    val cacheDir: String = "",
    val xkbBasePath: String = "",
    val xwaylandEnabled: Boolean = true,
    val gpuMode: String = "AUTO", // Will be replaced by enum from :virgl module
    val testClientEnabled: Boolean = false,
    /** Path for the AHB registry Unix socket used for GPU buffer sharing with VirGL. */
    val ahbRegistrySocketPath: String = "",
) {
    companion object {
        fun default() = CompositorConfig()
    }
}
