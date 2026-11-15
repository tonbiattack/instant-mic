package com.tonbiattack.instantmic.domain

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The pure parts of kana conversion: what must be converted, and what must be
 * preserved. The Kuromoji-backed analyser itself is exercised separately.
 */
class KanaConverterRulesTest {

    @Test
    fun `ASCII, digits and URLs are left untouched`() {
        assertEquals("https://github.com/tonbiattack", KanaConverterText.keepAscii("https://github.com/tonbiattack"))
        assertEquals("v1.2.3 と src/main.kt", KanaConverterText.keepAscii("v1.2.3 と src/main.kt"))
    }

    @Test
    fun `katakana becomes hiragana when asked`() {
        assertEquals("ぎっとはぶ", KanaConverterText.katakanaToHiragana("ギットハブ"))
        assertEquals("ぷるりくえすと", KanaConverterText.katakanaToHiragana("プルリクエスト"))
    }

    @Test
    fun `the long vowel mark and small kana are preserved`() {
        assertEquals("こーど", KanaConverterText.katakanaToHiragana("コード"))
        assertEquals("しゃしん", KanaConverterText.katakanaToHiragana("シャシン"))
    }

    @Test
    fun `punctuation survives and katakana is folded by the helper`() {
        assertEquals(
            "これは、てすとです。",
            KanaConverterText.katakanaToHiragana("これは、テストです。"),
        )
    }

    @Test
    fun `hasKanji distinguishes the scripts that matter`() {
        assertEquals(true, KanaConverterText.hasKanji("会議"))
        assertEquals(false, KanaConverterText.hasKanji("シークレット"))
        assertEquals(false, KanaConverterText.hasKanji("かいぎ"))
        assertEquals(false, KanaConverterText.hasKanji("GitHub"))
    }

    /**
     * Real device case: 「GitHub シークレット」 was recognised as
     * 「ギターボシークレット」. Folding katakana turned it into
     * 「ぎたーぶしーくれっと」, which no longer reads as the original words.
     * Katakana must survive so a misheard loanword stays recognisable.
     */
    @Test
    fun `a misheard loanword stays readable when katakana is kept`() {
        val converter = KanaConverter(FakeAnalyser(mapOf(
            "ギターボシークレット" to listOf(
                AnalysedToken("ギターボ", null),
                AnalysedToken("シークレット", null),
            ),
        )))
        assertEquals("ギターボシークレット", converter.convert("ギターボシークレット"))
    }

    @Test
    fun `kanji is replaced by its reading`() {
        val converter = KanaConverter(FakeAnalyser(mapOf(
            "会議があります" to listOf(
                AnalysedToken("会議", "カイギ"),
                AnalysedToken("が", "ガ"),
                AnalysedToken("あり", "アリ"),
                AnalysedToken("ます", "マス"),
            ),
        )))
        assertEquals("かいぎがあります", converter.convert("会議があります"))
    }

    @Test
    fun `katakana next to kanji is not folded`() {
        val converter = KanaConverter(FakeAnalyser(mapOf(
            "シークレットを解く" to listOf(
                AnalysedToken("シークレット", null),
                AnalysedToken("を", "ヲ"),
                AnalysedToken("解く", "トク"),
            ),
        )))
        assertEquals("シークレットをとく", converter.convert("シークレットを解く"))
    }

    @Test
    fun `an unknown reading keeps the surface instead of inventing one`() {
        val converter = KanaConverter(FakeAnalyser(mapOf(
            "難しい語" to listOf(
                AnalysedToken("難しい", "ムズカシイ"),
                AnalysedToken("語", null),
            ),
        )))
        assertEquals("むずかしい語", converter.convert("難しい語"))
    }

    @Test
    fun `a failing analyser leaves the text alone`() {
        val converter = KanaConverter(FakeAnalyser(emptyMap()))
        assertEquals("会議があります", converter.convert("会議があります"))
    }
}

/** Returns canned tokens, so the converter's rules can be tested without the dictionary. */
private class FakeAnalyser(private val table: Map<String, List<AnalysedToken>>) : TokenAnalyser {
    override fun tokens(text: String): List<AnalysedToken>? = table[text]
}
