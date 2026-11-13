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
        /**
         * True when the recognizer may emit the term in ASCII with the wrong
         * case (`github`, `typescript`) and the canonical casing should be
         * restored.
         *
         * Off for words that are also ordinary English or shell terms
         * (`go`, `git`, `commit`), where forcing a capital would corrupt real
         * text such as `go build` or `git status`.
         */
        val fixCase: Boolean = false,
    )

    /**
     * The built-in list. Terms are ordinary words a developer dictates; the
     * mishearings were observed with the system recognizer set to `ja-JP`.
     */
    val builtIn: List<Term> = listOf(
        Term("GitHub", listOf("ギットハブ"), fixCase = true),
        Term("Git", listOf("ギット")),
        Term("Codex", listOf("コーデックス", "こーでっくす"), fixCase = true),
        Term("SQL", listOf("エスキューエル", "シーケル"), fixCase = true),
        Term("Nuxt", listOf("ヌクスト", "ナクスト"), fixCase = true),
        Term("Next.js", listOf("ネクストジェイエス", "ネクストjs"), fixCase = true),
        Term("Vue", listOf("ビュー")),
        Term("React", listOf("リアクト")),
        Term("TypeScript", listOf("タイプスクリプト"), fixCase = true),
        Term("JavaScript", listOf("ジャバスクリプト"), fixCase = true),
        Term("Python", listOf("パイソン"), fixCase = true),
        Term("Kotlin", listOf("コトリン"), fixCase = true),
        Term("Gradle", listOf("グレイドル"), fixCase = true),
        Term("Docker", listOf("ドッカー"), fixCase = true),
        Term("Kubernetes", listOf("クバネティス", "クーベネティス"), fixCase = true),
        Term("WebSocket", listOf("ウェブソケット"), fixCase = true),
        Term("API", listOf("エーピーアイ")),
        Term("JSON", listOf("ジェイソン", "ジェーソン"), fixCase = true),
        Term("YAML", listOf("ヤムル", "ヤメル"), fixCase = true),
        Term("CI/CD", listOf("シーアイシーディー")),
        Term("Pull Request", listOf("プルリクエスト"), fixCase = true),
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
            if (term.fixCase) {
                candidates.addAll(caseHits(text, term, protectedRanges))
            }
        }
        if (candidates.isEmpty()) return text

        // Leftmost match first, and the longest one when two start together
        // (「ギットハブ」 before 「ギット」). Sorting by length alone would apply a
        // later long match first and skip everything to its left.
        candidates.sortWith(compareBy<Hit> { it.start }.thenByDescending { it.end - it.start })
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
        if (!standsAlone(text.getOrNull(start - 1), text.getOrNull(end))) return false
        // Used as a label ("API: …") or a path segment ("Go/…"): leave it.
        val next = text.getOrNull(end)
        if (next == ':' || next == '/') return false
        return true
    }

    /**
     * True when the match is a whole word rather than part of a longer
     * katakana word.
     *
     * 「ビュー」 must not match inside 「レビュー」, and 「ゴー」 must not match
     * inside 「ゴール」. Particles (に, と, を …) are hiragana, so an ordinary
     * sentence still matches.
     */
    private fun standsAlone(before: Char?, after: Char?): Boolean {
        if (before != null && isKatakana(before)) return false
        if (after != null && isKatakana(after)) return false
        return true
    }

    /**
     * Finds the term written in ASCII with the wrong case, e.g. `github`.
     *
     * The recognizer often emits a known product name in ASCII already, so the
     * Japanese-mishearing rules never fire. Only exact case variants that touch
     * a letter or digit boundary are matched, so URLs, paths, identifiers and
     * inline code are excluded exactly like the other rules, and `github.com`
     * or `--github` is never rewritten.
     */
    private fun caseHits(text: String, term: Term, protectedRanges: List<IntRange>): List<Hit> {
        val canonical = term.text
        // Only single-token, alphabetic-leading terms are safe to case-fold.
        if (canonical.isEmpty() || !canonical[0].isLetter()) return emptyList()
        if (canonical.any { it.isWhitespace() || it == '.' || it == '/' }) return emptyList()

        val hits = mutableListOf<Hit>()
        var index = text.indexOf(canonical, ignoreCase = true)
        while (index >= 0) {
            val end = index + canonical.length
            val actual = text.substring(index, end)
            if (actual != canonical && !touchesIdentifier(text, index, end)) {
                if (!protectedRanges.any { index <= it.last && end - 1 >= it.first }) {
                    hits.add(Hit(index, end, canonical))
                }
            }
            index = text.indexOf(canonical, index + 1, ignoreCase = true)
        }
        return hits
    }

    /** True when [start, end) is part of a longer identifier, path or URL. */
    private fun touchesIdentifier(text: String, start: Int, end: Int): Boolean {
        // Only ASCII counts: a Japanese particle after the word is a sentence
        // boundary, not part of an identifier ("githubの…" is plain speech).
        fun isWordLike(ch: Char?): Boolean =
            ch != null && ch.code < 128 &&
                (ch.isLetterOrDigit() || ch == '_' || ch == '-' || ch == '.' || ch == '/')
        return isWordLike(text.getOrNull(start - 1)) || isWordLike(text.getOrNull(end))
    }

    /** True when the character would glue the match into a longer word. */
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
