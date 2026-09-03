package moe.gensoukyo.agentpulse

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppLaunchTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun launchShowsAgentPulseHostScreen() {
        composeRule.onNodeWithText("AgentPulse").assertIsDisplayed()
    }

    @Test
    fun bottomNavigationOpensSettings() {
        composeRule.onNodeWithText("Settings").performClick()

        composeRule.onNodeWithText("Appearance").assertIsDisplayed()
        composeRule.onNodeWithText("Theme mode").assertIsDisplayed()
    }
}
