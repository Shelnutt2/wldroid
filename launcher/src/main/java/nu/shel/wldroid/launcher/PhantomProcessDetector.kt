package nu.shel.wldroid.launcher

import java.util.concurrent.TimeUnit

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
            val process = ProcessBuilder(
                "settings", "get", "global", "settings_enable_monitor_phantom_procs"
            ).redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().use { it.readText().trim() }
            val finished = process.waitFor(5, TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                return null
            }
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

    companion object {
        /** User-facing guidance message when phantom process killing is detected. */
        const val PHANTOM_KILL_GUIDANCE =
            "Android terminated background processes. Enable Developer Options and disable 'Phantom process monitoring' to fix this."
    }
}
