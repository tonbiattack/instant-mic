package com.tonbiattack.instantmic.domain

/**
 * Converts spoken punctuation words into symbols in literal dictated text.
 *
 * Mirrors `ShapeText` in `internal/speech/shape.go`; keep the tables and rules in
 * sync. Only literal text reaches this object — commands are resolved first, so
 * a command word is never rewritten.
 *
 * Standalone words ("まる", "てん", …) convert only when they end the text or are
 * followed by whitespace/existing punctuation. That keeps 「まるで」「てんぷら」
 * intact. Paired bracket words convert only when the partner word is present, so
 * the result is never an unbalanced bracket.
 */
object TextShaper {

    private data class Word(
        val spoken: String,
        val symbol: String,
        val partner: String? = null,
    )

    // Longest first, so "かっことじ" wins over "かっこ" at the same position.
    private val words = listOf(
        Word("かぎかっことじ", "」", partner = "かぎかっこ"),
        Word("かぎかっこ", "「", partner = "かぎかっことじ"),
        Word("かっことじ", "）", partner = "かっこ"),
        Word("かっこ", "（", partner = "かっことじ"),
        Word("びっくりまーく", "！"),
        Word("すらっしゅ", "/"),
        Word("せみころん", "；"),
        Word("ころん", "："),
        Word("はてな", "？"),
        Word("まる", "。"),
        Word("てん", "、"),
    )

    fun shape(text: String): String {
        if (text.isEmpty()) return text
        val out = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val word = words.firstOrNull { text.startsWith(it.spoken, i) }
            if (word == null) {
                out.append(text[i])
                i++
                continue
            }
            val end = i + word.spoken.length
            val usable = if (word.partner != null) {
                partnerPresent(text, word.partner)
            } else {
                boundaryAfter(text, end)
            }
            if (!usable) {
                out.append(text[i])
                i++
                continue
            }

            out.append(word.symbol)
            i = end
            // Avoid "、、" or "。。" when the recognizer already punctuated.
            if (i < text.length && text[i].toString() == word.symbol) i++
        }
        return out.toString()
    }

    /** True when shaping would change [text]; used to show the result in the UI. */
    fun changed(text: String): Boolean = shape(text) != text

    private fun boundaryAfter(text: String, index: Int): Boolean {
        if (index >= text.length) return true
        val ch = text[index]
        if (ch.isWhitespace()) return true
        return isPunctuation(ch)
    }

    private fun isPunctuation(ch: Char): Boolean = when (ch) {
        '、', '。', '，', '．', '！', '？', '!', '?', '：', '；', ',', '.', ':', ';',
        '（', '）', '「', '」', '『', '』', '(', ')' -> true
        else -> false
    }

    /**
     * True when the partner word appears as a whole word.
     *
     * A substring check would match "かっこ" inside "かっことじ" and leave an
     * unbalanced bracket, so a match that is only the prefix of a longer word is
     * skipped.
     */
    private fun partnerPresent(text: String, partner: String): Boolean {
        var index = text.indexOf(partner)
        while (index >= 0) {
            val longer = words.any { it.spoken.length > partner.length && text.startsWith(it.spoken, index) }
            if (!longer) return true
            index = text.indexOf(partner, index + 1)
        }
        return false
    }
}
