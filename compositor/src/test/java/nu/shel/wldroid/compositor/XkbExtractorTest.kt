package nu.shel.wldroid.compositor

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class XkbExtractorTest {

    /**
     * Allocates an XkbExtractor without invoking its constructor,
     * bypassing the Kotlin non-null check on the Context parameter.
     * Safe for tests that only call [XkbExtractor.isExtracted], which
     * does not touch the context field.
     */
    private val extractor: XkbExtractor = run {
        val unsafeClass = Class.forName("sun.misc.Unsafe")
        val unsafeField = unsafeClass.getDeclaredField("theUnsafe")
        unsafeField.isAccessible = true
        val unsafe = unsafeField.get(null)
        val allocateInstance = unsafeClass.getMethod("allocateInstance", Class::class.java)
        allocateInstance.invoke(unsafe, XkbExtractor::class.java) as XkbExtractor
    }

    @Test
    fun `isExtracted returns false when directory does not exist`() {
        val tempDir = Files.createTempDirectory("xkb-test").toFile()
        tempDir.deleteRecursively()
        assertFalse(extractor.isExtracted(tempDir.absolutePath))
    }

    @Test
    fun `isExtracted returns false when directory exists but no marker`() {
        val tempDir = Files.createTempDirectory("xkb-test").toFile()
        try {
            assertFalse(extractor.isExtracted(tempDir.absolutePath))
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `isExtracted returns true when marker file exists`() {
        val tempDir = Files.createTempDirectory("xkb-test").toFile()
        try {
            File(tempDir, ".extracted").createNewFile()
            assertTrue(extractor.isExtracted(tempDir.absolutePath))
        } finally {
            tempDir.deleteRecursively()
        }
    }
}
