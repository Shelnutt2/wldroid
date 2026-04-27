package nu.shell.wldroid.virgl

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Manages the virgl_test_server process that provides GPU access to Wayland clients.
 *
 * The server runs with `--multi-clients` so it can accept connections from
 * multiple client processes simultaneously. Each process connects to the
 * vtest socket independently via the drm-shim.
 *
 * This class is designed to be used as a singleton — one server instance
 * serves all client sessions.
 */
class VirglServerManager(
    private val config: VirglConfig,
) {
    private val mutex = Mutex()

    @Volatile
    private var serverProcess: Process? = null

    @Volatile
    private var monitorJob: Job? = null

    /** Directory containing the vtest socket. */
    val socketDir: File
        get() = File(config.socketPath).parentFile ?: File("/tmp")

    /** Full path to the vtest socket file. */
    val socketPath: String
        get() = config.socketPath

    /** Path to the virgl_test_server binary. */
    val serverBinaryPath: String
        get() = config.virglBinaryPath

    /** Whether the server process is currently alive. */
    val isRunning: Boolean
        get() = serverProcess?.isAlive == true

    /**
     * Start virgl_test_server as a background process.
     *
     * @param gpuMode The GPU mode determining server flags.
     * @param scope Coroutine scope for the output monitor job.
     * @return `true` if the server started and the socket appeared within timeout.
     */
    suspend fun start(gpuMode: GpuMode, scope: CoroutineScope): Boolean = mutex.withLock {
        stopInternal()
        socketDir.mkdirs()

        val binaryFile = File(serverBinaryPath)
        if (!binaryFile.exists()) {
            Log.w(TAG, "virgl_test_server binary not found at $serverBinaryPath")
            return@withLock false
        }

        File(socketPath).delete() // clean stale socket

        val args = mutableListOf(
            serverBinaryPath,
            "--no-fork",
            "--multi-clients",
            "--socket-path=${socketPath}",
        )

        if (gpuMode == GpuMode.VENUS) {
            // Venus mode: skip GL/EGL initialization — Venus only needs Vulkan.
            args.add("--no-virgl")
            args.add("--venus")
        } else {
            // VirGL modes: use EGL surfaceless + GLES for GPU rendering.
            args.add("--use-egl-surfaceless")
            args.add("--use-gles")
        }

        val pb = ProcessBuilder(args)

        // Pass AHB registry socket path so virgl server can send AHB handles
        // to the compositor's registry receiver over a Unix domain socket.
        val ahbSocketPath = System.getenv("AHB_REGISTRY_SOCKET")
        if (ahbSocketPath != null) {
            pb.environment()["AHB_REGISTRY_SOCKET"] = ahbSocketPath
        }

        pb.redirectErrorStream(true)

        return@withLock try {
            val proc = pb.start()
            serverProcess = proc

            monitorJob = scope.launch(Dispatchers.IO) {
                try {
                    proc.inputStream.bufferedReader().use { reader ->
                        reader.lineSequence().forEach { line ->
                            Log.d(TAG, line)
                        }
                    }
                } catch (_: java.io.IOException) {
                    // Expected when stop() destroys the process.
                    Log.d(TAG, "Server output stream closed")
                }
            }

            val socketReady = waitForSocket(socketPath, timeoutMs = 5000)
            if (!socketReady) {
                Log.e(TAG, "virgl_test_server socket did not appear within 5 s")
                stopInternal()
            }
            socketReady
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start virgl_test_server", e)
            stopInternal()
            false
        }
    }

    /** Stop the virgl_test_server process gracefully. */
    suspend fun stop() = mutex.withLock {
        stopInternal()
    }

    /**
     * Internal stop logic — must only be called while [mutex] is held.
     * Moves the blocking [Process.waitFor] onto [Dispatchers.IO].
     */
    private suspend fun stopInternal() {
        monitorJob?.cancel()
        monitorJob = null
        serverProcess?.let { proc ->
            proc.destroy()
            withContext(Dispatchers.IO) {
                if (!proc.waitFor(3, TimeUnit.SECONDS)) {
                    proc.destroyForcibly()
                }
            }
        }
        serverProcess = null
        runCatching { File(socketPath).delete() }
    }

    /** Poll for the socket file to appear, returning `true` if it does within [timeoutMs]. */
    internal suspend fun waitForSocket(path: String, timeoutMs: Long): Boolean {
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < timeoutMs) {
            if (File(path).exists()) return true
            delay(100)
        }
        return false
    }

    companion object {
        private const val TAG = "VirglServer"
    }
}
