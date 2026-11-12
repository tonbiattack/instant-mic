package com.tonbiattack.instantmic.speech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RecognitionLoopPolicyTest {

    @Test
    fun `silence and no match restart quietly`() {
        assertEquals(
            RecognitionLoopPolicy.Decision.Restart,
            RecognitionLoopPolicy.classify(RecognitionLoopPolicy.ERROR_NO_MATCH, 0),
        )
        assertEquals(
            RecognitionLoopPolicy.Decision.Restart,
            RecognitionLoopPolicy.classify(RecognitionLoopPolicy.ERROR_SPEECH_TIMEOUT, 3),
        )
    }

    @Test
    fun `a busy recognizer is recreated before retrying`() {
        assertEquals(
            RecognitionLoopPolicy.Decision.RestartWithNewRecognizer,
            RecognitionLoopPolicy.classify(RecognitionLoopPolicy.ERROR_RECOGNIZER_BUSY, 1),
        )
    }

    @Test
    fun `network errors stop only after repeated failures`() {
        assertEquals(
            RecognitionLoopPolicy.Decision.Restart,
            RecognitionLoopPolicy.classify(RecognitionLoopPolicy.ERROR_NETWORK, 1),
        )
        val stop = RecognitionLoopPolicy.classify(
            RecognitionLoopPolicy.ERROR_NETWORK,
            RecognitionLoopPolicy.MAX_CONSECUTIVE_FAILURES,
        )
        assertTrue(stop is RecognitionLoopPolicy.Decision.Stop)
        assertEquals(true, (stop as RecognitionLoopPolicy.Decision.Stop).retryable)
    }

    @Test
    fun `missing permission is fatal and not retryable`() {
        val decision = RecognitionLoopPolicy.classify(
            RecognitionLoopPolicy.ERROR_INSUFFICIENT_PERMISSIONS,
            1,
        )
        assertTrue(decision is RecognitionLoopPolicy.Decision.Stop)
        assertEquals(false, (decision as RecognitionLoopPolicy.Decision.Stop).retryable)
    }

    @Test
    fun `backoff grows and is capped`() {
        val first = RecognitionLoopPolicy.backoffMillis(1)
        val second = RecognitionLoopPolicy.backoffMillis(2)
        assertTrue(first < second)
        assertEquals(RecognitionLoopPolicy.backoffMillis(6), RecognitionLoopPolicy.backoffMillis(60))
        assertTrue(RecognitionLoopPolicy.backoffMillis(60) <= 3_000)
    }

    @Test
    fun `unknown error stops with the code`() {
        val decision = RecognitionLoopPolicy.classify(42, 1)
        assertTrue(decision is RecognitionLoopPolicy.Decision.Stop)
        assertTrue((decision as RecognitionLoopPolicy.Decision.Stop).message.contains("42"))
    }
}
