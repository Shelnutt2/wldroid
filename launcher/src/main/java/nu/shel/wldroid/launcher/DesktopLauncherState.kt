package nu.shel.wldroid.launcher

sealed class DesktopLauncherState {
    data object Idle : DesktopLauncherState()
    data object StartingCompositor : DesktopLauncherState()
    data object DetectingGpu : DesktopLauncherState()
    data object StartingVirgl : DesktopLauncherState()
    data object ExtractingShims : DesktopLauncherState()
    data object SetupGpu : DesktopLauncherState()
    data object InstallingPackages : DesktopLauncherState()
    data object LaunchingApp : DesktopLauncherState()
    data object Running : DesktopLauncherState()
    data object Stopping : DesktopLauncherState()
    data class Error(val message: String, val phase: String, val cause: Throwable? = null) : DesktopLauncherState()

    val isActive: Boolean
        get() = this is StartingCompositor || this is DetectingGpu || this is StartingVirgl ||
                this is ExtractingShims || this is SetupGpu || this is InstallingPackages || this is LaunchingApp ||
                this is Running || this is Stopping

    val isTerminal: Boolean
        get() = this is Idle || this is Error
}
