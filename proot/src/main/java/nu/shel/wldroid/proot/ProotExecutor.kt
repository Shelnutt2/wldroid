package nu.shel.wldroid.proot

import android.system.Os
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Builds and executes proot commands.
 *
 * Encapsulates the logic for constructing proot command lines with appropriate
 * bind mounts, environment variables, and flags. Extracted from RootfsManager
 * for better separation of concerns and testability.
 */
class ProotExecutor(private val config: ProotConfig) {

    /**
     * Fully constructed proot command with arguments and environment.
     * Exposed for testing without executing processes.
     */
    data class ProotCommand(
        val args: List<String>,
        val environment: Map<String, String>,
    )

    /**
     * Builds a [ProcessBuilder] for running a command inside a proot environment.
     *
     * Sets up bind mounts for /dev, /dev/shm, /tmp, /dev/dri, /proc, /sys,
     * and configures the environment for a guest Linux session.
     *
     * @param environment The rootfs environment to run in
     * @param command The command and arguments to execute inside proot
     * @param envVars Additional environment variables to set (caller overrides defaults)
     * @param bindMounts Additional host:guest bind mounts
     * @return A configured [ProcessBuilder] ready to start
     * @throws IllegalArgumentException if the rootfs directory doesn't exist
     */
    fun buildCommand(
        environment: RootfsEnvironment,
        command: List<String>,
        envVars: Map<String, String> = emptyMap(),
        bindMounts: List<BindMount> = emptyList(),
        guestEnvVars: Map<String, String> = emptyMap(),
    ): ProcessBuilder {
        val cmd = buildProotCommand(environment, command, envVars, bindMounts, guestEnvVars)
        val pb = ProcessBuilder(cmd.args)
        // Clear the inherited Android environment to avoid polluting the guest
        // with host-specific vars (e.g., Android PATH, BOOTCLASSPATH, etc.)
        pb.environment().clear()
        pb.environment().putAll(cmd.environment)
        pb.redirectErrorStream(true)
        return pb
    }

    /**
     * Runs a command inside a proot environment and waits for completion.
     *
     * @param environment The rootfs environment to run in
     * @param command The command and arguments to execute inside proot
     * @param envVars Additional environment variables
     * @param bindMounts Additional host:guest bind mounts
     * @param onOutput Optional callback invoked for each line of stdout/stderr
     * @return The process exit code
     */
    suspend fun runInProot(
        environment: RootfsEnvironment,
        command: List<String>,
        envVars: Map<String, String> = emptyMap(),
        bindMounts: List<BindMount> = emptyList(),
        guestEnvVars: Map<String, String> = emptyMap(),
        onOutput: ((String) -> Unit)? = null,
    ): Int = withContext(Dispatchers.IO) {
        val pb = buildCommand(environment, command, envVars, bindMounts, guestEnvVars)
        val process = pb.start()

        if (onOutput != null) {
            process.inputStream.bufferedReader().useLines { lines ->
                for (line in lines) {
                    onOutput(line)
                }
            }
        }

        process.waitFor()
    }

