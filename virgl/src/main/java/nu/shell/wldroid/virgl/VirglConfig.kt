package nu.shell.wldroid.virgl

/**
 * Configuration for a VirGL server session.
 *
 * @property virglBinaryPath Path to the virgl_test_server binary.
 * @property socketPath Unix socket path the server listens on.
 * @property gpuMode Requested GPU rendering mode.
 * @property venusEnabled Whether to enable Venus Vulkan proxy support.
 * @property useZinkBackend Whether to prefer the Vulkan/Zink backend over GLES.
 */
data class VirglConfig(
    val virglBinaryPath: String = "",
    val socketPath: String = "",
    val gpuMode: GpuMode = GpuMode.AUTO,
    val venusEnabled: Boolean = false,
    val useZinkBackend: Boolean = false,
)
