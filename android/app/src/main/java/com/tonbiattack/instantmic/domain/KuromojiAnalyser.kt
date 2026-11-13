package com.tonbiattack.instantmic.domain

import com.atilika.kuromoji.ipadic.Token
import com.atilika.kuromoji.ipadic.Tokenizer

/**
 * Kuromoji adapter for [TokenAnalyser].
 *
 * Loading the bundled IPADIC dictionary takes a moment and a few tens of MB, so
 * this is built lazily and never on the UI thread's critical path: [KanaConverter]
 * falls back to katakana folding when the analyser is not ready or fails.
 */
class KuromojiAnalyser : TokenAnalyser {

    private val tokenizer: Tokenizer? = try {
        Tokenizer()
    } catch (e: Throwable) {
        // A missing or unreadable dictionary must not break dictation.
        null
    }

    override fun tokens(text: String): List<AnalysedToken>? {
        val analyser = tokenizer ?: return null
        return try {
            analyser.tokenize(text).map { it.toAnalysed() }
        } catch (e: Exception) {
            null
        }
    }

    private fun Token.toAnalysed(): AnalysedToken {
        // Kuromoji reports the reading in katakana; "*" means "unknown".
        val reading = reading.takeIf { it != "*" && it.isNotBlank() && it != surface }
        return AnalysedToken(surface, reading)
    }
}
