package nu.shell.wldroid.launcher

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class DesktopAppPresetTest {
    @Test fun allPresets_haveNonEmptyIds() {
        DesktopAppPreset.ALL.forEach { preset ->
            assertThat(preset.id).isNotEmpty()
        }
    }
    @Test fun allPresets_haveNonEmptyCommands() {
        DesktopAppPreset.ALL.forEach { preset ->
            assertThat(preset.command).isNotEmpty()
        }
    }
    @Test fun allPresets_haveUniqueIds() {
        val ids = DesktopAppPreset.ALL.map { it.id }
        assertThat(ids).containsNoDuplicates()
    }
    @Test fun allPresets_haveDisplayNames() {
        DesktopAppPreset.ALL.forEach { preset ->
            assertThat(preset.displayName).isNotEmpty()
        }
    }
    @Test fun expectedPresetCount() {
        assertThat(DesktopAppPreset.ALL).hasSize(7)
    }
}
