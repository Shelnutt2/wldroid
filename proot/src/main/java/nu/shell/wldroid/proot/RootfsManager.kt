package nu.shell.wldroid.proot

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

/**
 * Manages rootfs environments and their lifecycle (create, delete, list).
 *
 * Each environment lives under `<rootfsBaseDir>/<id>/` and contains a minimal
 * Linux root filesystem that can be entered via proot.
 *
 * This class handles downloading, verifying, and extracting rootfs tarballs,
 * as well as configuring the guest filesystem for use under proot.
 */
class RootfsManager(
    private val rootfsBaseDir: File,
    private val rootfsStore: RootfsStore,
    private val downloader: RootfsDownloader,
    private val extractor: RootfsExtractor,
) {
    private val createMutex = Mutex()

    /**
     * Returns true if the environment directory exists and contains a valid rootfs
     * (checked by the presence of /etc/os-release).
     */
    fun hasEnvironment(id: String): Boolean {
        val osRelease = File(rootfsBaseDir, "$id/etc/os-release")
        return osRelease.exists()
    }

    /**
     * Deletes the environment's filesystem directory and removes it from the store.
     */
    suspend fun deleteEnvironment(id: String) {
        val envDir = File(rootfsBaseDir, id)
        if (!envDir.deleteRecursively()) {
            Log.w(TAG, "Failed to fully delete rootfs directory: ${envDir.absolutePath}")
        }
        rootfsStore.removeEnvironment(id)
    }

    /**
     * Returns a flow of all tracked environments from the store.
     */
    fun getEnvironments(): Flow<List<RootfsEnvironment>> = rootfsStore.getEnvironments()

    /**
     * Calculates the total disk usage of an environment directory in bytes.
     */
    fun getDiskUsage(envId: String): Long {
        val envDir = File(rootfsBaseDir, envId)
        if (!envDir.exists()) return 0
        return envDir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }

    /**
     * Returns the total disk usage across all environment directories in bytes.
     */
    fun getTotalDiskUsage(): Long {
        if (!rootfsBaseDir.exists()) return 0
        return rootfsBaseDir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }

    /**
     * Returns the rootfs directory path for an environment.
     */
    fun getEnvironmentDir(id: String): File = File(rootfsBaseDir, id)

    /**
     * Creates a new rootfs environment with the given id and distro template.
     *
     * Downloads and extracts a rootfs tarball, then registers the environment
     * in the store. Emits [RootfsProgress] updates as the pipeline moves through
     * downloading, verification, and extraction.
     *
     * @param id Unique identifier for the new environment
     * @param name Human-readable display name
     * @param distro The distro template to use for this environment
     */
    fun createEnvironment(
        id: String,
        name: String = id,
        distro: DistroTemplate = DistroTemplate.DEBIAN_TRIXIE,
    ): Flow<RootfsProgress> = channelFlow {
        if (createMutex.isLocked) {
            send(RootfsProgress(RootfsStatus.DOWNLOADING, -1f))
        }
        createMutex.withLock {
            if (hasEnvironment(id)) {
                send(RootfsProgress(RootfsStatus.READY))
                return@channelFlow
            }

            val rootfsUrl = distro.downloadUrl
            val rootfsSha256 = distro.sha256

            // 1. Download (or use cache)
            send(RootfsProgress(RootfsStatus.DOWNLOADING))
            val tarball = downloader.getCachedTarball(rootfsUrl)
                ?: run {
                    val dest = downloader.getCacheFile(rootfsUrl)
                    downloader.download(rootfsUrl, dest) { bytesRead, totalBytes ->
                        val progress = if (totalBytes > 0) bytesRead.toFloat() / totalBytes else -1f
                        trySend(RootfsProgress(RootfsStatus.DOWNLOADING, progress))
                    }
                    dest
                }

            // 2. Verify SHA-256
            send(RootfsProgress(RootfsStatus.DOWNLOADING, progress = 0.99f))
            if (!downloader.verifySha256(tarball, rootfsSha256)) {
                tarball.delete()
                Log.e(TAG, "SHA-256 verification failed for rootfs tarball")
                send(RootfsProgress(RootfsStatus.ERROR))
                return@channelFlow
            }

            // 3. Extract
            send(RootfsProgress(RootfsStatus.EXTRACTING))
            val envDir = File(rootfsBaseDir, id)
            envDir.mkdirs()
            try {
                extractor.extract(tarball, envDir, stripComponents = 1) { entriesExtracted ->
                    val progress = (entriesExtracted / 40000f).coerceAtMost(0.99f)
                    trySend(RootfsProgress(RootfsStatus.EXTRACTING, progress))
                }
            } catch (e: Exception) {
                Log.e(TAG, "Rootfs extraction failed", e)
                envDir.deleteRecursively()
                send(RootfsProgress(RootfsStatus.ERROR))
                return@channelFlow
            }

            // 4. Configure rootfs environment files
            send(RootfsProgress(RootfsStatus.INSTALLING))
            configureRootfs(envDir)

            // 5. Validate extraction
            if (!File(envDir, "etc/os-release").exists()) {
                Log.e(TAG, "Rootfs extraction incomplete: etc/os-release not found")
                envDir.deleteRecursively()
                send(RootfsProgress(RootfsStatus.ERROR))
                return@channelFlow
            }

            // 6. Create .l2s directory for proot link2symlink
            File(envDir, ".l2s").mkdirs()

            // 7. Save to store
            val env = RootfsEnvironment(
                id = id,
                name = name,
                rootfsPath = envDir.absolutePath,
                distro = distro.name,
                createdAt = System.currentTimeMillis(),
                sizeBytes = getDiskUsage(id),
                status = RootfsStatus.READY,
            )
            rootfsStore.addEnvironment(env)
            send(RootfsProgress(RootfsStatus.READY))
        }
    }.flowOn(Dispatchers.IO)

    /**
     * Writes environment configuration files into the rootfs after extraction.
     *
     * This mirrors what Termux's proot-distro does in its post-install step:
     * sets up PATH, profile scripts, and DNS resolution so the guest rootfs
     * behaves like a normal Linux environment.
     */
    internal fun configureRootfs(envDir: File) {
        val etcDir = File(envDir, "etc")
        etcDir.mkdirs()

        // Write /etc/environment (matches proot-distro post-install)
        File(etcDir, "environment").writeText(
            "PATH=\"/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin\"\n",
        )

        // Write /etc/profile.d/wldroid-path.sh for login shells
        val profileDDir = File(etcDir, "profile.d")
        profileDDir.mkdirs()
        File(profileDDir, "wldroid-path.sh").writeText(
            "# Set by WLDroid during rootfs setup\n" +
                "export PATH=\"/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin\"\n",
        )

        // Create /dev/shm for Chromium shared memory (POSIX shm_open)
        val devShm = File(envDir, "dev/shm")
        if (!devShm.exists()) {
            devShm.mkdirs()
        }

        // Ensure /tmp exists with correct permissions
        val tmpDir = File(envDir, "tmp")
        tmpDir.mkdirs()
        tmpDir.setReadable(true, false)
        tmpDir.setWritable(true, false)
        tmpDir.setExecutable(true, false)

        // Write initial resolv.conf as fallback. ProotDnsManager can overwrite
        // this with VPN-aware DNS before each proot invocation.
        val resolvConf = File(etcDir, "resolv.conf")
        resolvConf.delete() // Handle symlinks (Trixie symlinks to systemd-resolved stub)
        resolvConf.writeText("nameserver 8.8.8.8\nnameserver 8.8.4.4\n")

        // --- Create default user (UID 1000) for non-root app execution ---
        val passwdFile = File(etcDir, "passwd")
        val groupFile = File(etcDir, "group")
        val shadowFile = File(etcDir, "shadow")
        val sudoersDir = File(etcDir, "sudoers.d")
        val homeDir = File(envDir, "home/user")

        homeDir.mkdirs()

        // Append user to /etc/passwd (idempotent)
        if (!passwdFile.exists() || !passwdFile.readText().contains("user:")) {
            passwdFile.appendText("user:x:1000:1000:Default User:/home/user:/bin/bash\n")
        }

        // Append user group to /etc/group (idempotent)
        val groupText = if (groupFile.exists()) groupFile.readText() else ""
        if (!groupText.contains("user:x:1000:")) {
            groupFile.appendText("user:x:1000:user\n")
        }

        // Add user to sudo group (create or append, idempotent)
        if (groupFile.exists()) {
            val currentGroupText = groupFile.readText()
            if (!currentGroupText.contains(Regex("sudo:x:\\d+:.*user"))) {
                if (currentGroupText.contains(Regex("sudo:x:\\d+:"))) {
                    val updated = currentGroupText.replace(Regex("(sudo:x:\\d+:)(.*)")) { match ->
                        val prefix = match.groupValues[1]
                        val members = match.groupValues[2]
                        if (members.isBlank()) "${prefix}user"
                        else "${prefix}${members},user"
                    }
                    groupFile.writeText(updated)
                } else {
                    groupFile.appendText("sudo:x:27:user\n")
                }
            }
        }

        // Append shadow entry (locked password, idempotent)
        if (!shadowFile.exists() || !shadowFile.readText().contains("user:")) {
            shadowFile.appendText("user:!:19000:0:99999:7:::\n")
        }

        // Add passwordless sudo for user
        sudoersDir.mkdirs()
        File(sudoersDir, "user").writeText("user ALL=(ALL) NOPASSWD:ALL\n")
    }

    companion object {
        private const val TAG = "RootfsManager"
    }
}
