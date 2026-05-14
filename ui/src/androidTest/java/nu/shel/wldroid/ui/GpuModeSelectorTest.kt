package nu.shel.wldroid.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.google.common.truth.Truth.assertThat
import nu.shel.wldroid.virgl.GpuMode
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

/**
 * Compose UI tests for [GpuModeSelector].
 */
@RunWith(JUnit4::class)
class GpuModeSelectorTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun displaysAllAvailableModes() {
        val modes = listOf(GpuMode.SOFTWARE, GpuMode.VIRGL_GLES, GpuMode.VIRGL_ZINK)
        composeTestRule.setContent {
            GpuModeSelector(
                currentMode = GpuMode.SOFTWARE,
                availableModes = modes,
                onModeSelected = {},
            )
        }
        // Each mode's displayName should be shown
        for (mode in modes) {
            composeTestRule.onNodeWithText(mode.displayName).assertIsDisplayed()
        }
    }

    @Test
    fun displaysTitle() {
        composeTestRule.setContent {
            GpuModeSelector(
                currentMode = GpuMode.SOFTWARE,
                availableModes = listOf(GpuMode.SOFTWARE),
                onModeSelected = {},
            )
        }
        composeTestRule.onNodeWithText("GPU Mode").assertIsDisplayed()
    }

    @Test
    fun selectingModeCallsCallback() {
        var selected: GpuMode? = null
        composeTestRule.setContent {
            GpuModeSelector(
                currentMode = GpuMode.SOFTWARE,
                availableModes = listOf(GpuMode.SOFTWARE, GpuMode.VIRGL_GLES),
                onModeSelected = { selected = it },
            )
        }
        // Click on VIRGL_GLES
        composeTestRule.onNodeWithText(GpuMode.VIRGL_GLES.displayName).performClick()
        assertThat(selected).isEqualTo(GpuMode.VIRGL_GLES)
    }

    @Test
    fun displaysDescriptionForModes() {
        composeTestRule.setContent {
            GpuModeSelector(
                currentMode = GpuMode.SOFTWARE,
                availableModes = listOf(GpuMode.SOFTWARE),
                onModeSelected = {},
            )
        }
        // The description text should be visible
        composeTestRule.onNodeWithText(GpuMode.SOFTWARE.description).assertIsDisplayed()
    }

    @Test
    fun singleModeListRendersWithoutError() {
        composeTestRule.setContent {
            GpuModeSelector(
                currentMode = GpuMode.TURNIP_DIRECT,
                availableModes = listOf(GpuMode.TURNIP_DIRECT),
                onModeSelected = {},
            )
        }
        composeTestRule.onNodeWithText(GpuMode.TURNIP_DIRECT.displayName).assertIsDisplayed()
    }
}
