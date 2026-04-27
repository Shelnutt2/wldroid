package nu.shell.wldroid.compositor

data class CompositorConfig(
    val cacheDir: String = "",
    val xkbBasePath: String = "",
    val xwaylandEnabled: Boolean = true,
    val gpuMode: String = "AUTO", // Will be replaced by enum from :virgl module
    val testClientEnabled: Boolean = false,
) {
    companion object {
        fun default() = CompositorConfig()
    }
}
