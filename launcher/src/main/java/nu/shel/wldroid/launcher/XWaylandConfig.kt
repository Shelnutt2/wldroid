package nu.shel.wldroid.launcher

/**
 * Configuration for XWayland support.
 *
 * When enabled, a wrapper script is generated so that wlroots can fork+exec Xwayland
 * through proot, allowing X11 applications to render through the Wayland compositor.
 *
 * @property enabled Whether XWayland support is active (default: true)
 * @property displayNumber X display number — typically 0 on Android where no other X server runs
 * @property additionalPackages Extra packages to install alongside `xwayland` (e.g., `xterm`)
 */
data class XWaylandConfig(
    val enabled: Boolean = true,
    val displayNumber: Int = 0,
    val additionalPackages: List<String> = emptyList(),
) {
    /** The DISPLAY value for X11 clients (e.g., ":0"). */
    val displayName: String
        get() = ":$displayNumber"
}
