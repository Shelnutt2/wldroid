package nu.shell.wldroid.proot

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Downloads rootfs tarballs from remote URLs with resume support and SHA-256 verification.
 *
 * Uses a standalone [OkHttpClient] (no auth/cert pinning) since
 * downloads target public GitHub releases.
 */
class RootfsDownloader(private val cacheDir: File) {

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.MINUTES)
            .writeTimeout(30, TimeUnit.SECONDS)
            .callTimeout(30, TimeUnit.MINUTES)
            .build()
    }

    init {
        cacheDir.mkdirs()
    }

    /**
     * Downloads a file from [url] to [destFile] with resume support.
     *
     * Writes to `<destFile>.partial` during download, renames on completion.
     * If a `.partial` file exists, attempts to resume via HTTP Range header.
     * Progress is reported via [onProgress] callback (throttled to ~100KB intervals).
     *
     * @param url The URL to download from
     * @param destFile The target file path
     * @param onProgress Callback with (bytesRead, totalBytes). totalBytes may be -1 if unknown.
     */
    suspend fun download(
        url: String,
        destFile: File,
        onProgress: ((bytesRead: Long, totalBytes: Long) -> Unit)? = null,
    ) = withContext(Dispatchers.IO) {
        destFile.parentFile?.mkdirs()
        val partialFile = File(destFile.path + ".partial")
        val existingBytes = if (partialFile.exists()) partialFile.length() else 0L

        val requestBuilder = Request.Builder().url(url)
        if (existingBytes > 0) {
            requestBuilder.header("Range", "bytes=$existingBytes-")
        }

        val response = client.newCall(requestBuilder.build()).execute()
        response.use {
            if (!response.isSuccessful && response.code != 206) {
                throw RuntimeException("Download failed: HTTP ${response.code}")
            }

            val body = response.body ?: throw RuntimeException("Empty response body")

            // If server returned 200 instead of 206, restart from scratch
            val resuming = response.code == 206 && existingBytes > 0
            val totalBytes = if (resuming) {
                existingBytes + body.contentLength()
            } else {
                body.contentLength()
            }

            var bytesRead = if (resuming) existingBytes else 0L
            if (!resuming && partialFile.exists()) {
                partialFile.delete()
            }

            val outputStream = FileOutputStream(partialFile, resuming)
            val buffer = ByteArray(8192)
            var lastProgressBytes = bytesRead
            val progressThreshold = 100 * 1024L // Report every ~100KB

            body.byteStream().use { input ->
                outputStream.use { output ->
                    var read: Int
                    while (input.read(buffer).also { read = it } != -1) {
                        output.write(buffer, 0, read)
                        bytesRead += read
                        if (bytesRead - lastProgressBytes >= progressThreshold) {
                            onProgress?.invoke(bytesRead, totalBytes)
                            lastProgressBytes = bytesRead
                        }
                    }
                }
            }

            // Final progress report
            onProgress?.invoke(bytesRead, totalBytes)

            // Rename partial to final
            if (!partialFile.renameTo(destFile)) {
                // renameTo can fail across filesystems; fall back to copy
                partialFile.copyTo(destFile, overwrite = true)
                partialFile.delete()
            }

            Log.i(TAG, "Download complete: ${destFile.name} ($bytesRead bytes)")
        }
    }

    /**
     * Verifies the SHA-256 hash of [file] matches [expectedHash].
     *
     * @return true if the hash matches, false otherwise
     */
    fun verifySha256(file: File, expectedHash: String): Boolean {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(8192)
        file.inputStream().buffered().use { input ->
            var read: Int
            while (input.read(buffer).also { read = it } != -1) {
                digest.update(buffer, 0, read)
            }
        }
        val actualHash = digest.digest().joinToString("") { "%02x".format(it) }
        val matches = actualHash.equals(expectedHash, ignoreCase = true)
        if (!matches) {
            Log.w(TAG, "SHA-256 mismatch for ${file.name}: expected=$expectedHash, actual=$actualHash")
        }
        return matches
    }

    /**
     * Returns the cached tarball file for [url] if it exists, null otherwise.
     * The cache key is the filename extracted from the URL.
     */
    fun getCachedTarball(url: String): File? {
        val filename = url.substringAfterLast('/')
        val cached = File(cacheDir, filename)
        return if (cached.exists() && cached.length() > MIN_TARBALL_SIZE) cached else null
    }

    /**
     * Returns the cache file path for a URL (whether or not it exists yet).
     */
    fun getCacheFile(url: String): File {
        val filename = url.substringAfterLast('/')
        return File(cacheDir, filename)
    }

    companion object {
        private const val TAG = "RootfsDownloader"
        private const val MIN_TARBALL_SIZE = 1_000_000L // 1MB — rootfs tarballs are 50MB+
    }
}
