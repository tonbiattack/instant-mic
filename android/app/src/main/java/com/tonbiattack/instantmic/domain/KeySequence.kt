package com.tonbiattack.instantmic.domain

/**
 * Validates and canonicalises key sequences shared with the Windows receiver.
 *
 * The same allowlist is enforced by `internal/speech` in the receiver, so a
 * mistyped or malicious custom shortcut can never reach an arbitrary key. A
 * sequence is one or more chords separated by `>`; a chord is modifiers and a
 * single key separated by `+`.
 */
object KeySequence {

    const val MAX_STEPS = 8
    private const val MAX_LENGTH = 256

    private val namedKeys = setOf(
        "enter", "tab", "escape", "backspace", "delete", "insert", "space",
        "up", "down", "left", "right", "home", "end", "pageup", "pagedown",
    )

    private val keyAliases = mapOf(
        "return" to "enter",
        "esc" to "escape",
        "del" to "delete",
        "ins" to "insert",
        "spacebar" to "space",
        "pgup" to "pageup",
        "pgdn" to "pagedown",
        "arrowup" to "up",
        "arrowdown" to "down",
        "arrowleft" to "left",
        "arrowright" to "right",
    )

    private val modifierAliases = mapOf(
        "ctrl" to "ctrl",
        "control" to "ctrl",
        "shift" to "shift",
        "alt" to "alt",
        "win" to "win",
        "windows" to "win",
        "super" to "win",
        "meta" to "win",
    )

    private val modifierOrder = listOf("ctrl", "shift", "alt", "win")

    data class Combo(val mods: List<String>, val key: String) {
        val wire: String get() = (mods + key).joinToString("+")
        val label: String get() = (mods.map(::modifierLabel) + keyLabel(key)).joinToString("+")
    }

    /** Returns the canonical sequence, or null when unsupported. */
    fun normalize(value: String): String? = parse(value)?.joinToString(">") { it.wire }

    fun parse(value: String): List<Combo>? {
        val text = value.trim()
        if (text.isEmpty() || text.length > MAX_LENGTH) return null
        val parts = text.split(">")
        if (parts.isEmpty() || parts.size > MAX_STEPS) return null
        return parts.map { parseCombo(it) ?: return null }
    }

    /** Human readable form, e.g. `Home → Shift+End → Delete`. */
    fun label(value: String): String =
        parse(value)?.joinToString(" → ") { it.label } ?: value

    private fun parseCombo(value: String): Combo? {
        val tokens = value.split("+")
        val mods = LinkedHashSet<String>()
        var key: String? = null
        for (raw in tokens) {
            val token = raw.trim().lowercase()
            if (token.isEmpty()) return null
            val mod = modifierAliases[token]
            if (mod != null) {
                mods += mod
                continue
            }
            val resolved = keyAliases[token] ?: token
            if (!isKnownKey(resolved)) return null
            if (key != null) return null
            key = resolved
        }
        val finalKey = key ?: return null
        val ordered = modifierOrder.filter { it in mods }
        // Matches the receiver: Ctrl+Alt+Delete opens the Windows security
        // screen and is never injectable, so it is rejected while typing.
        if (finalKey == "delete" && ordered == listOf("ctrl", "alt")) return null
        return Combo(ordered, finalKey)
    }

    fun isKnownKey(name: String): Boolean {
        if (name in namedKeys) return true
        if (name.length == 1 && (name[0] in 'a'..'z' || name[0] in '0'..'9')) return true
        if (name.length in 2..3 && name[0] == 'f') {
            val n = name.drop(1).toIntOrNull() ?: return false
            return n in 1..24
        }
        return false
    }

    private fun modifierLabel(mod: String): String = when (mod) {
        "ctrl" -> "Ctrl"
        "shift" -> "Shift"
        "alt" -> "Alt"
        "win" -> "Win"
        else -> mod
    }

    private fun keyLabel(key: String): String = when (key) {
        "pageup" -> "PageUp"
        "pagedown" -> "PageDown"
        else -> if (key.length == 1 || isFunctionKey(key)) key.uppercase()
        else key.replaceFirstChar { it.uppercase() }
    }

    private fun isFunctionKey(key: String): Boolean =
        key.length in 2..3 && key[0] == 'f' && key.drop(1).toIntOrNull() in 1..24
}

/** One resolved command: the key sequence to send and how many times. */
data class KeyCommand(
    val sequence: String,
    val label: String,
    val count: Int = 1,
) {
    val wire: String get() = sequence

    fun repeated(times: Int): KeyCommand = copy(count = times.coerceIn(1, MAX_REPEAT))

    companion object {
        const val MAX_REPEAT = 5

        val ENTER = KeyCommand("enter", "Enter")
        val SHIFT_ENTER = KeyCommand("shift+enter", "Shift+Enter")
        val CTRL_Z = KeyCommand("ctrl+z", "Ctrl+Z")
        val CTRL_A = KeyCommand("ctrl+a", "Ctrl+A")
        val BACKSPACE = KeyCommand("backspace", "Backspace")
        val CTRL_BACKSPACE = KeyCommand("ctrl+backspace", "Ctrl+Backspace")
        val DELETE_LINE = KeyCommand("home>shift+end>delete", "行削除 (Home → Shift+End → Delete)")
        val DELETE = KeyCommand("delete", "Delete")
        val UP = KeyCommand("up", "↑")
        val DOWN = KeyCommand("down", "↓")
        val LEFT = KeyCommand("left", "←")
        val RIGHT = KeyCommand("right", "→")
        val COPY = KeyCommand("ctrl+c", "Ctrl+C")
        val PASTE = KeyCommand("ctrl+v", "Ctrl+V")
        val CUT = KeyCommand("ctrl+x", "Ctrl+X")
        val REDO = KeyCommand("ctrl+y", "Ctrl+Y")
        val NEXT_TAB = KeyCommand("ctrl+tab", "Ctrl+Tab")
    }
}

/** A user-registered phrase that maps to a key sequence. */
data class CustomCommand(
    val phrase: String,
    val sequence: String,
) {
    val label: String get() = KeySequence.label(sequence)
}
