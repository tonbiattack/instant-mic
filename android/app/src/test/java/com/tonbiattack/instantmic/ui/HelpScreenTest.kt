package com.tonbiattack.instantmic.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Compose UI test for the bundled help screen, run on the JVM through
 * Robolectric.
 *
 * The parser has its own unit tests; this one checks the part a unit test cannot
 * see — that the asset is actually packaged, read, and rendered with its
 * headings and tables intact.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class HelpScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `the bundled help document is shown`() {
        composeRule.setContent { HelpScreen() }

        // Reading the asset happens off the main thread, so wait for the
        // document to replace the spinner.
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithTag(TestTags.HELP_SCREEN).fetchSemanticsNodes().isNotEmpty()
        }

        composeRule.onNodeWithTag(TestTags.HELP_SCREEN).assertIsDisplayed()
        composeRule.onNodeWithText("instant-mic の使い方").assertIsDisplayed()
    }

    @Test
    fun `the command table is rendered`() {
        composeRule.setContent { HelpScreen() }
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithTag(TestTags.HELP_SCREEN).fetchSemanticsNodes().isNotEmpty()
        }

        // A table cell from the voice-command list: proves the pipe table was
        // parsed rather than dumped as literal "|" text. It sits below the fold,
        // so scroll to it before asserting it is on screen.
        composeRule.onNodeWithText("コマンド、送信 / エンター").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Enter").performScrollTo().assertIsDisplayed()
    }
}
