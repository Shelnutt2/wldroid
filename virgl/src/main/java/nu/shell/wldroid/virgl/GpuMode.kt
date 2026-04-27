package nu.shell.wldroid.virgl

/**
 * Available GPU rendering modes for the Wayland compositor.
 *
 * Each mode represents a different rendering pipeline with varying
 * hardware requirements and performance characteristics.
 */
enum class GpuMode(val displayName: String, val description: String) {
    SOFTWARE("Software", "CPU-only rendering via pixman, no GPU acceleration"),
    VIRGL_GLES("VirGL GLES", "VirGL server with Android GLES backend (universal)"),
    VIRGL_ZINK("VirGL Zink", "VirGL server with Vulkan/Zink backend (better perf)"),
    VENUS("Venus", "Venus Vulkan proxy via vtest (experimental)"),
    TURNIP_DIRECT("Turnip Direct", "Direct Adreno GPU via KGSL (Qualcomm only)"),
    AUTO("Auto-detect", "Automatically select the best available GPU mode");

    /** Whether this mode requires virgl_test_server to be running. */
    val requiresVirglServer: Boolean
        get() = this == VIRGL_GLES || this == VIRGL_ZINK || this == VENUS

    companion object {
        /** Parse a mode name, defaulting to [AUTO] if unrecognised. */
        fun fromString(value: String): GpuMode =
            entries.find { it.name == value } ?: AUTO
    }
}
