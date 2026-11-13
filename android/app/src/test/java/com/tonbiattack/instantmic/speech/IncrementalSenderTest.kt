package com.tonbiattack.instantmic.speech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IncrementalSenderTest {

    @Test
    fun `no phrase until a sentence boundary arrives`() {
        val sender = IncrementalSender()
        assertNull(sender.nextPhrase("きょうは"))
        assertNull(sender.nextPhrase("きょうは いい てんき"))
        assertEquals("きょうは いい てんきです。", sender.nextPhrase("きょうは いい てんきです。つぎは"))
    }

    @Test
    fun `a completed sentence is sent even when short`() {
        val sender = IncrementalSender()
        assertEquals("はい。", sender.nextPhrase("はい。"))
    }

    @Test
    fun `sentence boundaries accumulate and only new text is returned`() {
        val sender = IncrementalSender()
        assertEquals("いちです。", sender.nextPhrase("いちです。"))
        assertEquals("にです。", sender.nextPhrase("いちです。にです。"))
        assertEquals("いちです。にです。", sender.emitted)
    }

    @Test
    fun `a revised interim stops incremental sending`() {
        val sender = IncrementalSender()
        assertEquals("これは テストです。", sender.nextPhrase("これは テストです。"))
        // The recognizer changed the first sentence: it must not append.
        assertNull(sender.nextPhrase("こちらは テストです。つぎ"))
        assertTrue(sender.stopped)
    }

    @Test
    fun `commands are never sent incrementally`() {
        val sender = IncrementalSender()
        assertNull(sender.nextPhrase("コマンド、送信。"))
        assertTrue(sender.stopped)

        val partial = IncrementalSender()
        // A prefix of the wake word is enough: 「コマ」 can still become
        // 「コマンド」.
        assertNull(partial.nextPhrase("コマ。"))
        assertTrue(partial.stopped)
    }

    @Test
    fun `a word starting like the wake prefix is not typed incrementally`() {
        val sender = IncrementalSender()
        // 「コマンド一覧」 starts like the wake word, so incremental stays off
        // for safety; the final result still types it as text.
        assertNull(sender.nextPhrase("コマンド一覧。"))
        assertTrue(sender.stopped)
    }

    @Test
    fun `finish sends only the remainder`() {
        val sender = IncrementalSender()
        sender.nextPhrase("いちです。")
        assertEquals(
            IncrementalFinish.Remaining("にです。"),
            sender.finish("いちです。にです。"),
        )
    }

    @Test
    fun `finish is complete when everything was sent`() {
        val sender = IncrementalSender()
        sender.nextPhrase("いちです。")
        assertEquals(IncrementalFinish.Complete, sender.finish("いちです。"))
    }

    @Test
    fun `finish reports a conflict when the final revises typed text`() {
        val sender = IncrementalSender()
        sender.nextPhrase("これは テストです。")
        assertTrue(sender.finish("こちらは テストです。") is IncrementalFinish.Conflict)
    }

    @Test
    fun `finish reports nothing sent when no phrase was emitted`() {
        val sender = IncrementalSender()
        assertNull(sender.nextPhrase("きょうは"))
        assertEquals(IncrementalFinish.NothingSent, sender.finish("きょうは いい てんき"))
    }

    @Test
    fun `spoken punctuation shapes the emitted text`() {
        val sender = IncrementalSender()
        // A trailing 「まる」 may still become 「まるで」, so it is not a boundary.
        assertNull(sender.nextPhrase("こんにちはまる"))
        // Once the recognizer punctuates it, the sentence is settled and shaped.
        // A sentence that ends on a spoken word waits for the next syllable.
        val settled = sender.nextPhrase("こんにちはまる。つぎにいく")
        assertEquals("こんにちは。", settled)
    }

    @Test
    fun `a trailing spoken punctuation word is not sent early`() {
        val sender = IncrementalSender()
        assertNull(sender.nextPhrase("こんにちは まる"))
        assertNull(sender.nextPhrase("こんにちは まるで ちがう"))
    }

    @Test
    fun `an unclosed bracket is not sent early`() {
        val sender = IncrementalSender()
        assertNull(sender.nextPhrase("これはかっこ とてもたいせつ です。"))
        // Balance is restored, so the whole sentence can go.
        assertEquals(
            "これは（とてもたいせつ）です。",
            sender.nextPhrase("これはかっことてもたいせつかっことじです。"),
        )
    }

    @Test
    fun `long speech without a sentence end can be cut at a comma`() {
        val sender = IncrementalSender(minClauseRunes = 10)
        assertEquals(
            "これはとてもながいぶんしょうで、",
            sender.nextPhrase("これはとてもながいぶんしょうで、まだおわりません"),
        )
    }

    @Test
    fun `a short clause is not sent`() {
        val sender = IncrementalSender(minClauseRunes = 30)
        assertNull(sender.nextPhrase("みじかい、まだつづく"))
    }

    @Test
    fun `a tail can be flushed when the recognizer adds no punctuation`() {
        val sender = IncrementalSender(minClauseRunes = 5)
        // No 。 ever arrives while speaking, so without allowTail nothing is sent.
        assertNull(sender.nextPhrase("今日は会議があります"))
        assertEquals("今日は会議があります", sender.nextPhrase("今日は会議があります", allowTail = true))
        assertEquals("今日は会議があります", sender.emitted)
    }

    @Test
    fun `a tail is not flushed while it is still very short`() {
        val sender = IncrementalSender(minClauseRunes = 30)
        assertNull(sender.nextPhrase("今日", allowTail = true))
    }

    @Test
    fun `a tail is not flushed inside an unclosed bracket`() {
        val sender = IncrementalSender(minClauseRunes = 5)
        assertNull(sender.nextPhrase("これはかっこです", allowTail = true))
    }

    @Test
    fun `a tail cut still refuses a later revision`() {
        val sender = IncrementalSender(minClauseRunes = 5)
        assertEquals("今日は会議があります", sender.nextPhrase("今日は会議があります", allowTail = true))
        assertNull(sender.nextPhrase("今日は会議がありました", allowTail = true))
        assertTrue(sender.stopped)
    }

    @Test
    fun `a tail flush continues after the sentence end`() {
        val sender = IncrementalSender(minTailRunes = 3)
        assertEquals("いちです。", sender.nextPhrase("いちです。にです"))
        assertEquals("にです", sender.nextPhrase("いちです。にです", allowTail = true))
    }

    @Test
    fun `reset makes the sender reusable for the next utterance`() {
        val sender = IncrementalSender()
        sender.nextPhrase("いちです。")
        sender.reset()
        assertFalse(sender.stopped)
        assertEquals("", sender.emitted)
        assertEquals("にです。", sender.nextPhrase("にです。"))
    }
}
