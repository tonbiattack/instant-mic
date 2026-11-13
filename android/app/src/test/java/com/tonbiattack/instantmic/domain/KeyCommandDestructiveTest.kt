package com.tonbiattack.instantmic.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Whether a command needs a confirmation step.
 *
 * Getting this wrong is what lets an accidental 「コマンド、送信」 post to a chat,
 * so the rule is spelled out and tested rather than left implicit.
 */
class KeyCommandDestructiveTest {

    @Test
    fun `sending and deleting are destructive`() {
        assertTrue(KeyCommand.ENTER.isDestructive)
        assertTrue(KeyCommand.BACKSPACE.isDestructive)
        assertTrue(KeyCommand.DELETE.isDestructive)
        assertTrue(KeyCommand.DELETE_LINE.isDestructive)
        assertTrue(KeyCommand.CTRL_BACKSPACE.isDestructive)
    }

    @Test
    fun `clipboard overwrite is destructive`() {
        assertTrue(KeyCommand.PASTE.isDestructive)
        assertTrue(KeyCommand.CUT.isDestructive)
    }

    @Test
    fun `navigation and selection are not destructive`() {
        assertFalse(KeyCommand.UP.isDestructive)
        assertFalse(KeyCommand.DOWN.isDestructive)
        assertFalse(KeyCommand.LEFT.isDestructive)
        assertFalse(KeyCommand.RIGHT.isDestructive)
        assertFalse(KeyCommand.CTRL_A.isDestructive)
        assertFalse(KeyCommand.CTRL_Z.isDestructive)
        assertFalse(KeyCommand.COPY.isDestructive)
        assertFalse(KeyCommand.NEXT_TAB.isDestructive)
    }

    @Test
    fun `a custom sequence that deletes is destructive`() {
        val custom = KeyCommand("ctrl+shift+r", "再読み込み")
        assertFalse(custom.isDestructive)
        val deletes = KeyCommand("end>shift+home>delete", "行頭まで削除")
        assertTrue(deletes.isDestructive)
    }

    @Test
    fun `a custom sequence that starts with enter is destructive`() {
        // Two chords, not a repeat count: the first one sends.
        val send = KeyCommand("enter>enter", "二回送信")
        assertTrue(send.isDestructive)
        assertEquals(1, send.count)
    }
}
