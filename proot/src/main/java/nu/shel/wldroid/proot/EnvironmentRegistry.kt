package nu.shel.wldroid.proot

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * High-level API for dynamic management of proot environments.
 *
 * Wraps [RootfsManager] and [RootfsStore] to provide a reactive interface
 * for creating, deleting, duplicating, importing, and exporting rootfs
 * environments. Tracks per-environment state via [StateFlow]s.
 */
class EnvironmentRegistry(
    private val rootfsManager: RootfsManager,
    private val rootfsStore: RootfsStore,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main),
) {
    /** Observable list of all tracked environments. */
    val environments: StateFlow<List<RootfsEnvironment>> =
        rootfsManager.getEnvironments().stateIn(
            scope = scope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList(),
        )

    private val stateFlows = ConcurrentHashMap<String, MutableStateFlow<EnvironmentProgress>>()

    private data class CreationEntry(val job: Job, val flowId: String)
    private val creationJobs = ConcurrentHashMap<String, CreationEntry>()

    /**
     * Service-scoped [CoroutineScope] used for long-running creation work.
     *
     * When non-null, [create] launches its coroutine in this scope so the work
     * survives Activity lifecycle changes (e.g. screen-off while a foreground
     * service is running). When null, the registry's own [scope] is used.
     *
     * Call [setServiceScope] from a bound service's `onCreate` and
     * [clearServiceScope] from `onDestroy`.
     */
    @Volatile
    private var serviceScope: CoroutineScope? = null

    /**
     * Binds a service-owned [CoroutineScope] for long-running work.
     *
     * While set, [create] launches its coroutine in the service scope instead
     * of the registry's default scope so that download/extraction survives the
     * Activity lifecycle as long as the foreground service is alive.
     */
    fun setServiceScope(scope: CoroutineScope?) {
        this.serviceScope = scope
    }

    /**
     * Clears the service scope, reverting to the registry's default scope.
     */
    fun clearServiceScope() {
        this.serviceScope = null
    }

    /**
     * Creates a new environment from the given [config].
     *
     * Downloads, extracts, and configures the rootfs, updating the
     * environment state flow throughout the process. The work is launched
     * in this registry's own [scope] so it survives caller lifecycle
     * changes (e.g. screen sleep).
     *
     * If a creation job for the same environment [name][EnvironmentConfig.name]
     * is already running, the existing [StateFlow] is returned without
     * starting a duplicate.
     *
     * @param config Configuration for the new environment
     * @return A [StateFlow] tracking the creation progress
     */
    fun create(config: EnvironmentConfig): StateFlow<EnvironmentProgress> {
        // Deduplicate by environment name — return existing flow if a job is active.
        synchronized(creationJobs) {
            val existingEntry = creationJobs[config.name]
            if (existingEntry != null && existingEntry.job.isActive) {
                val existingFlow = stateFlows[existingEntry.flowId]
                if (existingFlow != null) {
                    return existingFlow.asStateFlow()
                }
            }

            val id = UUID.randomUUID().toString()
            val stateFlow = getOrCreateStateFlow(id)
            val creatingMarker = File(rootfsManager.getEnvironmentDir(id), ".creating")

            val launchScope = serviceScope ?: scope
            val job = launchScope.launch(Dispatchers.IO) {
                try {
                    // Write .creating marker so interrupted installs can be
                    // cleaned up on next startup.
                    rootfsManager.getEnvironmentDir(id).mkdirs()
                    creatingMarker.createNewFile()

                    // Clean up a partial prior extraction for this id (no
                    // etc/os-release means extraction didn't finish).
                    val envDir = rootfsManager.getEnvironmentDir(id)
                    if (envDir.exists() && !File(envDir, "etc/os-release").exists()) {
                        // Keep the directory itself but clear contents for fresh extraction.
                        envDir.listFiles()?.forEach { child ->
                            if (child.name != ".creating") child.deleteRecursively()
                        }
                    }

                    rootfsManager.createEnvironment(
                        id = id,
                        name = config.name,
                        distro = config.distro,
                    ).collect { progress ->
                        val state = when (progress.status) {
                            RootfsStatus.DOWNLOADING -> EnvironmentState.DOWNLOADING
                            RootfsStatus.EXTRACTING -> EnvironmentState.EXTRACTING
                            RootfsStatus.INSTALLING -> EnvironmentState.INSTALLING
                            RootfsStatus.READY -> EnvironmentState.IDLE
                            RootfsStatus.ERROR -> EnvironmentState.ERROR
                        }
                        stateFlow.value = EnvironmentProgress(
                            state = state,
                            progress = progress.progress,
                        )
                    }

                    if (stateFlow.value.state == EnvironmentState.ERROR) {
                        Log.e(TAG, "Environment creation failed for '${config.name}' (id=$id)")
                    } else {
                        Log.i(TAG, "Environment '${config.name}' (id=$id) created successfully")
                    }
                } catch (e: CancellationException) {
                    stateFlow.value = EnvironmentProgress(
                        state = EnvironmentState.ERROR,
                        message = "Setup was interrupted",
                    )
                    throw e // re-throw to let coroutine machinery handle cancellation
                } catch (e: Exception) {
                    stateFlow.value = EnvironmentProgress(EnvironmentState.ERROR, message = e.message ?: "")
                    Log.e(TAG, "Failed to create environment '${config.name}'", e)
                } finally {
                    creatingMarker.delete()
                    creationJobs.remove(config.name)
                }
            }

            creationJobs[config.name] = CreationEntry(job, id)
            return stateFlow.asStateFlow()
        }
    }

    /**
     * Deletes an environment by ID, removing both the filesystem and store entry.
     */
    suspend fun delete(id: String) {
        getOrCreateStateFlow(id).value = EnvironmentProgress(EnvironmentState.STOPPING)
        rootfsManager.deleteEnvironment(id)
        stateFlows.remove(id)
    }

    /**
     * Duplicates an environment by copying its rootfs directory.
     *
     * @param id The source environment ID
     * @param newName Display name for the duplicate
     * @return The new [RootfsEnvironment]
     */
    suspend fun duplicate(id: String, newName: String): RootfsEnvironment =
        withContext(Dispatchers.IO) {
            val source = rootfsManager.getEnvironments().first().firstOrNull { it.id == id }
                ?: throw IllegalArgumentException("Environment '$id' not found")

            val newId = UUID.randomUUID().toString()
            val sourceDir = File(source.rootfsPath)
            val destDir = rootfsManager.getEnvironmentDir(newId)

            require(sourceDir.exists()) { "Source rootfs directory does not exist: ${sourceDir.path}" }

            val stateFlow = getOrCreateStateFlow(newId)
            stateFlow.value = EnvironmentProgress(EnvironmentState.INSTALLING)

            try {
                sourceDir.copyRecursively(destDir, overwrite = true)
            } catch (e: Exception) {
                stateFlow.value = EnvironmentProgress(EnvironmentState.ERROR, message = e.message ?: "")
                destDir.deleteRecursively()
                throw IllegalStateException("Failed to duplicate environment", e)
            }

            val env = RootfsEnvironment(
                id = newId,
                name = newName,
                rootfsPath = destDir.absolutePath,
                distro = source.distro,
                createdAt = System.currentTimeMillis(),
                sizeBytes = rootfsManager.getDiskUsage(newId),
                status = RootfsStatus.READY,
            )
            rootfsStore.addEnvironment(env)
            stateFlow.value = EnvironmentProgress(EnvironmentState.IDLE)
            env
        }

    /**
     * Imports a rootfs from an existing tar.xz tarball.
     *
     * @param tarballPath Path to the tar.xz file to import
     * @param name Display name for the imported environment
     * @return The imported [RootfsEnvironment]
     */
    suspend fun importRootfs(tarballPath: String, name: String = "Imported"): RootfsEnvironment =
        withContext(Dispatchers.IO) {
            val newId = UUID.randomUUID().toString()
            val destDir = rootfsManager.getEnvironmentDir(newId)
            val stateFlow = getOrCreateStateFlow(newId)

            stateFlow.value = EnvironmentProgress(EnvironmentState.EXTRACTING)
            try {
                val extractor = RootfsExtractor()
                extractor.extract(File(tarballPath), destDir, stripComponents = 1)
            } catch (e: Exception) {
                stateFlow.value = EnvironmentProgress(EnvironmentState.ERROR, message = e.message ?: "")
                destDir.deleteRecursively()
                throw IllegalStateException("Failed to import rootfs from $tarballPath", e)
            }

            stateFlow.value = EnvironmentProgress(EnvironmentState.INSTALLING)
            rootfsManager.configureRootfs(destDir)

            val env = RootfsEnvironment(
                id = newId,
                name = name,
                rootfsPath = destDir.absolutePath,
                createdAt = System.currentTimeMillis(),
                sizeBytes = rootfsManager.getDiskUsage(newId),
                status = RootfsStatus.READY,
            )
            rootfsStore.addEnvironment(env)
            stateFlow.value = EnvironmentProgress(EnvironmentState.IDLE)
            env
        }

    /**
     * Exports an environment's rootfs directory as a tar.xz archive.
     *
     * @param id The environment ID to export
     * @param outputPath Destination path for the tar.xz file
     */
    suspend fun exportRootfs(id: String, outputPath: String) = withContext(Dispatchers.IO) {
        val env = rootfsManager.getEnvironments().first().firstOrNull { it.id == id }
            ?: throw IllegalArgumentException("Environment '$id' not found")

        val sourceDir = File(env.rootfsPath)
        require(sourceDir.exists()) { "Rootfs directory does not exist: ${sourceDir.path}" }

        // Use tar command for export (available on Android via toybox)
        val pb = ProcessBuilder(
            "tar", "-cJf", outputPath, "-C", sourceDir.parent!!, sourceDir.name,
        )
        pb.redirectErrorStream(true)
        val process = pb.start()
        val output = process.inputStream.bufferedReader().readText()
        val exitCode = process.waitFor()

        if (exitCode != 0) {
            throw RuntimeException("Export failed (exit=$exitCode): $output")
        }

        Log.i(TAG, "Exported environment '$id' to $outputPath")
    }

    /**
     * Recovers orphaned rootfs directories that exist on disk but are not
     * tracked in the environment store.
     *
     * This can happen if the app crashes between rootfs extraction and the
     * store write, or if store data is cleared or corrupted. This method
     * is the intentional recovery API for downstream apps to call at startup
     * instead of manually reconstructing [RootfsEnvironment] records.
     *
     * Adopted environments use the directory name as ID and name, with
     * distro inferred from the rootfs `etc/os-release` file.
     *
     * @return List of newly adopted [RootfsEnvironment] records, empty if
     *         no orphans were found
     * @see RootfsManager.adoptOrphanedEnvironments
     */
    suspend fun recoverOrphanedEnvironments(): List<RootfsEnvironment> =
        rootfsManager.adoptOrphanedEnvironments()

    /**
     * Returns a [StateFlow] tracking the current state of an environment.
     */
    fun getState(id: String): StateFlow<EnvironmentProgress> =
        getOrCreateStateFlow(id).asStateFlow()

    /**
     * Returns the list of available distro templates.
     */
    fun availableDistros(): List<DistroTemplate> = DistroTemplate.entries

    private fun getOrCreateStateFlow(id: String): MutableStateFlow<EnvironmentProgress> =
        stateFlows.getOrPut(id) { MutableStateFlow(EnvironmentProgress(EnvironmentState.IDLE)) }

    companion object {
        private const val TAG = "EnvironmentRegistry"
    }
}

/**
 * Configuration for creating a new proot environment.
 */
data class EnvironmentConfig(
    val name: String,
    val distro: DistroTemplate,
    val bindMounts: List<BindMount> = emptyList(),
    val environmentVariables: Map<String, String> = emptyMap(),
)

/**
 * Observable state of a proot environment.
 */
enum class EnvironmentState {
    IDLE,
    DOWNLOADING,
    EXTRACTING,
    INSTALLING,
    RUNNING,
    STOPPING,
    STOPPED,
    ERROR,
}