    /**
     * Builds the proot command arguments and environment variables without starting a process.
     *
     * @param environment The rootfs environment to target
     * @param command The command and arguments to execute inside proot
     * @param envVars Additional environment variables
     * @param extraBindMounts Additional bind mounts beyond the defaults
     * @return A [ProotCommand] containing the full argument list and environment map
     */
    internal fun buildProotCommand(
        environment: RootfsEnvironment,
        command: List<String>,
        envVars: Map<String, String> = emptyMap(),
        extraBindMounts: List<BindMount> = emptyList(),
        guestEnvVars: Map<String, String> = emptyMap(),
    ): ProotCommand {
        val rootfsPath = environment.rootfsPath

        require(File(rootfsPath, "etc/os-release").exists()) {
            "Rootfs environment '${environment.id}' is not ready or does not exist"
        }

        val cacheDir = File(config.cacheDir)
        cacheDir.mkdirs()

        // Create host-side directories for /dev/shm and /tmp.
        // Chromium/Electron FATAL crashes if /dev/shm doesn't exist (POSIX shm_open),
        // and Android's /dev has no /dev/shm tmpfs.
        val shmDir = File(cacheDir, "proot-shm").also { it.mkdirs() }
        val tmpDir = File(cacheDir, "proot-tmp").also { it.mkdirs() }

        val prootArgs = mutableListOf<String>()
        prootArgs.add(config.prootBinaryPath)

        if (config.fakeRoot) {
            prootArgs.add("-0")
        }

        prootArgs.addAll(listOf(
            "-r", rootfsPath,
            "-b", "/dev",
            "-b", "${shmDir.absolutePath}:/dev/shm",
            "-b", "${tmpDir.absolutePath}:/tmp",
            "-b", "/proc",
        ))

        // Create /dev/dri stub directory with fake device nodes for GPU passthrough.
        val devDriDir = File(cacheDir, "proot-dev-dri").also { it.mkdirs() }
        File(devDriDir, "renderD128").apply { if (!exists()) createNewFile() }
        File(devDriDir, "card0").apply { if (!exists()) createNewFile() }

        // Create fake sysfs tree for the virtual GPU device.
        val fakeSysDir = setupFakeSysfs(cacheDir)

        prootArgs.addAll(listOf(
            "-b", "${devDriDir.absolutePath}:/dev/dri",
            "-b", "/sys",
            "-b", "${fakeSysDir.absolutePath}/dev/char:/sys/dev/char",
            "-b", "${fakeSysDir.absolutePath}/bus/platform:/sys/bus/platform",
        ))

        if (config.link2symlink) {
            prootArgs.add("--link2symlink")
        }

        prootArgs.addAll(listOf("-w", "/root"))

        // Add user-specified bind mounts
        for (mount in extraBindMounts) {
            prootArgs.add("-b")
            prootArgs.add("${mount.hostPath}:${mount.guestPath}")
        }

        // Guest-only env vars (e.g. LD_PRELOAD with guest paths) are injected via
        // /usr/bin/env inside the proot guest, NOT on the host ProcessBuilder environment.
        // This prevents the host dynamic linker from trying to resolve guest-side paths.
        if (guestEnvVars.isNotEmpty()) {
            prootArgs.add("/usr/bin/env")
            for ((key, value) in guestEnvVars) {
                prootArgs.add("$key=$value")
            }
        }
        prootArgs.addAll(command)

        val processEnv = buildMap {
            put("PROOT_TMP_DIR", cacheDir.absolutePath)
            if (config.prootLoaderPath.isNotEmpty()) {
                put("PROOT_LOADER", config.prootLoaderPath)
            }
            put("PROOT_IGNORE_MISSING_BINDINGS", "1")
            // Standard Linux environment for the guest rootfs
            put("PATH", "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin")
            put("HOME", "/root")
            put("USER", "root")
            put("LANG", "C.UTF-8")
            put("TERM", "xterm-256color")
            // Chromium sandbox conflicts with proot's ptrace — disable it
            put("ELECTRON_NO_SANDBOX", "1")
            // Prevent X11 fallback — force Wayland only
            put("DISPLAY", "")
            putAll(envVars) // Caller env overrides defaults
        }

        return ProotCommand(args = prootArgs, environment = processEnv)
    }

    /**
     * Creates a fake sysfs tree so Electron's statically-linked drmGetDevices2 can
     * enumerate our virtual GPU device.
     *
     * Tree structure:
     * ```
     * proot-sys/
     * ├── bus/platform/                              (symlink target for subsystem)
     * └── dev/char/226:128/                          (directory, NOT symlink)
     *     ├── dev                                    (226:128)
     *     ├── uevent                                 (MAJOR=226, MINOR=128, DEVNAME=dri/renderD128)
     *     └── device/                                (virtual GPU device info)
     *         ├── uevent                             (DRIVER=virtio-gpu)
     *         ├── subsystem → ../../../../bus/platform
     *         └── drm/
     *             ├── renderD128/dev                  (226:128)
     *             └── card0/dev                       (226:0)
     * ```
     */
    private fun setupFakeSysfs(cacheDir: File): File {
        val sysDir = File(cacheDir, "proot-sys")

        // bus/platform — symlink target for the subsystem link
        File(sysDir, "bus/platform").mkdirs()

        // dev/char/226:128 — a DIRECTORY representing the render node
        val charNodeDir = File(sysDir, "dev/char/226:128")
        charNodeDir.deleteRecursively()
        charNodeDir.mkdirs()

        File(charNodeDir, "dev").writeText("226:128\n")
        File(charNodeDir, "uevent").writeText(
            "MAJOR=226\nMINOR=128\nDEVNAME=dri/renderD128\n",
        )

        val deviceDir = File(charNodeDir, "device")
        deviceDir.mkdirs()

        File(deviceDir, "uevent").writeText(
            "DRIVER=virtio-gpu\nMODALIAS=platform:virtio-gpu\n",
        )

        // subsystem symlink → ../../../../bus/platform
        val subsystemLink = File(deviceDir, "subsystem")
        subsystemLink.delete()
        Os.symlink("../../../../bus/platform", subsystemLink.absolutePath)

        // drm/renderD128
        val renderDir = File(deviceDir, "drm/renderD128")
        renderDir.mkdirs()
        File(renderDir, "dev").writeText("226:128\n")

        // drm/card0
        val cardDir = File(deviceDir, "drm/card0")
        cardDir.mkdirs()
        File(cardDir, "dev").writeText("226:0\n")

        return sysDir
    }

    companion object {
        private const val TAG = "ProotExecutor"
    }
}
