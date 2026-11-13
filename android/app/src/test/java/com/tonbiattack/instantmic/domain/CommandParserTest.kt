package com.tonbiattack.instantmic.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CommandParserTest {

    private fun interpret(text: String, custom: List<CustomCommand> = emptyList()) =
        CommandParser.interpret(text, custom = custom)

    @Test
    fun `commands map to key sequences`() {
        val cases = mapOf(
            "コマンド、送信" to "enter",
            "コマンド、改行" to "shift+enter",
            "コマンド、取り消し" to "ctrl+z",
            "コマンド、全選択" to "ctrl+a",
            "コマンド、削除" to "backspace",
            "コマンド、バックスペース" to "backspace",
            "コマンド、バック スペース" to "backspace",
            "コマンド、前の単語削除" to "ctrl+backspace",
            "コマンド、行削除" to "home>shift+end>delete",
            "コマンド、デリート" to "delete",
            "コマンド、上" to "up",
            "コマンド、下" to "down",
            "コマンド、左" to "left",
            "コマンド、右" to "right",
            "コマンド、コピー" to "ctrl+c",
            "コマンド、貼り付け" to "ctrl+v",
            "コマンド、切り取り" to "ctrl+x",
            "コマンド、やり直し" to "ctrl+y",
            "コマンド、タブ切り替え" to "ctrl+tab",
            "コマンド送信" to "enter",
            "コマンド 送信" to "enter",
            "コマンド　送信" to "enter",
            "コマンド、送信。" to "enter",
        )
        for ((input, expected) in cases) {
            val result = interpret(input)
            assertTrue("$input -> $result", result is CommandParser.Result.Key)
            assertEquals(input, expected, (result as CommandParser.Result.Key).command.wire)
        }
    }

    @Test
    fun `misrecognised kaiGyou resolves only as a whole phrase`() {
        for (input in listOf("コマンド、開業", "コマンド開業", "コマンド 開業", "コマンド、開業。")) {
            val result = interpret(input)
            assertTrue("$input -> $result", result is CommandParser.Result.Key)
            assertEquals(input, "shift+enter", (result as CommandParser.Result.Key).command.wire)
        }
        for (input in listOf("開業しました", "コマンド開業について説明して", "コマンド開業について教えて")) {
            val result = interpret(input)
            assertTrue("$input -> $result", result is CommandParser.Result.Text)
        }
    }

    @Test
    fun `katakana key names are accepted`() {
        val cases = mapOf(
            "コマンド、エンター" to "enter",
            "コマンドエンター" to "enter",
            "コマンド エンター" to "enter",
            "コマンド、えんたー" to "enter",
            "コマンド、シフトエンター" to "shift+enter",
            "コマンド、バックスペース" to "backspace",
            "コマンド、でりーと" to "delete",
            "コマンド、アップ" to "up",
            "コマンド、ダウン" to "down",
            "コマンド、レフト" to "left",
            "コマンド、ライト" to "right",
            "コマンド、ペースト" to "ctrl+v",
        )
        for ((input, expected) in cases) {
            val result = interpret(input)
            assertTrue("$input -> $result", result is CommandParser.Result.Key)
            assertEquals(input, expected, (result as CommandParser.Result.Key).command.wire)
        }
    }

    @Test
    fun `katakana key names inside sentences stay text`() {
        for (input in listOf("エンターキーを押す", "デリートしました", "コマンドエンターについて説明して")) {
            val result = interpret(input)
            assertTrue("$input -> $result", result is CommandParser.Result.Text)
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
            "削除しました",
            "コマンド削除について",
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
        for (input in listOf("コマンド、存在しない", "コマンド、", "コマンド")) {
            assertTrue(input, interpret(input) is CommandParser.Result.UnknownCommand)
        }
    }

    @Test
    fun `spoken undo is a whole-phrase command`() {
        for (input in listOf("コマンド、言い直し", "コマンド言い直し", "コマンド 言い直し", "コマンド、言直し")) {
            assertTrue("$input", interpret(input) is CommandParser.Result.Erase)
        }
        for (input in listOf("言い直しします", "コマンド言い直しについて説明して")) {
            assertTrue("$input", interpret(input) is CommandParser.Result.Text)
        }
    }

    @Test
    fun `repeat count is honoured only for repeatable commands`() {
        val cases = mapOf(
            "コマンド、3回削除" to (3 to "backspace"),
            "コマンド、削除3回" to (3 to "backspace"),
            "コマンド、三回削除" to (3 to "backspace"),
            "コマンド、10回上" to (5 to "up"),
        )
        for ((input, expected) in cases) {
            val result = interpret(input)
            assertTrue("$input -> $result", result is CommandParser.Result.Key)
            val key = result as CommandParser.Result.Key
            assertEquals(input, expected.first, key.command.count)
            assertEquals(input, expected.second, key.command.wire)
        }
        for (input in listOf("コマンド、3回送信", "コマンド、2回行削除", "コマンド、2回コピー")) {
            assertTrue(input, interpret(input) !is CommandParser.Result.Key)
        }
    }

    @Test
    fun `custom prefix is honoured`() {
        val result = CommandParser.interpret("マイク、送信", prefix = "マイク")
        assertTrue(result is CommandParser.Result.Key)
        assertEquals("enter", (result as CommandParser.Result.Key).command.wire)
    }

    @Test
    fun `custom commands resolve on whole phrase only`() {
        val custom = listOf(CustomCommand("AIに送信", "ctrl+enter"))
        val hit = interpret("コマンド、AIに送信", custom)
        assertTrue("$hit", hit is CommandParser.Result.Key)
        assertEquals("ctrl+enter", (hit as CommandParser.Result.Key).command.wire)

        // With an explicit separator this is an unknown command, not text: the
        // wake word was spoken and nothing is typed.
        val miss = interpret("コマンド、AIに送信してみた", custom)
        assertTrue("$miss", miss is CommandParser.Result.UnknownCommand)
    }

    @Test
    fun `custom commands cannot shadow built-ins`() {
        // The built-in table wins, so a conflicting custom entry cannot change
        // what 削除 does.
        val custom = listOf(CustomCommand("削除", "ctrl+alt+delete"))
        val result = interpret("コマンド、削除", custom)
        assertEquals("backspace", (result as CommandParser.Result.Key).command.wire)
        assertTrue(
            CustomCommands.validate("削除", "ctrl+c", emptyList()).isNotEmpty(),
        )
    }

    @Test
    fun `undo phrase cannot be registered as a custom command`() {
        assertTrue(
            CustomCommands.validate("言い直し", "ctrl+c", emptyList()).isNotEmpty(),
        )
    }

    @Test
    fun `normalize folds punctuation and width`() {
        assertEquals(CommandParser.normalize("送信"), CommandParser.normalize(" 送 信 "))
        assertEquals(CommandParser.normalize("送信"), CommandParser.normalize("送信。"))
    }

    @Test
    fun `key sequences normalise and reject unknown keys`() {
        assertEquals("ctrl+shift+k", KeySequence.normalize(" SHIFT + CTRL + K "))
        assertEquals("home>shift+end>delete", KeySequence.normalize("home > shift+end > delete"))
        assertEquals("escape", KeySequence.normalize("esc"))
        assertNull(KeySequence.normalize("hyper+a"))
        assertNull(KeySequence.normalize("ctrl+alt+delete"))
        assertNull(KeySequence.normalize("ctrl"))
        assertEquals("Home → Shift+End → Delete", KeySequence.label("home>shift+end>delete"))

        // Custom commands only accept allowlisted keys.
        assertNotNull(KeySequence.normalize("ctrl+enter"))
        assertNull(KeySequence.normalize("ctrl+mediaplay"))
    }
}
