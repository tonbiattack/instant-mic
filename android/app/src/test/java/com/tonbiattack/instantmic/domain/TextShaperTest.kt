package com.tonbiattack.instantmic.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TextShaperTest {

    @Test
    fun `spoken punctuation becomes a symbol at a boundary`() {
        val cases = mapOf(
            "こんにちはまる" to "こんにちは。",
            "これとこれてん、お願い" to "これとこれ、お願い",
            "そうですねまるまる" to "そうですねまる。",
            "本当にはてな？" to "本当に？",
            "かっこテストかっことじ" to "（テスト）",
            "びっくりまーく" to "！",
            "ぱすはすらっしゅ、です" to "ぱすは/、です",
        )
        for ((input, expected) in cases) {
            assertEquals(input, expected, TextShaper.shape(input))
        }
    }

    @Test
    fun `words inside ordinary words are left alone`() {
        for (input in listOf("まるで違う", "てんぷらが食べたい", "かっこいい", "ころんで", "おわり")) {
            assertEquals(input, input, TextShaper.shape(input))
        }
    }

    @Test
    fun `unpaired brackets stay literal`() {
        assertEquals("かっこだけ", TextShaper.shape("かっこだけ"))
        assertEquals("かっことじだけ", TextShaper.shape("かっことじだけ"))
    }

    @Test
    fun `duplicate punctuation is not doubled`() {
        assertEquals("これは。", TextShaper.shape("これはまる。"))
        assertEquals("これは、", TextShaper.shape("これはてん、"))
    }

    @Test
    fun `coding symbols convert only as whole words`() {
        assertEquals("_", TextShaper.shape("あんだーすこあ"))
        assertEquals("@", TextShaper.shape("はいろふん"))
        assertEquals("[]", TextShaper.shape("かっこはな"))
        assertEquals("{}", TextShaper.shape("かっこなみ"))
        // Longest match wins: かっこはな must not fall back to かっこ.
        assertEquals("()", TextShaper.shape("かっこまる"))
        // Mid-word occurrences stay untouched.
        assertEquals("ぷらすの", TextShaper.shape("ぷらすの"))
    }

    @Test
    fun `changed reports whether shaping has an effect`() {
        assertTrue(TextShaper.changed("こんにちはまる"))
        assertFalse(TextShaper.changed("こんにちは"))
    }

    @Test
    fun `parser shapes literal text but never commands`() {
        val text = CommandParser.interpret("こんにちはまる")
        assertEquals("こんにちは。", (text as CommandParser.Result.Text).text)

        val command = CommandParser.interpret("コマンド、送信")
        assertTrue(command is CommandParser.Result.Key)

        val prefixWord = CommandParser.interpret("コマンド一覧を教えて")
        assertEquals("コマンド一覧を教えて", (prefixWord as CommandParser.Result.Text).text)
    }
}
