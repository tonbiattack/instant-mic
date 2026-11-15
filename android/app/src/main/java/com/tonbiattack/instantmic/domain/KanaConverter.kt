package com.tonbiattack.instantmic.domain

/**
 * Converts dictated Japanese to hiragana, for use when the text goes to a
 * coding assistant.
 *
 * Rationale: the recognizer's *kanji choice* is the main source of errors
 * (「改行」 vs 「開業」). Removing kanji removes that whole class of mistake, and a
 * large language model reads hiragana as easily as kanji.
 *
 * **Katakana is kept.** An earlier version folded it too, which made a
 * misrecognition unreadable: 「GitHub シークレット」 misheard as
 * 「ギターボシークレット」 became 「ぎたーぶしーくれっと」 and the speaker could no
 * longer tell what was said. Katakana words are usually loanwords whose reading
 * is the spelling, so keeping them costs nothing and preserves recognisability.
 *
 * Everything else is preserved on purpose:
 *
 * - ASCII, digits and code spans are never touched, so command names, paths,
 *   flags and URLs survive verbatim.
 * - Punctuation produced by the shaper (。、！？) and bracket symbols stay.
 * - If the analyser cannot load, [convert] returns the input unchanged rather
 *   than mangling it; the setting then simply has no effect.
 */
class KanaConverter(private val analyser: TokenAnalyser? = KuromojiAnalyser()) {

    /** True when a real analyser is available. */
    val available: Boolean get() = analyser != null

    /**
     * Rewrites [text] in hiragana. Runs on the caller's thread; the analyser is
     * thread-safe and the input is a single phrase.
     */
    fun convert(text: String): String {
        if (text.isEmpty()) return text
        val out = StringBuilder(text.length + 16)
        // Split into runs of convertible Japanese and everything else, so code
        // and URLs are copied byte for byte.
        var i = 0
        val pending = StringBuilder()
        while (i < text.length) {
            val cp = text.codePointAt(i)
            val len = Character.charCount(cp)
            if (isConvertible(cp)) {
                pending.appendCodePoint(cp)
                i += len
                continue
            }
            if (pending.isNotEmpty()) {
                out.append(convertRun(pending.toString()))
                pending.clear()
            }
            out.appendCodePoint(cp)
            i += len
        }
        if (pending.isNotEmpty()) out.append(convertRun(pending.toString()))
        return out.toString()
    }

    private fun convertRun(run: String): String {
        val tokens = analyser?.tokens(run)
        // A failed load or an empty analysis must not lose text.
        if (tokens == null || tokens.isEmpty()) return run
        val shaped = tokens.joinToString("") { it.surface }
        // The analyser must reproduce the input exactly; if it does not, its
        // readings cannot be trusted for this run.
        if (shaped != run) return run

        val out = StringBuilder(run.length + 8)
        for (token in tokens) {
            val surface = token.surface
            when {
                // A word without kanji is already readable: katakana stays
                // katakana, so a misheard loanword is still recognisable.
                !KanaConverterText.hasKanji(surface) -> out.append(surface)

                // Kanji: replace with its reading (kuromoji gives katakana), then
                // fold that reading to hiragana. An unknown reading ("*") means
                // the word is not in the dictionary, so keep the surface rather
                // than inventing something.
                else -> {
                    val reading = token.reading?.takeIf { it.isNotBlank() }
                    out.append(
                        if (reading != null) KanaConverterText.katakanaToHiragana(reading) else surface,
                    )
                }
            }
        }
        return out.toString()
    }

    /**
     * True for Japanese script that takes part in a convertible run.
     *
     * Kanji is what gets rewritten; hiragana and katakana are included so a run
     * spans whole words (kuromoji needs that context to give the right reading
     * for kanji next to them). ASCII, digits and symbols are boundaries: a URL
     * or an identifier is never partially analysed.
     */
    private fun isConvertible(cp: Int): Boolean = when (Character.UnicodeScript.of(cp)) {
        Character.UnicodeScript.HAN,
        Character.UnicodeScript.HIRAGANA,
        Character.UnicodeScript.KATAKANA,
        -> true
        // The prolonged sound mark and iteration marks belong to Japanese words.
        else -> cp == 0x30FC || cp == 0x3005
    }

    companion object {
        /** Shared instance: the dictionary is large, so load it at most once. */
        val shared: KanaConverter by lazy { KanaConverter() }
    }
}

/** One analysed word: its surface text and its kana reading, when known. */
data class AnalysedToken(val surface: String, val reading: String?)

/** Turns a Japanese phrase into tokens. Implemented by the Kuromoji adapter. */
fun interface TokenAnalyser {
    fun tokens(text: String): List<AnalysedToken>?
}

/** Text-level helpers that are independent of the dictionary. */
object KanaConverterText {

    /** Returns [text] unchanged; documents that ASCII is never converted. */
    fun keepAscii(text: String): String = text

    /**
     * Folds katakana to hiragana, keeping the prolonged sound mark (ー) as is,
     * which is how Japanese is normally written in all-hiragana style
     * (「こーど」 rather than the unusual 「こうど」).
     */
    fun katakanaToHiragana(text: String): String {
        val out = StringBuilder(text.length)
        for (ch in text) {
            out.append(
                when (ch) {
                    in '\u30A1'..'\u30F6' -> ch - 0x60
                    else -> ch
                },
            )
        }
        return out.toString()
    }

    /** True when the text contains at least one kanji. */
    fun hasKanji(text: String): Boolean = text.any { Character.UnicodeScript.of(it.code) == Character.UnicodeScript.HAN }
}
