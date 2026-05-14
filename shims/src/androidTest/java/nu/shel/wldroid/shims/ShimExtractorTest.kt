package nu.shel.wldroid.shims

import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import java.io.File

/**
 * Instrumented tests for [ShimExtractor] — asset extraction and
 * LD_PRELOAD string generation on a real Android device/emulator.
 *
 * Note: Actual shim .so files may not be bundled in the test APK
 * (they require the native build to have run). Tests verify the
 * code paths and logic without requiring the actual assets.
 */
@RunWith(JUnit4::class)
class ShimExtractorTest {

    private lateinit var testDir: File

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        testDir = File(context.filesDir, "shim_test_${System.nanoTime()}")
        testDir.mkdirs()
    }

    @After
    fun tearDown() {
        testDir.deleteRecursively()
    }

    @Test
    fun shimAssetsListIsComplete() {
        // Verify the internal asset list contains all expected shim libraries
        val assets = ShimExtractor.SHIM_ASSETS
        assertThat(assets).hasSize(5)
        val filenames = assets.map { it.second }
        assertThat(filenames).contains("libdrm-shim.so")
        assertThat(filenames).contains("libdrm-wrapper.so")
        assertThat(filenames).contains("libgbm.so.1")
        assertThat(filenames).contains("libegl_override.so")
        assertThat(filenames).contains("libnetstub.so")
    }

    @Test
    fun isExtractedReturnsFalseForEmptyDir() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val extractor = ShimExtractor(context)
        assertThat(extractor.isExtracted(testDir.absolutePath)).isFalse()
    }

    @Test
    fun isExtractedReturnsFalseForNonexistentDir() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val extractor = ShimExtractor(context)
        assertThat(extractor.isExtracted("/nonexistent/path")).isFalse()
    }

    @Test
    fun isExtractedReturnsTrueWhenAllFilesPresent() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val extractor = ShimExtractor(context)

        // Create fake shim files in subdirectory layout to simulate extraction
        for ((assetPath, filename) in ShimExtractor.SHIM_ASSETS) {
            val subDir = File(assetPath).parent ?: ""
            val outDir = File(testDir, subDir).also { it.mkdirs() }
            File(outDir, filename).writeText("fake")
        }

        assertThat(extractor.isExtracted(testDir.absolutePath)).isTrue()
    }

    @Test
    fun isExtractedReturnsFalseWhenPartialFiles() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val extractor = ShimExtractor(context)

        // Only create some of the shim files
        File(testDir, "libdrm-shim.so").writeText("fake")
        File(testDir, "libnetstub.so").writeText("fake")

        assertThat(extractor.isExtracted(testDir.absolutePath)).isFalse()
    }

    @Test
    fun getLdPreloadStringForSoftwareMode() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val extractor = ShimExtractor(context)

        val shimSet = ShimExtractor.ShimSet(
            drmShim = "/lib/libdrm-shim.so",
            drmWrapper = "/lib/libdrm-wrapper.so",
            gbmShim = "/lib/libgbm.so.1",
            eglOverride = "/lib/libegl_override.so",
            netstub = "/lib/libnetstub.so",
        )

        val ldPreload = extractor.getLdPreloadString(shimSet, "SOFTWARE")
        // SOFTWARE mode disables drm, gbm, and egl — only netstub remains
        assertThat(ldPreload).doesNotContain("libdrm-shim.so")
        assertThat(ldPreload).contains("libnetstub.so")
        assertThat(ldPreload).doesNotContain("libgbm.so")
        assertThat(ldPreload).doesNotContain("libegl_override.so")
    }

    @Test
    fun getLdPreloadStringForVirglMode() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val extractor = ShimExtractor(context)

        val shimSet = ShimExtractor.ShimSet(
            drmShim = "/lib/libdrm-shim.so",
            drmWrapper = "/lib/libdrm-wrapper.so",
            gbmShim = "/lib/libgbm.so.1",
            eglOverride = "/lib/libegl_override.so",
            netstub = "/lib/libnetstub.so",
        )

        val ldPreload = extractor.getLdPreloadString(shimSet, "VIRGL_GLES")
        // VirGL modes enable all shims
        assertThat(ldPreload).contains("libdrm-shim.so")
        assertThat(ldPreload).contains("libgbm.so.1")
        assertThat(ldPreload).contains("libegl_override.so")
        assertThat(ldPreload).contains("libnetstub.so")
    }

    @Test
    fun ldPreloadStringUsesColonSeparator() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val extractor = ShimExtractor(context)

        val shimSet = ShimExtractor.ShimSet(
            drmShim = "/a",
            drmWrapper = "/b",
            gbmShim = "/c",
            eglOverride = "/d",
            netstub = "/e",
        )

        val ldPreload = extractor.getLdPreloadString(shimSet, "VIRGL_GLES")
        // Should be colon-separated
        assertThat(ldPreload).contains(":")
        val parts = ldPreload.split(":")
        assertThat(parts.size).isGreaterThan(1)
    }
}
