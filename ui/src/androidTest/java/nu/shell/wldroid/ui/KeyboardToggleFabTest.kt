package nu.shell.wldroid.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

/**
 * Compose UI tests for [KeyboardToggleFab].
 */
@RunWith(JUnit4::class)
class KeyboardToggleFabTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun fabIsDisplayedWhenVisible() {
        composeTestRule.setContent {
            KeyboardToggleFab(
                isKeyboardVisible = false,
                onToggle = {},
                visible = true,
            )
        }
        composeTestRule.onNodeWithContentDescription("Show keyboard").assertIsDisplayed()
    }

    @Test
    fun fabShowsHideKeyboardWhenVisible() {
        composeTestRule.setContent {
            KeyboardToggleFab(
                isKeyboardVisible = true,
                onToggle = {},
                visible = true,
            )
        }
        composeTestRule.onNodeWithContentDescription("Hide keyboard").assertIsDisplayed()
    }

    @Test
    fun clickingFabCallsToggle() {
        var toggled = false
        composeTestRule.setContent {
            KeyboardToggleFab(
                isKeyboardVisible = false,
                onToggle = { toggled = true },
                visible = true,
            )
        }
        composeTestRule.onNodeWithContentDescription("Show keyboard").performClick()
        assertThat(toggled).isTrue()
    }
}
