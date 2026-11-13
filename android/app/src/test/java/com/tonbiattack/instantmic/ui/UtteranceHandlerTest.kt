package com.tonbiattack.instantmic.ui

import com.tonbiattack.instantmic.speech.IncrementalFinish
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UtteranceHandlerTest {

    @Test
    fun `nothing typed means the final result is handled normally`() {
        val handler = UtteranceHandler()
        assertEquals(IncrementalFinish.NothingSent, handler.finish("きょうは いい てんき"))
    }

    @Test
    fun `a failed send stops the rest of the utterance`() {
        val handler = UtteranceHandler()
        handler.notePhrase("いちです。")
        handler.noteAcknowledged("いちです。")
        handler.fail()
        val finish = handler.finish("いちです。にです。")
        assertTrue(finish is IncrementalFinish.Conflict)
        assertEquals("いちです。にです。", (finish as IncrementalFinish.Conflict).finalText)
    }

    @Test
    fun `an acknowledged phrase leaves only the remainder`() {
        val handler = UtteranceHandler()
        handler.notePhrase("いちです。")
        handler.noteAcknowledged("いちです。")
        assertEquals(
            IncrementalFinish.Remaining("にです。"),
            handler.finish("いちです。にです。"),
        )
    }

    @Test
    fun `everything acknowledged is complete`() {
        val handler = UtteranceHandler()
        handler.notePhrase("いちです。")
        handler.noteAcknowledged("いちです。")
        assertEquals(IncrementalFinish.Complete, handler.finish("いちです。"))
    }

    @Test
    fun `a command tail is treated as a conflict after typing`() {
        val handler = UtteranceHandler()
        handler.notePhrase("これは テストです。")
        handler.noteAcknowledged("これは テストです。")
        // The full utterance turns out to start a command: it must not run.
        assertTrue(handler.finish("コマンド、送信") is IncrementalFinish.Conflict)
    }

    @Test
    fun `a wake prefix seen while typing disables incremental completion`() {
        val handler = UtteranceHandler()
        handler.notePhrase("コマンド")
        handler.noteAcknowledged("コマンド")
        assertTrue(handler.finish("コマンド、送信") is IncrementalFinish.Conflict)
    }

    @Test
    fun `a revised final is a conflict rather than a guess`() {
        val handler = UtteranceHandler()
        handler.notePhrase("これは テストです。")
        handler.noteAcknowledged("これは テストです。")
        val finish = handler.finish("こちらは テストです。")
        assertTrue(finish is IncrementalFinish.Conflict)
        assertTrue(handler.revised)
    }

    @Test
    fun `spoken punctuation is shaped before comparing`() {
        val handler = UtteranceHandler()
        handler.notePhrase("これはテストですまる")
        handler.noteAcknowledged("これはテストです。")
        // The final still contains the spoken word; shaping makes it match what
        // was already typed, so it is not reported as a revision.
        assertEquals(IncrementalFinish.Complete, handler.finish("これはテストですまる"))
    }

    @Test
    fun `a failed send with nothing acknowledged still falls back to the final`() {
        val handler = UtteranceHandler()
        handler.fail()
        // The failed phrase may have reached the PC anyway, so the final is not
        // typed: a duplicate is worse than a missing word.
        assertTrue(handler.finish("きょうは いい てんき") is IncrementalFinish.Conflict)
    }
}
