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

    /**
     * Words that mean "undo the last insertion". They are matched as whole
     * command words, exactly like the key commands.
     */
    private val ERASE_PHRASES = setOf("言い直し", "言直し").map(::normalize).toSet()

    private class Entry(
        val phrase: String,
        val command: KeyCommand,
        val aliases: List<String> = emptyList(),
        val allowRepeat: Boolean = false,
    )

    // Mirrors builtinCommands in internal/speech/speech.go. Speech recognition
    // only gets aliases that were actually observed ("開業" for 改行); destructive
    // commands deliberately have no aliases so a misrecognition cannot delete
    // text by accident.
    private val builtinEntries = listOf(
        Entry("送信", KeyCommand.ENTER, aliases = listOf("エンター", "えんたー", "エンタ")),
        Entry("改行", KeyCommand.SHIFT_ENTER, aliases = listOf("開業", "シフトエンター")),
        Entry("取り消し", KeyCommand.CTRL_Z, aliases = listOf("取消")),
        Entry("全選択", KeyCommand.CTRL_A),
        Entry("削除", KeyCommand.BACKSPACE, aliases = listOf("バックスペース", "ばっくすぺーす"), allowRepeat = true),
        Entry("前の単語削除", KeyCommand.CTRL_BACKSPACE, aliases = listOf("単語削除"), allowRepeat = true),
        Entry("行削除", KeyCommand.DELETE_LINE),
        Entry("デリート", KeyCommand.DELETE, aliases = listOf("でりーと"), allowRepeat = true),
        Entry("上", KeyCommand.UP, aliases = listOf("アップ", "あっぷ"), allowRepeat = true),
        Entry("下", KeyCommand.DOWN, aliases = listOf("ダウン", "だうん"), allowRepeat = true),
        Entry("左", KeyCommand.LEFT, aliases = listOf("レフト", "れふと"), allowRepeat = true),
        Entry("右", KeyCommand.RIGHT, aliases = listOf("ライト", "らいと"), allowRepeat = true),
        Entry("コピー", KeyCommand.COPY),
        Entry("貼り付け", KeyCommand.PASTE, aliases = listOf("貼りつけ", "ペースト", "ぺーすと")),
        Entry("切り取り", KeyCommand.CUT),
        Entry("やり直し", KeyCommand.REDO, aliases = listOf("リドゥ")),
        Entry("タブ切り替え", KeyCommand.NEXT_TAB),
    )

    private val byPhrase: Map<String, Entry> = buildMap {
        for (entry in builtinEntries) {
            put(normalize(entry.phrase), entry)
            for (alias in entry.aliases) put(normalize(alias), entry)
        }
    }

    /** Normalized built-in phrases, used to reject custom-command conflicts. */
    fun builtinPhrases(): Set<String> = byPhrase.keys + ERASE_PHRASES

    sealed interface Result {
        /** Literal text to insert. */
        data class Text(val text: String) : Result

        /** A key sequence to perform. */
        data class Key(val command: KeyCommand, val raw: String) : Result

        /** Remove the last text insertion ("言い直し"). */
        data class Erase(val raw: String) : Result

        /** The wake prefix was used with an unsupported word. Do nothing. */
        data class UnknownCommand(val raw: String) : Result

        /** Nothing was recognized. */
        data object Empty : Result
    }

    fun interpret(
        transcript: String,
        prefix: String = DEFAULT_PREFIX,
        custom: List<CustomCommand> = emptyList(),
    ): Result {
        val trimmed = transcript.trim()
        if (trimmed.isEmpty()) return Result.Empty

        // NFKC folds full-width ASCII and spacing. Punctuation is still intact
        // here, so a spoken separator can be told apart from a glued word.
        val light = java.text.Normalizer.normalize(trimmed, java.text.Normalizer.Form.NFKC).trim()
        val normPrefix = java.text.Normalizer.normalize(prefix, java.text.Normalizer.Form.NFKC)
        if (!light.startsWith(normPrefix)) return Result.Text(TextShaper.shape(trimmed))

        var after = light.substring(normPrefix.length)
        var separated = false
        while (after.isNotEmpty() && isSeparator(after.first())) {
            separated = true
            after = after.substring(1)
        }

        val normRest = normalize(after)
        if (normRest in ERASE_PHRASES) return Result.Erase(normPrefix + normRest)
        builtinResult(normRest)?.let { return it }
        customResult(normRest, custom)?.let { return it }

        return when {
            !separated && normRest.isNotEmpty() -> Result.Text(TextShaper.shape(trimmed))
            else -> Result.UnknownCommand(normPrefix + normRest)
        }
    }

    private fun builtinResult(normRest: String): Result.Key? {
        byPhrase[normRest]?.let { entry ->
            return Result.Key(entry.command, DEFAULT_PREFIX + normRest)
        }
        val (base, count) = splitRepeat(normRest) ?: return null
        val entry = byPhrase[base] ?: return null
        if (!entry.allowRepeat) return null
        return Result.Key(entry.command.repeated(count), DEFAULT_PREFIX + normRest)
    }

    private fun customResult(normRest: String, custom: List<CustomCommand>): Result.Key? {
        if (custom.isEmpty()) return null
        val match = custom.firstOrNull { normalize(it.phrase) == normRest } ?: return null
        val canonical = KeySequence.normalize(match.sequence) ?: return null
        return Result.Key(KeyCommand(canonical, KeySequence.label(canonical)), DEFAULT_PREFIX + normRest)
    }

    /**
     * Splits an optional spoken repeat count. `3回削除`, `削除3回` and `三回削除`
     * all return ("削除", 3). The count is only honoured for repeatable commands.
     */
    fun splitRepeat(normRest: String): Pair<String, Int>? {
        if (normRest.isEmpty()) return null
        val idx = normRest.indexOf("回")
        if (idx in 1 until normRest.length) {
            val n = parseCount(normRest.substring(0, idx))
            if (n != null) return normRest.substring(idx + 1) to n
        }
        if (normRest.endsWith("回")) {
            val body = normRest.dropLast(1)
            var start = body.length
            while (start > 0 && (body[start - 1].isDigit() || body[start - 1] in KANJI_DIGITS)) {
                start--
            }
            if (start < body.length) {
                val n = parseCount(body.substring(start))
                if (n != null) return body.substring(0, start) to n
            }
        }
        return null
    }

    private val KANJI_DIGITS = "一二三四五六七八九十"

    private fun parseCount(value: String): Int? {
        if (value.isEmpty()) return null
        value.toIntOrNull()?.let { return it.takeIf { n -> n > 0 } }

        val digits = mapOf('一' to 1, '二' to 2, '三' to 3, '四' to 4, '五' to 5,
            '六' to 6, '七' to 7, '八' to 8, '九' to 9)
        var total = 0
        var ten = false
        for (ch in value) {
            when {
                ch == '十' -> {
                    if (ten) return null
                    ten = true
                    if (total == 0) total = 1
                    total *= 10
                }
                digits.containsKey(ch) -> total += digits.getValue(ch)
                else -> return null
            }
        }
        return total.takeIf { it > 0 }
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
