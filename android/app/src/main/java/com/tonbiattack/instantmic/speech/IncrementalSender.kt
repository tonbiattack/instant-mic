package com.tonbiattack.instantmic.speech

import com.tonbiattack.instantmic.domain.CommandParser
import com.tonbiattack.instantmic.domain.TextShaper
import java.text.Normalizer

/** What to do with the rest of an utterance once the user stops speaking. */
sealed interface IncrementalFinish {
    /** Send this remainder; what was sent before plus this equals the final text. */
    data class Remaining(val text: String) : IncrementalFinish

    /** Everything was already sent as settled phrases. */
    data object Complete : IncrementalFinish

    /** Nothing was typed yet, so the caller should handle the final normally. */
    data object NothingSent : IncrementalFinish

    /**
     * The recognizer revised text that was already typed. It cannot be replaced
     * safely (the user may have moved the caret, or another app may have focus),
     * so nothing more is sent and the phone asks the user to check.
     */
    data class Conflict(val finalText: String) : IncrementalFinish
}

/**
 * Decides which prefix of a live transcript is safe to type before the user
 * stops speaking.
 *
 * Only *settled* phrases are sent: a completed sentence (。！？) always counts,
 * and a long run-on sentence may be cut at a comma. Interims are never trusted
 * wholesale, because a recognizer routinely rewrites the tail of a sentence as
 * more audio arrives.
 *
 * Three rules keep this safe:
 *
 * 1. **No rewrite.** `emitted` records the shaped text already handed to the
 *    receiver. Every later result, interim or final, is shaped as a whole and
 *    must start with that exact text; otherwise sending stops for the rest of
 *    the utterance ([IncrementalFinish.Conflict]) and only the phone preview
 *    continues.
 * 2. **No commands.** If the utterance starts like the wake prefix (「コマンド」
 *    or any prefix of it), incremental sending is disabled for the session so a
 *    command is only ever interpreted from the final result.
 * 3. **No dangling fragments.** A chunk only ends on a boundary and never
 *    inside an unclosed bracket pair, so a half-recognized or half-shaped phrase
 *    never appears on the PC.
 *
 * Shaping (「まる」→「。」 and friends) is applied to the accumulated raw text,
 * not per chunk, so a word split across two interims still shapes correctly.
 */
