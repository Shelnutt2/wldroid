package nu.shell.wldroid.launcher

import android.content.Context
import nu.shell.wldroid.proot.BindMount
import nu.shell.wldroid.shims.ShimExtractor
import java.io.File

/**
 * Manages the launch-app.sh script extraction and build configuration
 * for the app launch phase (Phase 2) of the desktop launch pipeline.
 *
 * The launch script runs inside proot and handles LD_PRELOAD,
 * LD_LIBRARY_PATH, Wayland socket wait, and exec of the user command.
 */
class LaunchScriptManager {

    companion object {
        private const val ASSET_PATH = "scripts/launch-app.sh"
        private const val SCRIPT_NAME = "launch-app.sh"
    }

    /**
     * Extracts the launch-app.sh script from launcher module assets
     * to a host-side cache directory.
     */
    fun extractLaunchScript(context: Context, cacheDir: File): File {
        val scriptsDir = File(cacheDir, "scripts")
        scriptsDir.mkdirs()
        val scriptFile = File(scriptsDir, SCRIPT_NAME)

        context.assets.open(ASSET_PATH).use { input ->
            scriptFile.outputStream().use { output ->
                input.copyTo(output)
            }
        }
        scriptFile.setExecutable(true)
        return scriptFile
    }

    /**
     * Builds bind mounts for the app launch phase:
     * - Wayland runtime dir → identity bind (same host and guest path, avoids /tmp shadow)
     * - Netstub dir → /opt/wldroid/netstub
     * - Launch script → /opt/wldroid/launch-app.sh
     */
    fun buildLaunchBindMounts(
        launchScriptFile: File,
        shimSet: ShimExtractor.ShimSet,
        waylandRuntimeDir: String,
        config: DesktopLauncherConfig,
    ): List<BindMount> {
        val mounts = mutableListOf<BindMount>()

        // Wayland runtime dir → identity bind (same host and guest path).
        // Using an identity bind avoids the /tmp shadow problem where proot's
        // default /tmp bind mount would intercept a /tmp/xdg-runtime sub-bind.
        mounts.add(BindMount(hostPath = waylandRuntimeDir, guestPath = waylandRuntimeDir))

        // Netstub dir (all modes — works around Android SELinux getifaddrs)
        val netstubDir = File(shimSet.netstub).parent ?: shimSet.netstub
        mounts.add(BindMount(hostPath = netstubDir, guestPath = config.netstubGuestPath))

        // Launch script → /opt/wldroid/launch-app.sh
        mounts.add(
            BindMount(
                hostPath = launchScriptFile.absolutePath,
                guestPath = "${config.shimGuestBasePath}/$SCRIPT_NAME",
            ),
        )

        return mounts
    }

    /**
     * Builds the proot command to run launch-app.sh with the user's command.
     *
     * @param command The user's command and arguments
     * @param config The launcher configuration
     * @return Command list: ["bash", "/opt/wldroid/launch-app.sh", ...command]
     */
    fun buildLaunchCommand(
        command: List<String>,
        config: DesktopLauncherConfig,
    ): List<String> {
        return listOf("bash", "${config.shimGuestBasePath}/$SCRIPT_NAME") + command
    }
}
