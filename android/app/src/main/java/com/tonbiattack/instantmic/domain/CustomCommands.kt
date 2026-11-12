package com.tonbiattack.instantmic.domain

import org.json.JSONArray
import org.json.JSONObject

/**
 * User-registered voice commands.
 *
 * A custom command maps one spoken phrase (used after the wake prefix) to a key
 * sequence. Registration is intentionally strict:
 * - the phrase must not collide with a built-in or another custom command,
 * - the key sequence must only use allowlisted keys,
 * - a phrase may never be shorter than two characters, so a stray sound cannot
 *   trigger a shortcut.
 */
object CustomCommands {

    const val MAX_PHRASE_LENGTH = 40
    const val MAX_COMMANDS = 50
    private const val MIN_PHRASE_LENGTH = 2

    /** Returns a user-facing error message, or an empty list when valid. */
    fun validate(
        phrase: String,
        sequence: String,
        existing: List<CustomCommand>,
        editing: String? = null,
    ): List<String> {
        val errors = mutableListOf<String>()
        val trimmedPhrase = phrase.trim()
        val normalized = CommandParser.normalize(trimmedPhrase)

        when {
            trimmedPhrase.isEmpty() -> errors += "呼び出しフレーズを入力してください"
            trimmedPhrase.length > MAX_PHRASE_LENGTH ->
                errors += "フレーズは${MAX_PHRASE_LENGTH}文字以内にしてください"
            normalized.length < MIN_PHRASE_LENGTH ->
                errors += "フレーズは2文字以上にしてください"
            CommandParser.DEFAULT_PREFIX in normalized && normalized.startsWith(
                CommandParser.normalize(CommandParser.DEFAULT_PREFIX),
            ) -> errors += "フレーズに「${CommandParser.DEFAULT_PREFIX}」を含めないでください（自動で付きます）"
            normalized in CommandParser.builtinPhrases() ->
                errors += "「$trimmedPhrase」は標準コマンドと重複しています"
            else -> {
                val duplicate = existing.firstOrNull {
                    CommandParser.normalize(it.phrase) == normalized &&
                        (editing == null || CommandParser.normalize(editing) != normalized)
                }
                if (duplicate != null) errors += "「$trimmedPhrase」は登録済みです"
            }
        }

        if (KeySequence.normalize(sequence) == null) {
            errors += "キー操作が正しくありません（例: ctrl+enter, home>shift+end）"
        }
        if (existing.size >= MAX_COMMANDS && editing == null) {
            errors += "登録できるのは${MAX_COMMANDS}件までです"
        }
        return errors
    }

    /** Adds or replaces a command. Returns the new list; the caller validates. */
    fun upsert(
        existing: List<CustomCommand>,
        phrase: String,
        sequence: String,
        editing: String? = null,
    ): List<CustomCommand> {
        val canonical = KeySequence.normalize(sequence) ?: return existing
        val entry = CustomCommand(phrase.trim(), canonical)
        val target = editing?.let(CommandParser::normalize)
        val out = existing.filterNot {
            CommandParser.normalize(it.phrase) == target ||
                CommandParser.normalize(it.phrase) == CommandParser.normalize(entry.phrase)
        }.toMutableList()
        out += entry
        return out
    }

    fun remove(existing: List<CustomCommand>, phrase: String): List<CustomCommand> =
        existing.filterNot { CommandParser.normalize(it.phrase) == CommandParser.normalize(phrase) }

    /** Commands from the receiver for display, e.g. "AIに送信 → Ctrl+Enter". */
    fun describe(command: CustomCommand): String =
        "「${CommandParser.DEFAULT_PREFIX}、${command.phrase}」→ ${command.label}"

    // Stored as a compact JSON array; never contains audio or transcripts.
    fun encode(commands: List<CustomCommand>): String {
        val array = JSONArray()
        for (command in commands) {
            array.put(
                JSONObject().apply {
                    put("phrase", command.phrase)
                    put("sequence", command.sequence)
                },
            )
        }
        return array.toString()
    }

    fun decode(raw: String?): List<CustomCommand> {
        if (raw.isNullOrBlank()) return emptyList()
        return try {
            val array = JSONArray(raw)
            buildList {
                for (i in 0 until array.length()) {
                    val item = array.optJSONObject(i) ?: continue
                    val phrase = item.optString("phrase").trim()
                    val sequence = KeySequence.normalize(item.optString("sequence")) ?: continue
                    if (phrase.isEmpty()) continue
                    add(CustomCommand(phrase, sequence))
                }
            }
        } catch (e: Exception) {
            emptyList()
        }
    }
}
