package com.tonbiattack.instantmic.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CustomCommandsTest {

    private val existing = listOf(
        CustomCommand("AIに送信", "ctrl+enter"),
        CustomCommand("保存", "ctrl+s"),
    )

    @Test
    fun `valid command has no errors`() {
        assertTrue(CustomCommands.validate("整形", "shift+alt+f", existing).isEmpty())
    }

    @Test
    fun `built-in phrases are rejected`() {
        for (phrase in listOf("削除", "改行", "バックスペース", "開業")) {
            val errors = CustomCommands.validate(phrase, "ctrl+c", existing)
            assertTrue("$phrase -> $errors", errors.isNotEmpty())
        }
    }

    @Test
    fun `duplicate phrases are rejected unless editing that entry`() {
        assertTrue(CustomCommands.validate("AIに送信", "ctrl+enter", existing).isNotEmpty())
        assertTrue(
            CustomCommands.validate("AIに送信", "ctrl+enter", existing, editing = "AIに送信").isEmpty(),
        )
    }

    @Test
    fun `unknown keys are rejected`() {
        assertTrue(CustomCommands.validate("何か", "hyper+q", existing).isNotEmpty())
        assertTrue(CustomCommands.validate("何か", "ctrl+alt+delete", existing).isNotEmpty())
        assertTrue(CustomCommands.validate("何か", "ctrl", existing).isNotEmpty())
    }

    @Test
    fun `phrase must not repeat the wake prefix`() {
        assertTrue(CustomCommands.validate("コマンド送信", "ctrl+c", existing).isNotEmpty())
    }

    @Test
    fun `short phrases are rejected`() {
        assertTrue(CustomCommands.validate("あ", "ctrl+c", existing).isNotEmpty())
    }

    @Test
    fun `upsert adds replaces and removes`() {
        val added = CustomCommands.upsert(existing, "整形", "SHIFT + ALT + F")
        assertEquals(3, added.size)
        assertEquals("shift+alt+f", added.last().sequence)

        val renamed = CustomCommands.upsert(added, "AIへ送信", "ctrl+enter", editing = "AIに送信")
        assertEquals(3, renamed.size)
        assertFalse(renamed.any { it.phrase == "AIに送信" })
        assertTrue(renamed.any { it.phrase == "AIへ送信" })

        val removed = CustomCommands.remove(renamed, "保存")
        assertEquals(2, removed.size)
        assertFalse(removed.any { it.phrase == "保存" })
    }

    @Test
    fun `storage round trip ignores corrupt entries`() {
        val encoded = CustomCommands.encode(existing)
        assertEquals(existing, CustomCommands.decode(encoded))

        assertTrue(CustomCommands.decode(null).isEmpty())
        assertTrue(CustomCommands.decode("not json").isEmpty())
        val partial = CustomCommands.decode(
            """[{"phrase":"有効","sequence":"ctrl+k"},{"phrase":"","sequence":"ctrl+j"},{"phrase":"不正","sequence":"hyper+x"}]""",
        )
        assertEquals(1, partial.size)
        assertEquals("有効", partial.first().phrase)
    }
}
