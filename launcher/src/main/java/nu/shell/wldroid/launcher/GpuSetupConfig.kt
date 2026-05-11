package nu.shell.wldroid.launcher

/**
 * Configuration for the GPU setup phase.
 *
 * Controls how [GpuSetupManager] runs `setup-gpu.sh` inside proot to
 * install Mesa packages, copy shim libraries, and create DRI symlinks.
 */
data class GpuSetupConfig(
    /** Base path in guest where shims and libs are installed. */
    val shimBasePath: String = "/opt/wldroid",
    /** Skip setup if shim checksums match (md5sum caching in the script). */
    val skipIfAlreadySetup: Boolean = true,
    /** Install Mesa packages via apt-get. Set to false if the consumer handles this. */
    val installMesaPackages: Boolean = true,
    /** Enable verbose Mesa/EGL debug logging in the guest. */
    val gpuDebugEnabled: Boolean = false,
)
