package nu.shell.wldroid.compositor

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Extracts bundled XKB keyboard layout data from assets to a destination directory.
 */
class XkbExtractor(private val context: Context) {

    companion object {
        private const val ASSET_DIR = "xkb"
        private const val MARKER_FILE = ".extracted"
    }

    /**
     * Extract XKB data to [destDir], returning the absolute path.
     * Idempotent: skips extraction if the marker file already exists.
     */
    suspend fun extract(destDir: String): String = withContext(Dispatchers.IO) {
        val xkbDir = File(destDir)
        val marker = File(xkbDir, MARKER_FILE)
        if (marker.exists()) return@withContext xkbDir.absolutePath

        // Clean any partial previous extraction
        if (xkbDir.exists()) xkbDir.deleteRecursively()

        copyAssetDir(ASSET_DIR, xkbDir)
        marker.createNewFile()
        xkbDir.absolutePath
    }

    /**
     * Check if XKB data has already been extracted to [destDir].
     */
    fun isExtracted(destDir: String): Boolean =
        File(destDir, MARKER_FILE).exists()

    private fun copyAssetDir(assetPath: String, destDir: File) {
        destDir.mkdirs()
        val children = context.assets.list(assetPath) ?: return
        for (child in children) {
            val childPath = "$assetPath/$child"
            val childList = context.assets.list(childPath)
            if (childList != null && childList.isNotEmpty()) {
                copyAssetDir(childPath, File(destDir, child))
            } else {
                context.assets.open(childPath).use { input ->
                    File(destDir, child).outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
            }
        }
    }
}
