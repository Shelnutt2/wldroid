package nu.shel.wldroid.virgl

import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

/**
 * Instrumented tests for [GpuCapabilityDetector] — real GPU detection on
 * an Android device or emulator.
 */
@RunWith(JUnit4::class)
class GpuCapabilityDetectorTest {

    @Test
    fun detectBestGpuModeReturnsValidMode() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val detector = GpuCapabilityDetector(context)
        val mode = detector.detectBestGpuMode(virglAvailable = true)

        assertThat(mode).isNotNull()
        assertThat(mode).isNotEqualTo(GpuMode.AUTO) // Auto is resolved, never returned
        assertThat(mode).isNotEqualTo(GpuMode.VENUS) // Venus is never auto-selected
    }

    @Test
    fun detectBestGpuModeWithoutVirglReturnsSoftwareOrTurnip() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val detector = GpuCapabilityDetector(context)
        val mode = detector.detectBestGpuMode(virglAvailable = false)

        // Without virgl, only SOFTWARE or TURNIP_DIRECT are possible
        assertThat(mode).isAnyOf(GpuMode.SOFTWARE, GpuMode.TURNIP_DIRECT)
    }

    @Test
    fun emulatorDoesNotHaveKgsl() {
        // x86_64 emulators don't have Qualcomm KGSL hardware
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val detector = GpuCapabilityDetector(context)

        // On a real Qualcomm device this would be true, but on emulator it's false
        val kgslAccessible = detector.isKgslAccessible()
        val isAdreno = detector.isAdrenoGpu()

        // Both should be consistent: if no kgsl, no adreno
        if (!kgslAccessible) {
            assertThat(isAdreno).isFalse()
        }
    }

    @Test
    fun detectBestGpuModeIsConsistentAcrossCalls() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val detector = GpuCapabilityDetector(context)

        val mode1 = detector.detectBestGpuMode(virglAvailable = true)
        val mode2 = detector.detectBestGpuMode(virglAvailable = true)

        // Same hardware should always return the same mode
        assertThat(mode1).isEqualTo(mode2)
    }

    @Test
    fun gpuModeRequiresVirglServerIsCorrect() {
        assertThat(GpuMode.SOFTWARE.requiresVirglServer).isFalse()
        assertThat(GpuMode.TURNIP_DIRECT.requiresVirglServer).isFalse()
        assertThat(GpuMode.VIRGL_GLES.requiresVirglServer).isTrue()
        assertThat(GpuMode.VIRGL_ZINK.requiresVirglServer).isTrue()
        assertThat(GpuMode.VENUS.requiresVirglServer).isTrue()
        assertThat(GpuMode.AUTO.requiresVirglServer).isFalse()
    }
}