class IncrementalSender(
    private val commandPrefix: String = CommandParser.DEFAULT_PREFIX,
    /** Minimum length for a *comma* cut; a finished sentence has no minimum. */
    private val minClauseRunes: Int = 30,
    /** Minimum length for a pause-based tail cut, which has no boundary at all. */
    private val minTailRunes: Int = 8,
) {

    /** Shaped text already sent; the prefix every later result must keep. */
    var emitted: String = ""
        private set

    /** True once incremental sending is no longer allowed for this utterance. */
    var stopped: Boolean = false
        private set

    fun reset() {
        emitted = ""
        stopped = false
    }

    /**
     * Returns the shaped text to type now, or null when the interim does not
     * yet contain a settled chunk.
     *
     * [allowTail] relaxes the boundary rule for recognizers that only add
     * punctuation to the final result: the end of the current interim is then
     * treated as settled. The caller only passes true after the interim has
     * stopped changing, so a half-recognized word is not cut in half.
     */
    fun nextPhrase(partial: String, allowTail: Boolean = false): String? {
        if (stopped) return null
        val text = partial.trimStart()
        if (text.isEmpty()) return null

        if (emitted.isEmpty() && looksLikeCommand(text)) {
            // A command must only be interpreted from the final result.
            stopped = true
            return null
        }
        val shaped = TextShaper.shape(text)
        if (!shaped.startsWith(emitted)) {
            // The recognizer changed text that is already on the PC.
            stopped = true
            return null
        }

        val boundary = findBoundary(shaped, emitted.length)
        if (boundary == null) {
            if (!allowTail) return null
            // A tail cut is arbitrary, so demand enough text to be worth typing.
            if (shaped.substring(emitted.length).trim().runes() < minTailRunes) return null
        }
        val cut = boundary ?: shaped.length
        // A spoken punctuation word at the very end of an interim may still
        // grow into a word (「まる」 → 「まるで」), so it is not settled yet.
        if (cut == shaped.length && endsWithSpokenPunctuation(text)) return null
        val chunk = shaped.substring(0, cut)
        val delta = chunk.substring(emitted.length)
        if (delta.isBlank()) return null
        if (hasUnclosedBracket(chunk)) return null

        emitted = chunk
        return delta
    }

    /** Decides what to send when the recognizer reports its final result. */
    fun finish(final: String): IncrementalFinish {
        if (emitted.isEmpty()) return IncrementalFinish.NothingSent
        val shaped = TextShaper.shape(final.trim())
        if (!shaped.startsWith(emitted)) {
            stopped = true
            return IncrementalFinish.Conflict(shaped.trim())
        }
        val remainder = shaped.substring(emitted.length).trim()
        return if (remainder.isEmpty()) IncrementalFinish.Complete else IncrementalFinish.Remaining(remainder)
    }

    /**
     * True when the text might be (the beginning of) a voice command. Any
     * prefix of the wake prefix counts, because recognition arrives in pieces:
     * 「コマ」 may still become 「コマンド」.
     */
    private fun looksLikeCommand(text: String): Boolean {
        val normalized = normalize(text)
        val prefix = normalize(commandPrefix)
        if (prefix.isEmpty() || normalized.isEmpty()) return false
        val limit = minOf(normalized.length, prefix.length)
        for (i in 1..limit) {
            if (normalized.startsWith(prefix.substring(0, i))) return true
        }
        return false
    }

    /**
     * Index just past the last usable boundary in the *shaped* text, or null.
     *
     * Only text after [from] is considered, so a boundary that was already sent
     * cannot be mistaken for new text.
     *
     * Shaping already decides whether a spoken 「まる」 really is punctuation
     * (it is not when the word continues, e.g. 「まるで」), so this only has to
     * find sentence ends and, in long speech, a clause comma.
     */
    private fun findBoundary(shaped: String, from: Int = 0): Int? {
        var sentence = -1
        var clause = -1
        for (i in from until shaped.length) {
            when (shaped[i]) {
                '。', '！', '？', '!', '?' -> sentence = i + 1
                '、', ',', '，' -> clause = i + 1
            }
        }
        if (sentence >= 0) return sentence
        if (clause >= 0 && shaped.substring(0, clause).runes() >= minClauseRunes) return clause
        return null
    }

    /** True when bracket words opened inside [text] are not closed yet. */
    private fun hasUnclosedBracket(text: String): Boolean {
        var depth = 0
        var i = 0
        while (i < text.length) {
            when {
                text.startsWith("かぎかっことじ", i) -> {
                    if (depth > 0) depth--
                    i += 7
                }
                text.startsWith("かっことじ", i) -> {
                    if (depth > 0) depth--
                    i += 5
                }
                text.startsWith("かぎかっこ", i) -> {
                    depth++
                    i += 5
                }
                text.startsWith("かっこ", i) -> {
                    depth++
                    i += 3
                }
                text[i] == '「' || text[i] == '（' || text[i] == '『' -> {
                    depth++
                    i++
                }
                text[i] == '」' || text[i] == '）' || text[i] == '』' -> {
                    if (depth > 0) depth--
                    i++
                }
                else -> i++
            }
        }
        return depth != 0
    }

    private enum class Boundary { Sentence, Clause }

    /**
     * True when [raw] ends in a spoken punctuation word. TextShaper converts
     * such a word even at the very end of the text, but in a live interim it may
     * still turn into an ordinary word (「まるで」), so it waits for more audio.
     */
    private fun endsWithSpokenPunctuation(raw: String): Boolean {
        val trimmed = raw.trimEnd()
        return SPOKEN_PUNCTUATION.any { trimmed.endsWith(it) }
    }

    private fun normalize(value: String): String =
        Normalizer.normalize(value, Normalizer.Form.NFKC)
            .filterNot { it.isWhitespace() || it in "、。，．！？!?「」『』・,." }

    private fun String.runes(): Int = codePointCount(0, length)

    private companion object {
        /** The spoken punctuation words TextShaper understands. */
        val SPOKEN_PUNCTUATION = listOf(
            "びっくりまーく", "かぎかっことじ", "かぎかっこ", "かっことじ", "かっこ",
            "すらっしゅ", "せみころん", "ころん", "はてな", "まる", "てん",
        )
    }
}
