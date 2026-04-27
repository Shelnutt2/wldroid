package nu.shell.wldroid.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.google.common.truth.Truth.assertThat
import nu.shell.wldroid.proot.RootfsEnvironment
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

/**
 * Compose UI tests for [EnvironmentPicker].
 */
@RunWith(JUnit4::class)
class EnvironmentPickerTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun emptyListShowsCreateButton() {
        composeTestRule.setContent {
            EnvironmentPicker(
                environments = emptyList(),
                selectedId = null,
                onSelect = {},
                onCreate = {},
                onDelete = {},
            )
        }
        composeTestRule.onNodeWithText("Create new").assertIsDisplayed()
    }

    @Test
    fun environmentsAreDisplayed() {
        val envs = listOf(
            RootfsEnvironment(
                id = "env-1",
                name = "Debian Dev",
                rootfsPath = "/data/rootfs/env-1",
                distro = "debian-trixie",
                createdAt = System.currentTimeMillis(),
            ),
            RootfsEnvironment(
                id = "env-2",
                name = "Ubuntu Server",
                rootfsPath = "/data/rootfs/env-2",
                distro = "ubuntu",
                createdAt = System.currentTimeMillis(),
            ),
        )
        composeTestRule.setContent {
            EnvironmentPicker(
                environments = envs,
                selectedId = "env-1",
                onSelect = {},
                onCreate = {},
                onDelete = {},
            )
        }
        composeTestRule.onNodeWithText("Debian Dev").assertIsDisplayed()
        composeTestRule.onNodeWithText("Ubuntu Server").assertIsDisplayed()
    }

    @Test
    fun createNewButtonCallsCallback() {
        var createCalled = false
        composeTestRule.setContent {
            EnvironmentPicker(
                environments = emptyList(),
                selectedId = null,
                onSelect = {},
                onCreate = { createCalled = true },
                onDelete = {},
            )
        }
        composeTestRule.onNodeWithText("Create new").performClick()
        assertThat(createCalled).isTrue()
    }

    @Test
    fun titleIsDisplayed() {
        composeTestRule.setContent {
            EnvironmentPicker(
                environments = emptyList(),
                selectedId = null,
                onSelect = {},
                onCreate = {},
                onDelete = {},
            )
        }
        composeTestRule.onNodeWithText("Environments").assertIsDisplayed()
    }
}
