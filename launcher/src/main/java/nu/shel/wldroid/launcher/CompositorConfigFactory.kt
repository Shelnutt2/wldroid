package nu.shel.wldroid.launcher

import nu.shel.wldroid.compositor.CompositorConfig
import nu.shel.wldroid.proot.RootfsEnvironment

/**
 * Factory for creating [CompositorConfig] instances with correct XWayland setup.
 *
 * This factory handles the required startup ordering: it prepares the XWayland
 * wrapper script before building the config, so downstream apps do not need to
 * manage the extraction sequence manually.
 *
 * ## Startup ordering guarantee
 *
 * When [createWithXWayland] is called the factory:
 * 1. Extracts the XWayland wrapper script via [XWaylandManager.prepare].
 * 2. Ensures `/tmp/.X11-unix` exists for X11 socket sharing.
 * 3. Constructs a [CompositorConfig] whose [CompositorConfig.xwaylandBinaryPath]
 *    points to the newly extracted (and executable) wrapper script.
 *
 * This guarantees that `xwaylandBinaryPath` is never a dangling reference when
 * the config is consumed by [nu.shel.wldroid.compositor.CompositorSession].
 *
 * ## Usage
 *
 * ```kotlin
 * val factory = CompositorConfigFactory(xwaylandManager)
 * val config = factory.createWithXWayland(
 *     environment = rootfsEnvironment,
 *     cacheDir = context.cacheDir.absolutePath,
 *     tempDir = launcherConfig.tempDir,
 *     xkbBasePath = xkbPath,
 * )
 * val session = CompositorSession(config)
 * ```
 *
 * For configs that do not need XWayland, construct [CompositorConfig] directly.
 */
class CompositorConfigFactory(
    private val xwaylandManager: XWaylandManager,
) {
    /**
     * Create a [CompositorConfig] with XWayland support fully prepared.
     *
     * Extracts the wrapper script and prepares `/tmp/.X11-unix` before
     * constructing the config, ensuring [CompositorConfig.xwaylandBinaryPath]
     * points to an existing, executable file.
     *
     * @param environment The rootfs environment that contains Xwayland
     * @param cacheDir Host cache directory for the compositor
     * @param tempDir Host path that maps to proot's `/tmp`
     * @param xwaylandConfig XWayland configuration (default: enabled)
     * @param xkbBasePath Path to extracted XKB data (default: empty)
     * @param gpuMode GPU rendering mode name (default: "AUTO")
     * @param testClientEnabled Whether to enable the test client (default: false)
     * @param ahbRegistrySocketPath Path for the AHB registry socket (default: empty)
     * @return A fully initialized [CompositorConfig] ready for [nu.shel.wldroid.compositor.CompositorSession]
     */
    fun createWithXWayland(
        environment: RootfsEnvironment,
        cacheDir: String,
        tempDir: String,
        xwaylandConfig: XWaylandConfig = XWaylandConfig(),
        xkbBasePath: String = "",
        gpuMode: String = "AUTO",
        testClientEnabled: Boolean = false,
        ahbRegistrySocketPath: String = "",
    ): CompositorConfig {
        val xwaylandPath = if (xwaylandConfig.enabled) {
            val result = xwaylandManager.prepare(environment, tempDir)
            result.wrapperScriptPath
        } else {
            ""
        }

        return CompositorConfig(
            cacheDir = cacheDir,
            xkbBasePath = xkbBasePath,
            xwaylandEnabled = xwaylandConfig.enabled,
            xwaylandBinaryPath = xwaylandPath,
            gpuMode = gpuMode,
            testClientEnabled = testClientEnabled,
            ahbRegistrySocketPath = ahbRegistrySocketPath,
        )
    }
}
