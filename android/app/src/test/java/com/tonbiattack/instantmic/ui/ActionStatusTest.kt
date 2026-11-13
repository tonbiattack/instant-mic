package com.tonbiattack.instantmic.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tonbiattack.instantmic.domain.KeyCommand
import com.tonbiattack.instantmic.speech.SpeechState
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Compose UI tests that run on the JVM through Robolectric, so CI covers the
 * screens without a phone.
 *
 * These focus on the states where a mistake is costly: the destructive-command
 * confirmation and the revision warning. A logic test cannot see whether the
 * buttons are actually wired, and a device test would not run in CI.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class ActionStatusTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `a destructive command asks before running`() {
        var confirmed = false
        var cancelled = false
        composeRule.setContent {
            ActionStatus(
                state = UiState(
                    lastAction = LastAction.ConfirmCommand(KeyCommand.ENTER),
                    speech = SpeechState.Listening,
                ),
                onDismiss = {},
                onConfirmCommand = { confirmed = true },
                onCancelCommand = { cancelled = true },
            )
        }

        composeRule.onNodeWithText("「Enter」を実行しますか？").assertIsDisplayed()
        composeRule.onNodeWithText("実行する").performClick()
        assertTrue("confirm was not wired", confirmed)
        assertTrue("cancel must not fire with confirm", !cancelled)
    }

    @Test
    fun `cancel does not run the command`() {
        var confirmed = false
        var cancelled = false
        composeRule.setContent {
            ActionStatus(
                state = UiState(lastAction = LastAction.ConfirmCommand(KeyCommand.DELETE)),
                onDismiss = {},
                onConfirmCommand = { confirmed = true },
                onCancelCommand = { cancelled = true },
            )
        }

        composeRule.onNodeWithText("やめる").performClick()
        assertTrue("cancel was not wired", cancelled)
        assertTrue("confirm must not fire with cancel", !confirmed)
    }

    @Test
    fun `a revision is reported as an error with the text`() {
        composeRule.setContent {
            ActionStatus(
                state = UiState(lastAction = LastAction.Revision("こちらはテストです。")),
                onDismiss = {},
                onConfirmCommand = {},
                onCancelCommand = {},
            )
        }

        composeRule.onNodeWithTag(TestTags.ACTION_STATUS).assertIsDisplayed()
        composeRule.onNodeWithText("こちらはテストです。").assertIsDisplayed()
    }

    @Test
    fun `a successful send shows the elapsed time`() {
        composeRule.setContent {
            ActionStatus(
                state = UiState(lastAction = LastAction.Typed("こんにちは", 12)),
                onDismiss = {},
                onConfirmCommand = {},
                onCancelCommand = {},
            )
        }

        composeRule.onNodeWithText("送信しました（12ms）: こんにちは").assertIsDisplayed()
    }
}
