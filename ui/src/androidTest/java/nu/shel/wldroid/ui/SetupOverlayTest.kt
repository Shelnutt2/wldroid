package nu.shel.wldroid.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.google.common.truth.Truth.assertThat
import java.util.concurrent.TimeUnit
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

/**
 * Compose UI tests for [SetupOverlay].
 */
@RunWith(JUnit4::class)
class SetupOverlayTest {

    @get:Rule
    val globalTimeout: Timeout = Timeout(60, TimeUnit.SECONDS)

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun downloadingStateShowsMessage() {
        composeTestRule.setContent {
            SetupOverlay(
                state = SetupState.Downloading(progress = 0.5f, message = "Downloading rootfs..."),
            )
        }
        composeTestRule.onNodeWithText("Downloading rootfs...").assertIsDisplayed()
    }

    @Test
    fun extractingStateShowsMessage() {
        composeTestRule.setContent {
            SetupOverlay(
                state = SetupState.Extracting(progress = 0.3f, message = "Extracting files..."),
            )
        }
        composeTestRule.onNodeWithText("Extracting files...").assertIsDisplayed()
    }

    @Test
    fun installingStateShowsMessage() {
        composeTestRule.setContent {
            SetupOverlay(
                state = SetupState.Installing(message = "Configuring packages..."),
            )
        }
        composeTestRule.onNodeWithText("Configuring packages...").assertIsDisplayed()
    }

    @Test
    fun errorStateShowsRetryButton() {
        var retried = false
        composeTestRule.setContent {
            SetupOverlay(
                state = SetupState.Error("Something failed"),
                onRetry = { retried = true },
            )
        }
        composeTestRule.onNodeWithText("Something failed").assertIsDisplayed()
        composeTestRule.onNodeWithText("Retry").assertIsDisplayed()
        composeTestRule.onNodeWithText("Retry").performClick()
        assertThat(retried).isTrue()
    }

    @Test
    fun errorStateWithCanRetryFalseHidesRetry() {
        composeTestRule.setContent {
            SetupOverlay(
                state = SetupState.Error("Fatal error", canRetry = false),
                onRetry = { },
            )
        }
        composeTestRule.onNodeWithText("Fatal error").assertIsDisplayed()
        // Retry button should not be displayed for non-retryable errors
        composeTestRule.onNodeWithText("Retry").assertDoesNotExist()
    }

    @Test
    fun setupStateIsActiveFlags() {
        assertThat(SetupState.Idle.isActive).isFalse()
        assertThat(SetupState.Running.isActive).isFalse()
        assertThat(SetupState.Downloading(0.5f).isActive).isTrue()
        assertThat(SetupState.Extracting(0.5f).isActive).isTrue()
        assertThat(SetupState.Installing().isActive).isTrue()
        assertThat(SetupState.Launching().isActive).isTrue()
        assertThat(SetupState.Error("err").isActive).isFalse()
    }

    @Test
    fun setupStateIsTerminalFlags() {
        assertThat(SetupState.Idle.isTerminal).isTrue()
        assertThat(SetupState.Running.isTerminal).isTrue()
        assertThat(SetupState.Error("err").isTerminal).isTrue()
        assertThat(SetupState.Downloading(0.5f).isTerminal).isFalse()
        assertThat(SetupState.Extracting(0.5f).isTerminal).isFalse()
        assertThat(SetupState.Installing().isTerminal).isFalse()
        assertThat(SetupState.Launching().isTerminal).isFalse()
    }
}
