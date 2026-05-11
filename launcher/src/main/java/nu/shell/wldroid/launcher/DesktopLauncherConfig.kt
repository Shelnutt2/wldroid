package nu.shell.wldroid.launcher

import nu.shell.wldroid.proot.BindMount

data class DesktopLauncherConfig(
    val shimExtractDir: String,
    val waylandRuntimeDir: String,
    /** Host path that maps to proot's /tmp (typically cacheDir/proot-tmp). */
    val tempDir: String,
    /** Path for the AHB registry socket; defaults to tempDir/.ahb_registry. */
    val ahbRegistrySocketPath: String = "",
    val netstubGuestPath: String = "/opt/wldroid/netstub",
    val shimGuestBasePath: String = "/opt/wldroid",
    /** Configuration for the GPU setup phase (Phase 1). */
    val gpuSetupConfig: GpuSetupConfig = GpuSetupConfig(),
    /** Configuration for XWayland (X11 app support). */
    val xwaylandConfig: XWaylandConfig = XWaylandConfig(),
    val additionalEnvVars: Map<String, String> = emptyMap(),
    val additionalBindMounts: List<BindMount> = emptyList(),
) {
    /** Resolved AHB socket path — uses configured value or falls back to tempDir/.ahb_registry. */
    val resolvedAhbSocketPath: String
        get() = ahbRegistrySocketPath.ifEmpty { "$tempDir/.ahb_registry" }
}
