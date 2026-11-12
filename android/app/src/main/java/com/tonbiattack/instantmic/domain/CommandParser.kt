package com.tonbiattack.instantmic.domain

/**
 * Interprets finalized Japanese speech transcripts.
 *
 * This mirrors the receiver implementation in `internal/speech/speech.go`. The
 * receiver applies the same rules again, so a command can never execute through
 * partial matching. Keep both files in sync.
 */
object CommandParser {

    const val DEFAULT_PREFIX = "コマンド"

    /** Maximum number of characters accepted by the receiver. */
    const val MAX_TEXT_LENGTH = 4000

    private val commandKeys = mapOf(
        "送信" to KeyAction.ENTER,
        "改行" to KeyAction.SHIFT_ENTER,
        "取り消し" to KeyAction.CTRL_Z,
        "取消" to KeyAction.CTRL_Z,
        "全選択" to KeyAction.CTRL_A,
    )

    sealed interface Result {
        /** Literal text to insert. */
        data class Text(val text: String) : Result

        /** A fixed key action to perform. */
        data class Key(val action: KeyAction, val raw: String) : Result

        /** The wake prefix was used with an unsupported word. Do nothing. */
        data class UnknownCommand(val raw: String) : Result

        /** Nothing was recognized. */
        data object Empty : Result
    }

    fun interpret(transcript: String, prefix: String = DEFAULT_PREFIX): Result {
        val trimmed = transcript.trim()
        if (trimmed.isEmpty()) return Result.Empty

        // NFKC folds full-width ASCII and spacing. Punctuation is still intact
        // here, so a spoken separator can be told apart from a glued word.
        val light = java.text.Normalizer.normalize(trimmed, java.text.Normalizer.Form.NFKC).trim()
        val normPrefix = java.text.Normalizer.normalize(prefix, java.text.Normalizer.Form.NFKC)
        if (!light.startsWith(normPrefix)) return Result.Text(trimmed)

        var after = light.substring(normPrefix.length)
        var separated = false
        while (after.isNotEmpty() && isSeparator(after.first())) {
            separated = true
            after = after.substring(1)
        }

        val normRest = normalize(after)
        val action = commandKeys[normRest]
        return when {
            action != null -> Result.Key(action, normPrefix + normRest)
            !separated && normRest.isNotEmpty() -> Result.Text(trimmed)
            else -> Result.UnknownCommand(normPrefix + normRest)
        }
    }

    /** Folds whitespace, punctuation and width so command variants compare equal. */
    fun normalize(value: String): String {
        val folded = java.text.Normalizer.normalize(value, java.text.Normalizer.Form.NFKC)
        val out = StringBuilder(folded.length)
        var i = 0
        while (i < folded.length) {
            val cp = folded.codePointAt(i)
            i += Character.charCount(cp)
            if (Character.isWhitespace(cp) || isPunctuationOrSymbol(cp)) continue
            out.appendCodePoint(Character.toLowerCase(cp))
        }
        return out.toString()
    }

    /** Speech recognition often inserts 、 or a space after the wake word. */
    private fun isSeparator(ch: Char): Boolean {
        return when (ch) {
            '、', ',', '，', '。', '．', '!', '！', '?', '？', ':', '：', '「', '」',
            '『', '』', '・' -> true
            else -> ch.isWhitespace()
        }
    }

    private fun isPunctuationOrSymbol(cp: Int): Boolean {
        return when (Character.getType(cp)) {
            Character.CONNECTOR_PUNCTUATION.toInt(),
            Character.DASH_PUNCTUATION.toInt(),
            Character.START_PUNCTUATION.toInt(),
            Character.END_PUNCTUATION.toInt(),
            Character.INITIAL_QUOTE_PUNCTUATION.toInt(),
            Character.FINAL_QUOTE_PUNCTUATION.toInt(),
            Character.OTHER_PUNCTUATION.toInt(),
            Character.MATH_SYMBOL.toInt(),
            Character.CURRENCY_SYMBOL.toInt(),
            Character.MODIFIER_SYMBOL.toInt(),
            Character.OTHER_SYMBOL.toInt() -> true
            else -> false
        }
    }
}

enum class KeyAction(val wire: String, val label: String) {
    ENTER("enter", "Enter"),
    SHIFT_ENTER("shift+enter", "Shift+Enter"),
    CTRL_Z("ctrl+z", "Ctrl+Z"),
    CTRL_A("ctrl+a", "Ctrl+A");

    companion object {
        fun fromWire(value: String): KeyAction? = entries.firstOrNull { it.wire == value }
    }
}
