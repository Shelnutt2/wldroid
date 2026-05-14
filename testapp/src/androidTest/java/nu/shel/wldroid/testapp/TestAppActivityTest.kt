package nu.shel.wldroid.testapp

import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import nu.shel.wldroid.compositor.CompositorConfig
import nu.shel.wldroid.compositor.CompositorState
import nu.shel.wldroid.shims.ShimConfig
import nu.shel.wldroid.shims.ShimExtractor
import nu.shel.wldroid.virgl.GpuCapabilityDetector
import nu.shel.wldroid.virgl.GpuMode
import nu.shel.wldroid.virgl.VirglConfig
import nu.shel.wldroid.virgl.VirglState
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

/**
 * Cross-module integration tests that exercise the full library stack.
 * These tests verify that all modules work together correctly on a real device.
 */
@RunWith(JUnit4::class)
class TestAppActivityTest {

    @Test
    fun allModulesCanBeImported() {
        // Verify all module types are accessible from the testapp
        assertThat(CompositorConfig::class.java).isNotNull()
        assertThat(CompositorState::class.java).isNotNull()
        assertThat(GpuMode::class.java).isNotNull()
        assertThat(VirglConfig::class.java).isNotNull()
        assertThat(VirglState::class.java).isNotNull()
        assertThat(ShimConfig::class.java).isNotNull()
        assertThat(ShimExtractor::class.java).isNotNull()
    }

    @Test
    fun gpuDetectionAndConfigIntegration() {
        // Test the flow: detect GPU → create compositor config → create shim config
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val detector = GpuCapabilityDetector(context)
        val gpuMode = detector.detectBestGpuMode(virglAvailable = false)

        // Create compositor config with detected mode
        val compositorConfig = CompositorConfig(
            cacheDir = context.cacheDir.absolutePath,
            gpuMode = gpuMode.name,
        )
        assertThat(compositorConfig.gpuMode).isEqualTo(gpuMode.name)

        // Create shim config matching the GPU mode
        val shimConfig = ShimConfig.forGpuMode(gpuMode.name)
        assertThat(shimConfig).isNotNull()

        // Create virgl config
        val virglConfig = VirglConfig(gpuMode = gpuMode)
        assertThat(virglConfig.gpuMode).isEqualTo(gpuMode)
    }

    @Test
    fun shimExtractionCheckWithRealContext() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val extractor = ShimExtractor(context)

        // Shims are not bundled in the test APK, so isExtracted should be false
        val targetDir = "${context.filesDir}/test_shims_check"
        assertThat(extractor.isExtracted(targetDir)).isFalse()
    }

    @Test
    fun applicationContextIsAvailable() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertThat(context).isNotNull()
        assertThat(context.packageName).isEqualTo("nu.shel.wldroid.testapp")
        assertThat(context.filesDir).isNotNull()
        assertThat(context.cacheDir).isNotNull()
        assertThat(context.applicationInfo.nativeLibraryDir).isNotNull()
    }

    @Test
    fun compositorStateTransitionsAreOrdered() {
        // Verify the expected state machine ordering
        val states = CompositorState.entries
        assertThat(states.indexOf(CompositorState.IDLE))
            .isLessThan(states.indexOf(CompositorState.STARTING))
        assertThat(states.indexOf(CompositorState.STARTING))
            .isLessThan(states.indexOf(CompositorState.RUNNING))
        assertThat(states.indexOf(CompositorState.RUNNING))
            .isLessThan(states.indexOf(CompositorState.STOPPING))
    }
}
