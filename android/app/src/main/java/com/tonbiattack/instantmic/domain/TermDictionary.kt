package com.tonbiattack.instantmic.domain

/**
 * A small user dictionary of development terms.
 *
 * Speech recognizers mishear product and language names constantly ("ギット
 * ハブ", "ヌクスト", "ゴー"). Two independent mechanisms are offered:
 *
 * - **Biasing** ([biasingPhrases]): words handed to the recognizer as a hint so
 *   it is more likely to produce them. Android exposes these only through a
 *   handful of undocumented keys, and most services ignore them, so this is a
 *   best-effort path.
 * - **Correction** ([correct]): a deterministic post-pass over literal text.
 *   This is where the real wins come from, but a careless replace would destroy
 *   the user's text, so correction is deliberately conservative.
 *
 * Correction rules:
 * - Only *Japanese-script* mishearings are replaced. A term is never matched
 *   against ASCII, so `go`, `GitHub`, `https://…` and code snippets survive
 *   untouched by construction.
 * - Longest match wins, and matches never overlap.
 * - A match must stand alone: no Japanese letter may touch it on either side,
 *   so 「ゴール」 is not turned into 「Goール」.
 * - Text inside a URL, an e-mail address or an inline code span is never
 *   touched, and a match that is immediately followed by `:` or `/` is skipped
 *   (it is being used as a label or path). Bare numbers are left alone so a
 *   spelled-out digit can never be rewritten.
 */
object TermDictionary {

    /** One term and the Japanese mishearings that should become it. */
    data class Term(
        val text: String,
        val mishearings: List<String> = emptyList(),
    )

    /**
     * The built-in list. Terms are ordinary words a developer dictates; the
     * mishearings were observed with the system recognizer set to `ja-JP`.
     */
    val builtIn: List<Term> = listOf(
        Term("GitHub", listOf("ギットハブ")),
        Term("Git", listOf("ギット")),
        Term("Codex", listOf("コーデックス", "こーでっくす")),
        Term("SQL", listOf("エスキューエル", "シーケル")),
        Term("Nuxt", listOf("ヌクスト", "ナクスト")),
        Term("Next.js", listOf("ネクストジェイエス", "ネクストjs")),
        Term("Vue", listOf("ビュー")),
        Term("React", listOf("リアクト")),
        Term("TypeScript", listOf("タイプスクリプト")),
        Term("JavaScript", listOf("ジャバスクリプト")),
        Term("Python", listOf("パイソン")),
        Term("Kotlin", listOf("コトリン")),
        Term("Gradle", listOf("グレイドル")),
        Term("Docker", listOf("ドッカー")),
        Term("Kubernetes", listOf("クバネティス", "クーベネティス")),
        Term("WebSocket", listOf("ウェブソケット")),
        Term("API", listOf("エーピーアイ")),
        Term("JSON", listOf("ジェイソン", "ジェーソン")),
        Term("YAML", listOf("ヤムル", "ヤメル")),
        Term("CI/CD", listOf("シーアイシーディー")),
        Term("Pull Request", listOf("プルリクエスト")),
        Term("commit", listOf("コミット")),
        Term("merge", listOf("マージ")),
        Term("branch", listOf("ブランチ")),
        Term("frontend", listOf("フロントエンド")),
        Term("backend", listOf("バックエンド")),
        Term("refactor", listOf("リファクタ")),
        Term("deploy", listOf("デプロイ")),
        Term("Go", listOf("ゴー")),
    )

    /** A matched span in the input, used by [correct]. */
    private data class Hit(val start: Int, val end: Int, val text: String)

    /** Words to hand to the recognizer as a vocabulary hint. */
    fun biasingPhrases(terms: List<Term> = builtIn, limit: Int = 24): List<String> {
        val out = LinkedHashSet<String>()
        for (term in terms) {
            out.add(term.text)
            if (out.size >= limit) break
        }
        return out.toList()
    }

    /**
     * Applies the dictionary to literal dictated text.
     *
     * Returns [text] unchanged when there is nothing to correct, which is the
     * normal case.
     */
    fun correct(text: String, terms: List<Term> = builtIn): String {
        if (text.isEmpty() || terms.isEmpty()) return text
        val protectedRanges = protectedRanges(text)
        val candidates = mutableListOf<Hit>()
        for (term in terms) {
            for (spoken in term.mishearings) {
                if (spoken.isEmpty()) continue
                var index = text.indexOf(spoken)
                while (index >= 0) {
                    val end = index + spoken.length
                    if (isAllowed(text, index, end, protectedRanges)) {
                        candidates.add(Hit(index, end, term.text))
                    }
                    index = text.indexOf(spoken, index + 1)
                }
            }
        }
        if (candidates.isEmpty()) return text

        // Longest match first, then left to right; skip overlaps.
        candidates.sortWith(compareByDescending<Hit> { it.end - it.start }.thenBy { it.start })
        val out = StringBuilder(text.length)
        var cursor = 0
        for (hit in candidates) {
            if (hit.start < cursor) continue
            out.append(text, cursor, hit.start)
            out.append(hit.text)
            cursor = hit.end
        }
        out.append(text, cursor, text.length)
        val result = out.toString()
        // A correction that would also rewrite a URL is a bug, not a feature.
        return if (looksLikeUrlCorruption(text, result)) text else result
    }

    private fun isAllowed(text: String, start: Int, end: Int, protectedRanges: List<IntRange>): Boolean {
        if (protectedRanges.any { start <= it.last && end - 1 >= it.first }) return false
        if (!standsAlone(text.getOrNull(end))) return false
        // Used as a label ("API: …") or a path segment ("Go/…"): leave it.
        val next = text.getOrNull(end)
        if (next == ':' || next == '/') return false
        return true
    }

    /** True when the character would glue the match into a longer word. */
    private fun standsAlone(ch: Char?): Boolean {
        // A mishearing that continues as katakana is part of a longer word:
        // 「ゴー」 inside 「ゴール」, 「ギット」 inside 「ギットハブ」. Particles
        // (に, と, を …) are hiragana, so ordinary sentences still match.
        if (ch == null) return true
        return !isKatakana(ch)
    }

    private fun isKatakana(ch: Char): Boolean =
        Character.UnicodeScript.of(ch.code) == Character.UnicodeScript.KATAKANA

    /** Spans that must never be rewritten: URLs, e-mails and inline code. */
    private fun protectedRanges(text: String): List<IntRange> {
        val ranges = mutableListOf<IntRange>()
        Regex("""(?i)\b(?:https?|wss?|ftp)://\S+""").findAll(text).forEach {
            ranges.add(it.range)
        }
        Regex("""[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}""").findAll(text).forEach {
            ranges.add(it.range)
        }
        Regex("`[^`\n]*`").findAll(text).forEach {
            ranges.add(it.range)
        }
        return ranges
    }

    /**
     * Detects a correction that touched a URL or e-mail. The match rules already
     * protect them; this is a cheap final guard in case a future rule loosens
     * that.
     */
    private fun looksLikeUrlCorruption(before: String, after: String): Boolean {
        val url = Regex("""(?i)\b(?:https?|wss?|ftp)://\S+""")
        val original = url.findAll(before).map { it.value }.toList()
        if (original.isEmpty()) return false
        val corrected = url.findAll(after).map { it.value }.toList()
        return original != corrected
    }
}
