package nu.shell.wldroid.shims

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Extracts bundled shim .so files from assets to the rootfs guest environment.
 */
class ShimExtractor(private val context: Context) {

    data class ShimSet(
        val drmShim: String,      // Path to extracted libdrm-shim.so
        val drmWrapper: String,   // Path to extracted libdrm-wrapper.so
        val gbmShim: String,      // Path to extracted libgbm.so.1
        val eglOverride: String,  // Path to extracted libegl_override.so
        val netstub: String,      // Path to extracted libnetstub.so
    )

    /**
     * Extract all shim libraries to the target directory.
     * Typically called once per rootfs environment setup.
     */
    suspend fun extractAll(targetDir: String): ShimSet = withContext(Dispatchers.IO) {
        val dir = File(targetDir)
        dir.mkdirs()

        val config = ShimConfig() // all enabled
        extractShims(dir, config)
    }

    /**
     * Extract only shims needed for the given GPU mode.
     */
    suspend fun extractForGpuMode(targetDir: String, gpuMode: String): ShimSet =
        withContext(Dispatchers.IO) {
            val dir = File(targetDir)
            dir.mkdirs()

            val config = ShimConfig.forGpuMode(gpuMode)
            extractShims(dir, config)
        }

    /**
     * Check if shims are already extracted and up-to-date.
     */
    fun isExtracted(targetDir: String): Boolean {
        val dir = File(targetDir)
        if (!dir.exists()) return false

        return SHIM_ASSETS.all { (assetPath, filename) ->
            val subDir = File(assetPath).parent ?: ""
            File(File(dir, subDir), filename).exists()
        }
    }

    /**
     * Get LD_PRELOAD string for the given GPU mode.
     */
    fun getLdPreloadString(shimSet: ShimSet, gpuMode: String): String {
        val config = ShimConfig.forGpuMode(gpuMode)
        return buildList {
            if (config.enableDrmShim) add(shimSet.drmShim)
            if (config.enableGbmShim) add(shimSet.gbmShim)
            if (config.enableEglOverride) add(shimSet.eglOverride)
            if (config.enableNetstub) add(shimSet.netstub)
        }.joinToString(":")
    }

    private fun extractShims(dir: File, config: ShimConfig): ShimSet {
        val paths = mutableMapOf<String, String>()

        for ((assetPath, filename) in SHIM_ASSETS) {
            val subDir = File(assetPath).parent ?: ""
            val outDir = File(dir, subDir).also { it.mkdirs() }
            val outFile = File(outDir, filename)
            val key = ASSET_TO_KEY[assetPath] ?: continue

            // Check if this shim is enabled in the config
            val enabled = when (key) {
                "drmShim" -> config.enableDrmShim
                "drmWrapper" -> config.enableDrmWrapper
                "gbmShim" -> config.enableGbmShim
                "eglOverride" -> config.enableEglOverride
                "netstub" -> config.enableNetstub
                else -> false
            }

            if (enabled) {
                extractAsset(assetPath, outFile)
            }

            // Always record the path so ShimSet is fully populated
            paths[key] = outFile.absolutePath
        }

        return ShimSet(
            drmShim = paths["drmShim"] ?: File(File(dir, "drm-shim"), "libdrm-shim.so").absolutePath,
            drmWrapper = paths["drmWrapper"] ?: File(File(dir, "drm-shim"), "libdrm-wrapper.so").absolutePath,
            gbmShim = paths["gbmShim"] ?: File(File(dir, "gbm-shim"), "libgbm.so.1").absolutePath,
            eglOverride = paths["eglOverride"] ?: File(File(dir, "egl-override"), "libegl_override.so").absolutePath,
            netstub = paths["netstub"] ?: File(File(dir, "netstub"), "libnetstub.so").absolutePath,
        )
    }

    private fun extractAsset(assetPath: String, outFile: File) {
        context.assets.open(assetPath).use { input ->
            outFile.outputStream().use { output ->
                input.copyTo(output)
            }
        }
        // Ensure the extracted .so is executable
        outFile.setExecutable(true, false)
    }

    companion object {
        /** Asset path -> output filename mapping for all shim libraries. */
        internal val SHIM_ASSETS = listOf(
            "drm-shim/libdrm-shim.so" to "libdrm-shim.so",
            "drm-shim/libdrm-wrapper.so" to "libdrm-wrapper.so",
            "gbm-shim/libgbm.so" to "libgbm.so.1",
            "egl-override/libegl_override.so" to "libegl_override.so",
            "netstub/libnetstub.so" to "libnetstub.so",
        )

        /** Maps asset paths to ShimSet field names. */
        private val ASSET_TO_KEY = mapOf(
            "drm-shim/libdrm-shim.so" to "drmShim",
            "drm-shim/libdrm-wrapper.so" to "drmWrapper",
            "gbm-shim/libgbm.so" to "gbmShim",
            "egl-override/libegl_override.so" to "eglOverride",
            "netstub/libnetstub.so" to "netstub",
        )
    }
}
