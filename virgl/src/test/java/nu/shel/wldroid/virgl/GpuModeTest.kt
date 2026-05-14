package nu.shel.wldroid.virgl

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class GpuModeTest {

    @Test
    fun `all enum entries have non-blank displayName`() {
        GpuMode.entries.forEach { mode ->
            assertThat(mode.displayName).isNotEmpty()
        }
    }

    @Test
    fun `all enum entries have non-blank description`() {
        GpuMode.entries.forEach { mode ->
            assertThat(mode.description).isNotEmpty()
        }
    }

    @Test
    fun `fromString returns correct mode for valid names`() {
        GpuMode.entries.forEach { mode ->
            assertThat(GpuMode.fromString(mode.name)).isEqualTo(mode)
        }
    }

    @Test
    fun `fromString returns AUTO for unknown value`() {
        assertThat(GpuMode.fromString("UNKNOWN")).isEqualTo(GpuMode.AUTO)
        assertThat(GpuMode.fromString("")).isEqualTo(GpuMode.AUTO)
        assertThat(GpuMode.fromString("virgl_gles")).isEqualTo(GpuMode.AUTO) // case-sensitive
    }

    @Test
    fun `requiresVirglServer is true only for server-backed modes`() {
        assertThat(GpuMode.VIRGL_GLES.requiresVirglServer).isTrue()
        assertThat(GpuMode.VIRGL_ZINK.requiresVirglServer).isTrue()
        assertThat(GpuMode.VENUS.requiresVirglServer).isTrue()

        assertThat(GpuMode.SOFTWARE.requiresVirglServer).isFalse()
        assertThat(GpuMode.TURNIP_DIRECT.requiresVirglServer).isFalse()
        assertThat(GpuMode.AUTO.requiresVirglServer).isFalse()
    }

    @Test
    fun `enum has expected number of entries`() {
        assertThat(GpuMode.entries).hasSize(6)
    }
}
