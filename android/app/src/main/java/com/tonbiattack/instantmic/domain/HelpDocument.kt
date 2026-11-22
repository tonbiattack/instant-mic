package com.tonbiattack.instantmic.domain

/**
 * A block of the bundled help document (`assets/help.md`).
 *
 * The help text ships as Markdown so it stays readable in the repository and can
 * be edited without touching Kotlin. Only the subset used by that file is
 * supported, which keeps the parser small enough to test exhaustively instead of
 * pulling in a Markdown library for one screen.
 */
sealed interface HelpBlock {
    data class Heading(val level: Int, val text: String) : HelpBlock

    data class Paragraph(val text: String) : HelpBlock

    data class Bullets(val items: List<String>) : HelpBlock

    data class Numbers(val items: List<String>) : HelpBlock

    data class Table(val headers: List<String>, val rows: List<List<String>>) : HelpBlock
}

/** A run of inline text with the emphasis the renderer should apply. */
data class HelpSpan(
    val text: String,
    val bold: Boolean = false,
    val code: Boolean = false,
)

/**
 * Parses the help Markdown into renderable blocks.
 *
 * Supported: `#`/`##`/`###` headings, `-` bullets, `1.` numbered items, pipe
 * tables (with `\|` for a literal pipe), and paragraphs. Inline `**bold**` and
 * `` `code` `` are handled by [inline].
 *
 * Paragraph lines are joined without a separator: the document is Japanese and
 * authored without hard wraps, so inserting a space would show up in the text.
 */
object HelpDocument {

    private val numbered = Regex("""^\d+\.\s+""")

    fun parse(markdown: String): List<HelpBlock> {
        val blocks = mutableListOf<HelpBlock>()
        val lines = markdown.lines()
        var i = 0
        while (i < lines.size) {
            val trimmed = lines[i].trim()
            when {
                trimmed.isEmpty() -> i++

                trimmed.startsWith("#") -> {
                    val level = trimmed.takeWhile { it == '#' }.length
                    blocks += HelpBlock.Heading(level, trimmed.drop(level).trim())
                    i++
                }

                trimmed.startsWith("|") -> {
                    val table = mutableListOf<String>()
                    while (i < lines.size && lines[i].trim().startsWith("|")) {
                        table += lines[i].trim()
                        i++
                    }
                    parseTable(table)?.let { blocks += it }
                }

                trimmed.startsWith("- ") -> {
                    val items = mutableListOf<String>()
                    while (i < lines.size && lines[i].trim().startsWith("- ")) {
                        items += lines[i].trim().removePrefix("- ").trim()
                        i++
                    }
                    blocks += HelpBlock.Bullets(items)
                }

                numbered.containsMatchIn(trimmed) -> {
                    val items = mutableListOf<String>()
                    while (i < lines.size && numbered.containsMatchIn(lines[i].trim())) {
                        items += lines[i].trim().replaceFirst(numbered, "").trim()
                        i++
                    }
                    blocks += HelpBlock.Numbers(items)
                }

                else -> {
                    val paragraph = mutableListOf<String>()
                    while (i < lines.size && isParagraphLine(lines[i])) {
                        paragraph += lines[i].trim()
                        i++
                    }
                    if (paragraph.isNotEmpty()) {
                        blocks += HelpBlock.Paragraph(paragraph.joinToString(""))
                    }
                }
            }
        }
        return blocks
    }

    private fun isParagraphLine(line: String): Boolean {
        val trimmed = line.trim()
        return trimmed.isNotEmpty() &&
            !trimmed.startsWith("#") &&
            !trimmed.startsWith("|") &&
            !trimmed.startsWith("- ") &&
            !numbered.containsMatchIn(trimmed)
    }

    /**
     * Builds a table from its raw lines: a header row, a `---` separator row, then
     * body rows. Returns null when the shape is not a table, so a stray pipe in
     * prose cannot swallow the rest of the document.
     */
    private fun parseTable(lines: List<String>): HelpBlock.Table? {
        if (lines.size < 2) return null
        val headers = splitRow(lines[0])
        if (!isSeparatorRow(lines[1])) return null
        val rows = lines.drop(2).map(::splitRow).filter { it.isNotEmpty() }
        if (headers.isEmpty()) return null
        return HelpBlock.Table(headers, rows)
    }

    private fun isSeparatorRow(line: String): Boolean {
        val cells = splitRow(line)
        return cells.isNotEmpty() && cells.all { cell ->
            cell.isNotEmpty() && cell.all { it == '-' || it == ':' }
        }
    }

    /**
     * Splits a table row on unescaped pipes.
     *
     * `\|` is a literal pipe inside a cell, which the help text needs to show the
     * `|` symbol itself.
     */
    fun splitRow(line: String): List<String> {
        var body = line.trim()
        if (body.startsWith("|")) body = body.substring(1)
        if (body.endsWith("|") && !body.endsWith("\\|")) body = body.dropLast(1)

        val cells = mutableListOf<String>()
        val current = StringBuilder()
        var i = 0
        while (i < body.length) {
            val ch = body[i]
            if (ch == '\\' && i + 1 < body.length && body[i + 1] == '|') {
                current.append('|')
                i += 2
                continue
            }
            if (ch == '|') {
                cells += current.toString().trim()
                current.clear()
                i++
                continue
            }
            current.append(ch)
            i++
        }
        cells += current.toString().trim()
        return cells
    }

    /**
     * Splits inline text into styled spans.
     *
     * `**bold**` and `` `code` `` are the only markers the help file uses. An
     * unterminated marker is left as literal text rather than swallowing the rest
     * of the line, so a typo degrades to readable output.
     */
    fun inline(text: String): List<HelpSpan> {
        val spans = mutableListOf<HelpSpan>()
        val plain = StringBuilder()
        var i = 0
        while (i < text.length) {
            if (text.startsWith("**", i)) {
                val end = text.indexOf("**", i + 2)
                if (end > i + 2) {
                    if (plain.isNotEmpty()) {
                        spans += HelpSpan(plain.toString())
                        plain.clear()
                    }
                    spans += HelpSpan(text.substring(i + 2, end), bold = true)
                    i = end + 2
                    continue
                }
            }
            if (text[i] == '`') {
                val end = text.indexOf('`', i + 1)
                if (end > i + 1) {
                    if (plain.isNotEmpty()) {
                        spans += HelpSpan(plain.toString())
                        plain.clear()
                    }
                    spans += HelpSpan(text.substring(i + 1, end), code = true)
                    i = end + 1
                    continue
                }
            }
            plain.append(text[i])
            i++
        }
        if (plain.isNotEmpty()) spans += HelpSpan(plain.toString())
        return spans
    }
}
