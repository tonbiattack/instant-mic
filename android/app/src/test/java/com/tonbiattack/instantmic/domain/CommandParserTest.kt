package com.tonbiattack.instantmic.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CommandParserTest {

    private fun interpret(text: String) = CommandParser.interpret(text)

    @Test
    fun `commands map to key actions`() {
        val cases = mapOf(
            "コマンド、送信" to KeyAction.ENTER,
            "コマンド、改行" to KeyAction.SHIFT_ENTER,
            "コマンド、取り消し" to KeyAction.CTRL_Z,
            "コマンド、全選択" to KeyAction.CTRL_A,
            "コマンド送信" to KeyAction.ENTER,
            "コマンド 送信" to KeyAction.ENTER,
            "コマンド　送信" to KeyAction.ENTER,
            "コマンド、送信。" to KeyAction.ENTER,
        )
        for ((input, expected) in cases) {
            val result = interpret(input)
            assertTrue("$input -> $result", result is CommandParser.Result.Key)
            assertEquals(input, expected, (result as CommandParser.Result.Key).action)
        }
    }

    @Test
    fun `normal sentences are text`() {
        val sentences = listOf(
            "送信",
            "これを送信してください",
            "メールを送信",
            "コマンド一覧を教えて",
            "コマンドラインの使い方",
            "コマンドを実行",
            "、送信",
            "改行します",
        )
        for (input in sentences) {
            val result = interpret(input)
            assertTrue("$input -> $result", result is CommandParser.Result.Text)
            assertEquals(input, (result as CommandParser.Result.Text).text)
        }
    }

    @Test
    fun `blank input is empty`() {
        assertEquals(CommandParser.Result.Empty, interpret(""))
        assertEquals(CommandParser.Result.Empty, interpret("   "))
    }

    @Test
    fun `unknown command does nothing`() {
        for (input in listOf("コマンド、削除", "コマンド、", "コマンド")) {
            assertTrue(input, interpret(input) is CommandParser.Result.UnknownCommand)
        }
    }

    @Test
    fun `custom prefix is honoured`() {
        val result = CommandParser.interpret("マイク、送信", prefix = "マイク")
        assertTrue(result is CommandParser.Result.Key)
        assertEquals(KeyAction.ENTER, (result as CommandParser.Result.Key).action)
    }

    @Test
    fun `normalize folds punctuation and width`() {
        assertEquals(CommandParser.normalize("送信"), CommandParser.normalize(" 送 信 "))
        assertEquals(CommandParser.normalize("送信"), CommandParser.normalize("送信。"))
    }
}
