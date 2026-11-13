package com.tonbiattack.instantmic.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
    fun `a later long match does not skip an earlier one`() {
        // Regression from a real device: "github のプルリクエスト" kept the ASCII
        // case because the longer Pull Request match was applied first.
        assertEquals(
            "GitHub のPull Requestを確認",
            TermDictionary.correct("github のプルリクエストを確認"),
        )
    }

    @Test
    fun `ascii case is restored for product names`() {
        assertEquals("GitHubのPull Request", TermDictionary.correct("githubのPull Request"))
        assertEquals("TypeScriptとJavaScript", TermDictionary.correct("typescriptとjavascript"))
        assertEquals("SQLを直す", TermDictionary.correct("sqlを直す"))
    }

    @Test
    fun `ascii case is not forced on shell or everyday terms`() {
        // `go build` and `git status` are real commands, not product names.
        assertEquals("go build ./...", TermDictionary.correct("go build ./..."))
        assertEquals("git status を実行", TermDictionary.correct("git status を実行"))
        assertEquals("commit しておく", TermDictionary.correct("commit しておく"))
    }

    @Test
    fun `ascii case is not restored inside identifiers or urls`() {
        assertEquals("https://github.com/tonbiattack", TermDictionary.correct("https://github.com/tonbiattack"))
        assertEquals("my_github_token と github.com", TermDictionary.correct("my_github_token と github.com"))
        assertEquals("`github` を読む", TermDictionary.correct("`github` を読む"))
        assertEquals("--github オプション", TermDictionary.correct("--github オプション"))
    }

    @Test
    fun `a mishearing inside a longer katakana word is not replaced`() {
        // Real-device regression: "レビュー" became "レVue".
        assertEquals("レビューします", TermDictionary.correct("レビューします"))
        assertEquals("プレビューを開く", TermDictionary.correct("プレビューを開く"))
        // A standalone word still converts.
        assertEquals("Vueを使う", TermDictionary.correct("ビューを使う"))
    }

    @Test
    fun `user terms override the built-in list`() {
        val user = listOf(TermDictionary.Term("Kubernetes", listOf("クバネティス"), userDefined = true))
        val merged = TermDictionary.withUserTerms(user)
        assertTrue(merged.any { it.text == "Kubernetes" && it.userDefined })
        assertEquals("Kubernetes", TermDictionary.correct("クバネティス", merged))
    }

    @Test
    fun `user term validation rejects unsafe input`() {
        assertNotNull(TermDictionary.validateTerm("", "クバネティス"))
        assertNotNull(TermDictionary.validateTerm("Kubernetes", ""))
        // An ASCII mishearing would rewrite code and URLs.
        assertNotNull(TermDictionary.validateTerm("Kubernetes", "kubernetes"))
        assertNull(TermDictionary.validateTerm("Kubernetes", "クバネティス"))
    }

    @Test
    fun `dictionary can be disabled by passing no terms`() {
        assertEquals("ギットハブ", TermDictionary.correct("ギットハブ", terms = emptyList()))
    }
}
