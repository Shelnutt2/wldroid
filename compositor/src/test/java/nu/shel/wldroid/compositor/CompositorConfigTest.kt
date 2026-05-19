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
    fun `xwaylandEnabled defaults to true`() {
        val config = CompositorConfig()
        assertTrue(config.xwaylandEnabled)
    }

    @Test
    fun `xwaylandEnabled false skips validation of binary path`() {
        // When XWayland is disabled, a non-existent binary path should not
        // cause validate() to fail — the path is irrelevant.
        val config = CompositorConfig(
            xwaylandEnabled = false,
            xwaylandBinaryPath = "/nonexistent/path/xwayland-wrapper.sh",
        )
        // Should not throw
        config.validate()
    }

    @Test
    fun `xwaylandTmpDir can be set`() {
        val config = CompositorConfig(xwaylandTmpDir = "/data/local/tmp/xwayland")
        assertEquals("/data/local/tmp/xwayland", config.xwaylandTmpDir)
    }

    @Test
    fun `xwaylandTmpDir defaults to empty`() {
        val config = CompositorConfig()
        assertEquals("", config.xwaylandTmpDir)
    }

    @Test
    fun `config with xwayland disabled preserves other fields`() {
        val config = CompositorConfig(
            cacheDir = "/cache",
            xkbBasePath = "/xkb",
            xwaylandEnabled = false,
            xwaylandBinaryPath = "/path/to/xwayland",
            xwaylandTmpDir = "/tmp/xwayland",
            gpuMode = "SOFTWARE",
        )
        assertEquals("/cache", config.cacheDir)
        assertEquals("/xkb", config.xkbBasePath)
        assertFalse(config.xwaylandEnabled)
        assertEquals("/path/to/xwayland", config.xwaylandBinaryPath)
        assertEquals("/tmp/xwayland", config.xwaylandTmpDir)
        assertEquals("SOFTWARE", config.gpuMode)
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
