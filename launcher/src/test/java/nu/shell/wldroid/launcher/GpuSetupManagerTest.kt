package nu.shell.wldroid.launcher

import com.google.common.truth.Truth.assertThat
import nu.shell.wldroid.virgl.GpuMode
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder

class GpuSetupManagerTest {

    @get:Rule val tempFolder = TemporaryFolder()

    @Test fun `virgl packages contain mesa-dri`() {
        assertThat(GpuSetupManager.VIRGL_PACKAGES).contains("libgl1-mesa-dri")
    }

    @Test fun `turnip packages contain vulkan drivers`() {
        assertThat(GpuSetupManager.TURNIP_PACKAGES).contains("mesa-vulkan-drivers")
    }

    @Test fun `venus packages contain vulkan drivers`() {
        assertThat(GpuSetupManager.VENUS_PACKAGES).contains("mesa-vulkan-drivers")
    }

    @Test fun `virgl packages contain mesa-utils`() {
        assertThat(GpuSetupManager.VIRGL_PACKAGES).contains("mesa-utils")
    }

    @Test fun `turnip packages do not contain mesa-utils`() {
        assertThat(GpuSetupManager.TURNIP_PACKAGES).doesNotContain("mesa-utils")
    }

    @Test fun `marker prefix is non-empty`() {
        assertThat(GpuSetupManager.MARKER_PREFIX).isNotEmpty()
    }
}
