package com.tonbiattack.instantmic.ui

import com.tonbiattack.instantmic.speech.Transcript
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class SpeechEventQueueTest {

    @Test
    fun `events keep the order they were offered`() = runTest {
        val queue = SpeechEventQueue()
        queue.offer(SpeechEvent.Phrase("いちです。", 1))
        queue.offer(SpeechEvent.Phrase("にです。", 1))
        queue.offer(SpeechEvent.Final(Transcript("いちです。にです。", 1)))
        queue.close()

        val seen = mutableListOf<SpeechEvent>()
        for (event in queue.events()) seen.add(event)

        assertEquals(
            listOf(
                SpeechEvent.Phrase("いちです。", 1),
                SpeechEvent.Phrase("にです。", 1),
                SpeechEvent.Final(Transcript("いちです。にです。", 1)),
            ),
            seen,
        )
    }

    @Test
    fun `offering never suspends even with no consumer yet`() {
        val queue = SpeechEventQueue()
        // A slow consumer must not block the recognizer callback.
        repeat(1_000) { queue.offer(SpeechEvent.Phrase("x", it.toLong())) }
        queue.close()
    }
}
