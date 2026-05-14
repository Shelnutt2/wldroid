package nu.shel.wldroid.launcher

import com.google.common.truth.Truth.assertThat
import nu.shel.wldroid.virgl.GpuMode
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
        assertThat(DesktopAppPreset.ALL).hasSize(8)
    }

    @Test fun vkcube_requiresVulkanGpuMode() {
        assertThat(DesktopAppPreset.VKCUBE.supportedGpuModes).isNotNull()
        assertThat(DesktopAppPreset.VKCUBE.isCompatibleWith(GpuMode.VENUS)).isTrue()
        assertThat(DesktopAppPreset.VKCUBE.isCompatibleWith(GpuMode.TURNIP_DIRECT)).isTrue()
        assertThat(DesktopAppPreset.VKCUBE.isCompatibleWith(GpuMode.VIRGL_ZINK)).isFalse()
        assertThat(DesktopAppPreset.VKCUBE.isCompatibleWith(GpuMode.VIRGL_GLES)).isFalse()
        assertThat(DesktopAppPreset.VKCUBE.isCompatibleWith(GpuMode.SOFTWARE)).isFalse()
    }

    @Test fun unrestrictedPresets_compatibleWithAllModes() {
        val unrestricted = DesktopAppPreset.ALL.filter { it.supportedGpuModes == null }
        assertThat(unrestricted).isNotEmpty()
        unrestricted.forEach { preset ->
            GpuMode.entries.filter { it != GpuMode.AUTO }.forEach { mode ->
                assertThat(preset.isCompatibleWith(mode)).isTrue()
            }
        }
    }

    @Test fun westonSimpleEgl_isUnrestricted() {
        assertThat(DesktopAppPreset.WESTON_SIMPLE_EGL.supportedGpuModes).isNull()
        assertThat(DesktopAppPreset.WESTON_SIMPLE_EGL.requiredPackages).contains("weston")
    }
}
