package nu.shell.wldroid.virgl

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import java.io.File
import java.io.FileInputStream
/**
 * Detects GPU capabilities and determines the best available rendering mode.
 *
 * Checks for:
 * - Qualcomm KGSL device node (`/dev/kgsl-3d0`) for Turnip direct rendering
 * - Adreno GPU model via sysfs
 * - Vulkan hardware support via Android PackageManager
 */
class GpuCapabilityDetector(
    private val context: Context,
) {
    /**
     * Detect the best GPU mode based on hardware capabilities.
     *
     * Priority: Turnip Direct > VirGL Zink > VirGL GLES > Software.
     * Venus is never auto-selected — it must be explicitly requested.
     *
     * @param virglAvailable Whether the virgl_test_server binary is present.
     */
    fun detectBestGpuMode(virglAvailable: Boolean = true): GpuMode {
        if (isKgslAccessible() && isAdrenoGpu()) {
            Log.d(TAG, "Detected GPU mode: TURNIP_DIRECT (kgsl=true, adreno=true)")
            return GpuMode.TURNIP_DIRECT
        }

        val mode = when {
            !virglAvailable -> GpuMode.SOFTWARE
            hasVulkanSupport() -> GpuMode.VIRGL_ZINK
            else -> GpuMode.VIRGL_GLES
        }
        Log.d(
            TAG,
            "Detected GPU mode: $mode (virglAvailable=$virglAvailable, " +
                "vulkan=${hasVulkanSupport()}, kgsl=${isKgslAccessible()}, adreno=${isAdrenoGpu()})",
        )
        return mode
    }

    /** Whether `/dev/kgsl-3d0` is accessible (Qualcomm KGSL driver). */
    fun isKgslAccessible(): Boolean = try {
        FileInputStream("/dev/kgsl-3d0").use { true }
    } catch (_: Exception) {
        false
    }

    /**
     * Check if the device has a Qualcomm Adreno GPU by reading
     * `/sys/class/kgsl/kgsl-3d0/gpu_model`. This sysfs node is
     * Qualcomm-specific and only exists on devices with the KGSL driver.
     */
    fun isAdrenoGpu(): Boolean {
        return try {
            val gpuModel = File("/sys/class/kgsl/kgsl-3d0/gpu_model").readText().trim()
            gpuModel.isNotBlank()
        } catch (_: Exception) {
            false
        }
    }

    /** Whether the device advertises Vulkan hardware support. */
    fun hasVulkanSupport(): Boolean {
        return context.packageManager.systemAvailableFeatures.any {
            it.name == PackageManager.FEATURE_VULKAN_HARDWARE_LEVEL
        }
    }

    /** Human-readable GPU capability report for diagnostics. */
    fun getGpuInfo(): String {
        return buildString {
            appendLine("GPU Capability Report:")
            appendLine("  KGSL accessible: ${isKgslAccessible()}")
            appendLine("  Adreno GPU: ${isAdrenoGpu()}")
            appendLine("  Vulkan support: ${hasVulkanSupport()}")
            appendLine("  Turnip eligible: ${isKgslAccessible() && isAdrenoGpu()}")
            appendLine("  Recommended mode: ${detectBestGpuMode()}")
            val kgslDir = File("/sys/class/kgsl/kgsl-3d0")
            if (kgslDir.exists()) {
                runCatching {
                    val gpuModel = File(kgslDir, "gpu_model").readText().trim()
                    appendLine("  GPU model: $gpuModel")
                }
            }
        }
    }

    /** Compact one-line GPU summary for settings UI. */
    fun getGpuSummary(): String {
        val vulkan = if (hasVulkanSupport()) "Vulkan" else "No Vulkan"
        val kgsl = if (isKgslAccessible()) "KGSL" else "No KGSL"
        val gpuModel = try {
            File("/sys/class/kgsl/kgsl-3d0/gpu_model").readText().trim()
        } catch (_: Exception) {
            null
        }
        return if (gpuModel != null) "$gpuModel • $vulkan • $kgsl" else "$vulkan • $kgsl"
    }

    companion object {
        private const val TAG = "GpuCapabilityDetector"
    }
}
