package nu.shell.wldroid.shims

/**
 * Configuration for which shims to load per GPU mode.
 */
data class ShimConfig(
    val enableDrmShim: Boolean = true,
    val enableGbmShim: Boolean = true,
    val enableEglOverride: Boolean = true,
    val enableNetstub: Boolean = true,
    val enableDrmWrapper: Boolean = true,
) {
    companion object {
        fun forGpuMode(gpuMode: String): ShimConfig = when (gpuMode) {
            "SOFTWARE" -> ShimConfig(enableGbmShim = false, enableEglOverride = false)
            "TURNIP_DIRECT" -> ShimConfig(enableEglOverride = false)
            else -> ShimConfig() // All shims for VirGL modes
        }
    }
}
