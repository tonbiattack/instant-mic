package com.tonbiattack.instantmic.ui

import com.tonbiattack.instantmic.speech.Transcript
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel

/**
 * Orders the recognition events that touch the PC document.
 *
 * The controller emits a settled phrase and the final result of the same
 * utterance from different callbacks, and two collectors can interleave. Since
 * the phrase path appends to the user's document, appending out of order (or
 * comparing a final before its phrases were typed) would corrupt the sentence.
 *
 * Sending both kinds through one channel and consuming it with a single worker
 * makes the order exactly the order the recognizer reported.
 */
sealed interface SpeechEvent {
    /** A settled phrase to type now (incremental mode). */
    data class Phrase(val text: String, val sessionId: Long) : SpeechEvent

    /** The final, authoritative transcript of one utterance. */
    data class Final(val transcript: Transcript) : SpeechEvent
}

/**
 * A small unbounded queue with one consumer.
 *
 * `Channel.UNLIMITED` is used on purpose: recognizing speech must never block
 * on the network, and the number of events per utterance is tiny.
 */
class SpeechEventQueue {
    private val channel = Channel<SpeechEvent>(Channel.UNLIMITED)

    /** Used by collectors; never suspends. */
    fun offer(event: SpeechEvent) {
        channel.trySend(event)
    }

    /** Closed when the view model is cleared so the worker can exit. */
    fun close() {
        channel.close()
    }

    /** The single consumer; returns when the queue is closed. */
    fun events(): ReceiveChannel<SpeechEvent> = channel
}
