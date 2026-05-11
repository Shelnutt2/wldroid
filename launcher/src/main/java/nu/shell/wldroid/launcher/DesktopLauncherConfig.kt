package nu.shell.wldroid.launcher

import nu.shell.wldroid.proot.BindMount

data class DesktopLauncherConfig(
    val shimExtractDir: String,
    val waylandRuntimeDir: String,
    val netstubGuestPath: String = "/opt/wldroid/netstub",
    val shimGuestBasePath: String = "/opt/wldroid",
    val additionalEnvVars: Map<String, String> = emptyMap(),
    val additionalBindMounts: List<BindMount> = emptyList(),
)
