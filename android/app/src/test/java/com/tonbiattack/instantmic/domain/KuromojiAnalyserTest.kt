package com.tonbiattack.instantmic.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies the bundled dictionary really produces readings.
 *
 * This runs on the JVM (the analyser is pure Java), so CI catches a broken
 * dictionary or a bad Kuromoji upgrade without a device. Kuromoji reads its
 * dictionary from the jar, so a packaging change that drops the resources shows
 * up here as a null reading rather than silently disabling the feature on the
 * phone.
 */
class KuromojiAnalyserTest {

    private val analyser = KuromojiAnalyser()

    @Test
    fun `the dictionary loads`() {
        assertTrue("Kuromoji tokenizer did not load", analyser.tokens("今日") != null)
    }

    @Test
    fun `kanji gets a kana reading`() {
        val tokens = analyser.tokens("今日は会議があります")
        assertTrue("no tokens", tokens != null)
        val readings = tokens!!.mapNotNull { it.reading }
        assertTrue("no readings: $tokens", readings.isNotEmpty())
        // 今日 → キョウ, 会議 → カイギ (katakana from Kuromoji).
        assertEquals("キョウ", tokens.first { it.surface == "今日" }.reading)
        assertEquals("カイギ", tokens.first { it.surface == "会議" }.reading)
    }

    @Test
    fun `kana input needs no reading`() {
        // Hiragana is already what the converter wants, so Kuromoji reports the
        // surface as the reading; the converter must not double-convert it.
        val tokens = analyser.tokens("きょうは")
        assertTrue(tokens != null)
        assertEquals("きょうは", tokens!!.joinToString("") { it.surface })
    }

    @Test
    fun `the converter produces hiragana and keeps ascii`() {
        val converter = KanaConverter(analyser)
        assertEquals("きょうはかいぎがあります", converter.convert("今日は会議があります"))
        // ASCII, digits and URLs are never converted.
        assertEquals(
            "v1.2.3 を https://github.com/x に おくる",
            converter.convert("v1.2.3 を https://github.com/x に おくる"),
        )
    }
}
