package com.tonbiattack.instantmic.domain

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The pure parts of kana conversion: what must be preserved, and what must be
 * converted. The Kuromoji-backed analyser itself is exercised on the device;
 * these rules are the ones a regression would break silently.
 */
class KanaConverterRulesTest {

    @Test
    fun `ASCII, digits and URLs are left untouched`() {
        assertEquals("https://github.com/tonbiattack", KanaConverterText.keepAscii("https://github.com/tonbiattack"))
        assertEquals("v1.2.3 と src/main.kt", KanaConverterText.keepAscii("v1.2.3 と src/main.kt"))
    }

    @Test
    fun `katakana becomes hiragana`() {
        assertEquals("ぎっとはぶ", KanaConverterText.katakanaToHiragana("ギットハブ"))
        assertEquals("ぷるりくえすと", KanaConverterText.katakanaToHiragana("プルリクエスト"))
    }

    @Test
    fun `the long vowel mark and small kana are preserved`() {
        assertEquals("こーど", KanaConverterText.katakanaToHiragana("コード"))
        assertEquals("しゃしん", KanaConverterText.katakanaToHiragana("シャシン"))
    }

    @Test
    fun `punctuation survives and katakana is folded`() {
        assertEquals(
            "これは、てすとです。",
            KanaConverterText.katakanaToHiragana("これは、テストです。"),
        )
    }
}
