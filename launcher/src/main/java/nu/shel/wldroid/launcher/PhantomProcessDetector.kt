package nu.shel.wldroid.launcher

/**
 * Detects when Android's phantom process killer has terminated child processes.
 *
 * Android limits the number of "phantom processes" (child processes of an app
 * that are not Android components). When the limit (typically 32) is exceeded,
 * Android kills excess processes — including critical ones like proot and virgl.
 */
class PhantomProcessDetector {
    /**
     * Checks whether the phantom process monitor is enabled on this device.
     * Returns null if the setting cannot be determined.
     */
    fun isPhantomProcessKillerEnabled(): Boolean? {
        return try {
            val result = Runtime.getRuntime().exec(
                arrayOf("settings", "get", "global", "settings_enable_monitor_phantom_procs")
            )
            val output = result.inputStream.bufferedReader().readText().trim()
            result.waitFor()
            when (output) {
                "0" -> false
                "1" -> true
                "null" -> true // default is enabled
                else -> null
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Checks if a process with the given PID is still alive.
     */
    fun isProcessAlive(pid: Int): Boolean {
        return try {
            java.io.File("/proc/$pid").exists()
        } catch (e: Exception) {
            false
        }
    }

    companion object {
        const val TAG = "PhantomProcessDetector"

        /** User-facing guidance message when phantom process killing is detected. */
        const val PHANTOM_KILL_GUIDANCE =
            "Android terminated background processes. Enable Developer Options and disable 'Phantom process monitoring' to fix this."
    }
}
