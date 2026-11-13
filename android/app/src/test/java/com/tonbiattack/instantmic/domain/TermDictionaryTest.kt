package com.tonbiattack.instantmic.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TermDictionaryTest {

    @Test
    fun `observed mishearings become the term`() {
        assertEquals("GitHubにpushしました。", TermDictionary.correct("ギットハブにpushしました。"))
        assertEquals("Codexで書きました。", TermDictionary.correct("コーデックスで書きました。"))
        assertEquals("SQLを直す", TermDictionary.correct("エスキューエルを直す"))
    }

    @Test
    fun `the longest match wins`() {
        // 「ギットハブ」 must win over 「ギット」, not produce "Gitハブ".
        assertEquals("GitHub", TermDictionary.correct("ギットハブ"))
    }

    @Test
    fun `matches never overlap`() {
        assertEquals("GitHubとGit", TermDictionary.correct("ギットハブとギット"))
    }

    @Test
    fun `ascii and code are left untouched`() {
        val code = "go build ./... && git push"
        assertEquals(code, TermDictionary.correct(code))
        val snippet = "`git status` を実行"
        assertEquals(snippet, TermDictionary.correct(snippet))
    }

    @Test
    fun `urls and emails are preserved`() {
        val text = "https://github.com/tonbiattack/instant-mic と ギットハブ"
        assertEquals(
            "https://github.com/tonbiattack/instant-mic と GitHub",
            TermDictionary.correct(text),
        )
        val mail = "tonbi@example.com は ゴー ではありません"
        assertTrue(TermDictionary.correct(mail).startsWith("tonbi@example.com"))
    }

    @Test
    fun `a match glued into a longer japanese word is not replaced`() {
        // 「ゴール」 contains 「ゴー」 but is a word of its own.
        assertEquals("ゴールに着いた", TermDictionary.correct("ゴールに着いた"))
    }

    @Test
    fun `labels and paths are left alone`() {
        assertEquals("API: /v1/users", TermDictionary.correct("API: /v1/users"))
    }

    @Test
    fun `numbers and plain text are untouched`() {
        assertEquals("123 と 456 を 足す", TermDictionary.correct("123 と 456 を 足す"))
        assertEquals("今日はいい天気です。", TermDictionary.correct("今日はいい天気です。"))
    }

    @Test
    fun `biasing phrases are deduplicated and bounded`() {
        val phrases = TermDictionary.biasingPhrases(limit = 5)
        assertEquals(5, phrases.size)
        assertEquals(phrases.size, phrases.toSet().size)
        assertTrue(phrases.contains("GitHub"))
    }

    @Test
    fun `dictionary can be disabled by passing no terms`() {
        assertEquals("ギットハブ", TermDictionary.correct("ギットハブ", terms = emptyList()))
    }
}
