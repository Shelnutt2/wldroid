package nu.shell.wldroid.compositor

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
}
