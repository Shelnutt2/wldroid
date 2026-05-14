package nu.shel.wldroid.compositor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CompositorConfigTest {

    @Test
    fun `default config has expected values`() {
        val config = CompositorConfig.default()
        assertEquals("", config.cacheDir)
        assertEquals("", config.xkbBasePath)
        assertTrue(config.xwaylandEnabled)
        assertEquals("", config.xwaylandBinaryPath)
        assertEquals("AUTO", config.gpuMode)
        assertFalse(config.testClientEnabled)
        assertEquals("", config.ahbRegistrySocketPath)
    }

    @Test
    fun `config can be created with custom values`() {
        val config = CompositorConfig(
            cacheDir = "/data/cache",
            xkbBasePath = "/data/xkb",
            xwaylandEnabled = false,
            gpuMode = "VIRGL",
            testClientEnabled = true,
        )
        assertEquals("/data/cache", config.cacheDir)
        assertEquals("/data/xkb", config.xkbBasePath)
        assertFalse(config.xwaylandEnabled)
        assertEquals("VIRGL", config.gpuMode)
        assertTrue(config.testClientEnabled)
    }

    @Test
    fun `config copy preserves unmodified fields`() {
        val original = CompositorConfig(cacheDir = "/original")
        val modified = original.copy(xwaylandEnabled = false)
        assertEquals("/original", modified.cacheDir)
        assertFalse(modified.xwaylandEnabled)
    }

    @Test
    fun `configs with same values are equal`() {
        val a = CompositorConfig(cacheDir = "/test")
        val b = CompositorConfig(cacheDir = "/test")
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun `xwaylandBinaryPath can be set`() {
        val config = CompositorConfig(xwaylandBinaryPath = "/cache/xwayland-wrapper.sh")
        assertEquals("/cache/xwayland-wrapper.sh", config.xwaylandBinaryPath)
        assertTrue(config.xwaylandEnabled)
    }

    @Test
    fun `xwayland disabled with custom binary path`() {
        val config = CompositorConfig(
            xwaylandEnabled = false,
            xwaylandBinaryPath = "/cache/xwayland-wrapper.sh",
        )
        assertFalse(config.xwaylandEnabled)
        assertEquals("/cache/xwayland-wrapper.sh", config.xwaylandBinaryPath)
    }

    @Test
    fun `validate passes for disabled xwayland`() {
        val config = CompositorConfig(xwaylandEnabled = false)
        // Should not throw
        config.validate()
    }

    @Test
    fun `validate passes for empty binary path`() {
        val config = CompositorConfig(xwaylandEnabled = true, xwaylandBinaryPath = "")
        // Should not throw (empty path means wlroots uses default search)
        config.validate()
    }

    @Test(expected = IllegalStateException::class)
    fun `validate fails for non-existent binary path`() {
        val config = CompositorConfig(
            xwaylandEnabled = true,
            xwaylandBinaryPath = "/nonexistent/path/xwayland-wrapper.sh",
        )
        config.validate()
    }

    @Test
    fun `validate passes for existing binary path`() {
        val tempFile = java.io.File.createTempFile("xwayland-test", ".sh")
        try {
            val config = CompositorConfig(
                xwaylandEnabled = true,
                xwaylandBinaryPath = tempFile.absolutePath,
            )
            // Should not throw
            config.validate()
        } finally {
            tempFile.delete()
        }
    }
}
