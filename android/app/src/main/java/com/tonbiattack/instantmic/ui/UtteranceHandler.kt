package com.tonbiattack.instantmic.ui

import com.tonbiattack.instantmic.domain.CommandParser
import com.tonbiattack.instantmic.domain.TextShaper
import com.tonbiattack.instantmic.speech.IncrementalFinish

/**
 * Tracks one utterance that is being typed phrase by phrase.
 *
 * The recognizer decides *when* a phrase looks settled (see
 * `IncrementalSender`); this class owns the part that only the view model knows:
 * which text actually reached the PC, and whether the final result still agrees
 * with it.
 *
 * The rules exist because appending text to someone else's document is
 * irreversible:
 *
 * - A phrase must be acknowledged before it counts as typed. A failed send
 *   stops the utterance, because typing the next phrase would leave a gap.
 * - A command is never split. If the utterance starts like the wake prefix, the
 *   whole utterance falls back to "final result only".
 * - If the final result does not start with what was typed, nothing more is
 *   typed. Reporting it is better than guessing at a correction.
 */
class UtteranceHandler(
    private val commandPrefix: String = CommandParser.DEFAULT_PREFIX,
    /** Dry-run mode for tests: phrases count as typed without a receiver. */
    private val autoAcknowledge: Boolean = false,
) {

    /** Shaped text confirmed by the receiver. */
    var emitted: String = ""
        private set

    /** True once the final result disagreed with what was already typed. */
    var revised: Boolean = false
        private set

    private var failed = false
    private var sawCommand = false

    /** True after a failed delivery; no further phrase may be sent. */
    val hasFailed: Boolean get() = failed

    /** True when anything at all was typed for this utterance. */
    val typedAnything: Boolean get() = emitted.isNotEmpty()

    /** Records a phrase the recognizer settled on, before it is sent. */
    fun notePhrase(phrase: String) {
        if (looksLikeCommand(emitted + phrase)) sawCommand = true
        if (autoAcknowledge) noteAcknowledged(TextShaper.shape(phrase))
    }

    /** Records that the receiver accepted [text]. */
    fun noteAcknowledged(text: String) {
        emitted += text
    }

    /** Stops sending for the rest of the utterance after a failed delivery. */
    fun fail() {
        failed = true
    }

    /**
     * Decides what the final result still needs.
     *
     * [IncrementalFinish.NothingSent] means the caller should handle the final
     * result exactly as it did before incremental input existed, so the two
     * paths can never type an utterance twice.
     */
    fun finish(final: String): IncrementalFinish {
        val shaped = TextShaper.shape(final.trim())
        if (failed) {
            // A phrase failed to deliver: it may still have reached the PC (a
            // lost acknowledgment), so typing the final would risk duplicating
            // it. Refusing is the only safe choice.
            revised = true
            return IncrementalFinish.Conflict(shaped)
        }
        if (emitted.isEmpty()) {
            return if (shaped.isEmpty()) IncrementalFinish.Complete else IncrementalFinish.NothingSent
        }
        if (sawCommand) {
            revised = true
            return IncrementalFinish.Conflict(shaped)
        }
        // Only remove the trailing whitespace: leading spaces are already gone
        // above, and trimming the end keeps the prefix comparison exact.
        val prefix = TextShaper.shape(final.trimStart()).trimEnd()
        if (!prefix.startsWith(emitted)) {
            revised = true
            return IncrementalFinish.Conflict(shaped)
        }
        val remainder = prefix.substring(emitted.length).trim()
        return if (remainder.isEmpty()) IncrementalFinish.Complete else IncrementalFinish.Remaining(remainder)
    }

    private fun looksLikeCommand(text: String): Boolean {
        val prefix = CommandParser.normalize(commandPrefix)
        if (prefix.isEmpty()) return false
        return CommandParser.normalize(text).startsWith(prefix)
    }
}
