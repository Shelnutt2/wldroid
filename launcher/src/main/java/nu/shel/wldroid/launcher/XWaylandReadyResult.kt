package nu.shel.wldroid.launcher

/**
 * Result of [XWaylandManager.prepare] containing the paths needed to
 * configure a [nu.shel.wldroid.compositor.CompositorConfig] for XWayland support.
 *
 * Downstream apps receive this from the high-level preparation API and pass
 * the [wrapperScriptPath] directly to [nu.shel.wldroid.compositor.CompositorConfig.xwaylandBinaryPath].
 *
 * @property wrapperScriptPath Absolute path to the generated, executable XWayland wrapper script.
 */
data class XWaylandReadyResult(
    val wrapperScriptPath: String,
)
