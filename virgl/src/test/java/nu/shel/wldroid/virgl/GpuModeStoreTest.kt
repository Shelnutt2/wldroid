package nu.shel.wldroid.virgl

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Unit tests for [GpuModeStore] persistence logic.
 *
 * Note: GpuModeStore requires an Android Context for DataStore,
 * so full read/write tests need Robolectric or instrumented tests.
 * Here we verify the GpuMode serialisation contract that the store relies on.
 */
class GpuModeStoreTest {

    @Test
    fun `GpuMode valueOf round-trips correctly`() {
        GpuMode.entries.forEach { mode ->
            assertThat(GpuMode.valueOf(mode.name)).isEqualTo(mode)
        }
    }

    @Test
    fun `invalid valueOf throws exception`() {
        try {
            GpuMode.valueOf("INVALID_MODE")
            assertThat(false).isTrue() // should not reach here
        } catch (e: IllegalArgumentException) {
            // Expected — the store catches this and returns null.
            assertThat(e).isInstanceOf(IllegalArgumentException::class.java)
        }
    }

    @Test
    fun `auto string is not a valid GpuMode name`() {
        // The store treats "auto" as null (auto-detect). Verify it's not a real enum name.
        try {
            GpuMode.valueOf("auto")
            assertThat(false).isTrue()
        } catch (_: IllegalArgumentException) {
            // Expected.
        }
    }

    @Test
    fun `GpuMode name serialisation produces uppercase strings`() {
        // DataStore stores mode.name — verify the format.
        assertThat(GpuMode.VIRGL_GLES.name).isEqualTo("VIRGL_GLES")
        assertThat(GpuMode.SOFTWARE.name).isEqualTo("SOFTWARE")
        assertThat(GpuMode.AUTO.name).isEqualTo("AUTO")
    }
}
