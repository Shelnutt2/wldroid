package nu.shel.wldroid.proot

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.tukaani.xz.XZInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files

/**
 * Extracts tar.xz archives (rootfs tarballs) with support for symlinks,
 * permissions, and zip-slip protection.
 */
class RootfsExtractor {

    /**
     * Extracts a tar.xz file to [destDir].
     *
     * Handles regular files, directories, and symbolic links.
     * Sets file permissions from tar entry mode bits.
     * Reports progress every 100 entries via [onProgress].
     *
     * @param tarXzFile The .tar.xz file to extract
     * @param destDir The destination directory
     * @param stripComponents Number of leading path components to strip from each
     *   entry name, similar to `tar --strip-components`. Defaults to 0.
     * @param onProgress Callback with count of entries extracted so far
     * @throws IOException on extraction errors
     * @throws SecurityException on zip-slip attempts
     */
    suspend fun extract(
        tarXzFile: File,
        destDir: File,
        stripComponents: Int = 0,
        onProgress: ((entriesExtracted: Int) -> Unit)? = null,
    ) = withContext(Dispatchers.IO) {
        destDir.mkdirs()
        val canonicalDest = destDir.canonicalFile

        var entriesExtracted = 0
        val failedSymlinks = mutableListOf<String>()
        val deferredHardLinks = mutableListOf<Pair<File, File>>()

        FileInputStream(tarXzFile).use { fileInput ->
        BufferedInputStream(fileInput, 65536).use { bufferedInput ->
        XZInputStream(bufferedInput).use { xzInput ->
        TarArchiveInputStream(xzInput).use { tar ->
            val buffer = ByteArray(65536)
            var entry: TarArchiveEntry? = tar.nextEntry
            while (entry != null) {
                // Strip leading path components (like tar --strip-components)
                val entryName = if (stripComponents > 0) {
                    val parts = entry.name.split("/")
                    if (parts.size <= stripComponents) {
                        entry = tar.nextEntry
                        continue // Skip entries that are entirely within stripped prefix
                    }
                    parts.drop(stripComponents).joinToString("/")
                } else {
                    entry.name
                }

                val outFile = File(canonicalDest, entryName).canonicalFile

                // Zip-slip protection
                if (!outFile.path.startsWith(canonicalDest.path + File.separator) &&
                    outFile != canonicalDest
                ) {
                    throw SecurityException(
                        "Zip-slip detected: ${entry.name} resolves outside destination",
                    )
                }

                when {
                    entry.isDirectory -> {
                        outFile.mkdirs()
                    }
                    entry.isSymbolicLink -> {
                        outFile.parentFile?.mkdirs()
                        // Delete existing file/link before creating symlink
                        outFile.delete()
                        try {
                            val symlinkTarget = stripLinkTarget(entry.linkName, entry.name, stripComponents)
                            Files.createSymbolicLink(
                                outFile.toPath(),
                                java.nio.file.Paths.get(symlinkTarget),
                            )
                        } catch (e: Exception) {
                            failedSymlinks.add(entry.name)
                            Log.w(TAG, "Failed to create symlink: ${entry.name} -> ${entry.linkName}", e)
                        }
                    }
                    entry.isLink -> {
                        // Hard link — strip components from link target path
                        outFile.parentFile?.mkdirs()
                        val hardlinkName = stripLinkTarget(entry.linkName, entry.name, stripComponents)
                        val linkTarget = File(canonicalDest, hardlinkName).canonicalFile
                        if (linkTarget.exists()) {
                            outFile.delete()
                            try {
                                Files.createLink(outFile.toPath(), linkTarget.toPath())
                            } catch (_: Exception) {
                                // Fall back to copy if hard link fails
                                linkTarget.copyTo(outFile, overwrite = true)
                            }
                        } else {
                            deferredHardLinks.add(outFile to linkTarget)
                        }
                    }
                    else -> {
                        // Regular file
                        outFile.parentFile?.mkdirs()
                        FileOutputStream(outFile).use { output ->
                            var read: Int
                            while (tar.read(buffer).also { read = it } != -1) {
                                output.write(buffer, 0, read)
                            }
                        }

                        // Set permissions from tar entry mode
                        setPermissions(outFile, entry.mode)
                    }
                }

                entriesExtracted++
                if (entriesExtracted % 100 == 0) {
                    onProgress?.invoke(entriesExtracted)
                }

                entry = tar.nextEntry
            }
        } } } }

        if (failedSymlinks.isNotEmpty()) {
            Log.w(TAG, "Failed to create ${failedSymlinks.size} symlinks: ${failedSymlinks.take(5)}")
            if (failedSymlinks.size > 10) {
                throw IOException(
                    "Too many symlink failures (${failedSymlinks.size}): rootfs is likely corrupt",
                )
            }
        }

        for ((outFile, linkTarget) in deferredHardLinks) {
            if (linkTarget.exists()) {
                outFile.delete()
                try {
                    Files.createLink(outFile.toPath(), linkTarget.toPath())
                } catch (_: Exception) {
                    linkTarget.copyTo(outFile, overwrite = true)
                }
            } else {
                Log.w(TAG, "Hard link target still missing after extraction: ${linkTarget.path}")
            }
        }

        // Final progress report
        onProgress?.invoke(entriesExtracted)
        Log.i(TAG, "Extraction complete: $entriesExtracted entries to ${destDir.path}")
    }

    /**
     * Strips leading path components from a link target if it is an absolute-style
     * path that starts with the same prefix being stripped from entry names.
     *
     * Relative symlink targets (e.g. `../../bin/bash`) are left unchanged because
     * they are relative to the symlink location which is already correctly placed.
     * Absolute-looking targets that begin with the stripped prefix (e.g.
     * `debian-aarch64/usr/lib/foo`) are stripped so they resolve correctly within
     * the destination directory.
     */
    internal fun stripLinkTarget(linkName: String, entryName: String, stripComponents: Int): String {
        if (stripComponents <= 0) return linkName
        // Relative targets (starting with . or /) are already relative to the symlink — keep as-is
        if (linkName.startsWith(".") || linkName.startsWith("/")) return linkName
        // Absolute-style target that shares the stripped prefix — strip same components
        val parts = linkName.split("/")
        if (parts.size > stripComponents) {
            // Check if the prefix matches the entry's stripped prefix
            val entryParts = entryName.split("/")
            val linkPrefix = parts.take(stripComponents)
            val entryPrefix = entryParts.take(stripComponents)
            if (linkPrefix == entryPrefix) {
                return parts.drop(stripComponents).joinToString("/")
            }
        }
        return linkName
    }

    /**
     * Sets file permissions based on Unix mode bits from a tar entry.
     *
     * Maps owner/group/other read/write/execute bits to Java File permissions.
     * Note: Java's File permission model is limited compared to Unix — only
     * owner vs everyone distinction is available.
     */
    private fun setPermissions(file: File, mode: Int) {
        // Owner permissions
        file.setReadable((mode and 0b100_000_000) != 0, true)
        file.setWritable((mode and 0b010_000_000) != 0, true)
        file.setExecutable((mode and 0b001_000_000) != 0, true)

        // Others permissions (set non-owner-only)
        if ((mode and 0b000_000_100) != 0) file.setReadable(true, false)
        if ((mode and 0b000_000_010) != 0) file.setWritable(true, false)
        if ((mode and 0b000_000_001) != 0) file.setExecutable(true, false)
    }

    companion object {
        private const val TAG = "RootfsExtractor"
    }
}
