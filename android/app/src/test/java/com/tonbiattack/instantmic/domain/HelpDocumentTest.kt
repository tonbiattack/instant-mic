package com.tonbiattack.instantmic.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HelpDocumentTest {

    @Test
    fun `headings carry their level`() {
        val blocks = HelpDocument.parse("# 使い方\n\n## 基本の流れ\n\n### 小見出し")
        assertEquals(
            listOf(
                HelpBlock.Heading(1, "使い方"),
                HelpBlock.Heading(2, "基本の流れ"),
                HelpBlock.Heading(3, "小見出し"),
            ),
            blocks,
        )
    }

    @Test
    fun `paragraph lines are joined without a space`() {
        // The document is Japanese and authored without hard wraps, so a space
        // between lines would appear in the rendered text.
        val blocks = HelpDocument.parse("これは\nテストです")
        assertEquals(listOf(HelpBlock.Paragraph("これはテストです")), blocks)
    }

    @Test
    fun `bullets and numbered items are grouped`() {
        val blocks = HelpDocument.parse("- 一つ目\n- 二つ目\n\n1. 手順\n2. 次の手順")
        assertEquals(
            listOf(
                HelpBlock.Bullets(listOf("一つ目", "二つ目")),
                HelpBlock.Numbers(listOf("手順", "次の手順")),
            ),
            blocks,
        )
    }

    @Test
    fun `a table keeps its header and rows`() {
        val blocks = HelpDocument.parse(
            """
            | 話しかた | 入力 |
            | --- | --- |
            | まる | 。 |
            | てん | 、 |
            """.trimIndent(),
        )
        assertEquals(
            listOf(
                HelpBlock.Table(
                    headers = listOf("話しかた", "入力"),
                    rows = listOf(listOf("まる", "。"), listOf("てん", "、")),
                ),
            ),
            blocks,
        )
    }

    @Test
    fun `an escaped pipe stays inside the cell`() {
        // The help text shows the "|" symbol itself, which must not split the row.
        val cells = HelpDocument.splitRow("| ぱいぷ | \\| |")
        assertEquals(listOf("ぱいぷ", "|"), cells)
    }

    @Test
    fun `a table without a separator is not a table`() {
        // A stray pipe in prose must not swallow the rest of the document.
        val blocks = HelpDocument.parse("| これは表ではない\n\n次の段落")
        assertTrue(blocks.none { it is HelpBlock.Table })
        assertTrue(blocks.any { it is HelpBlock.Paragraph })
    }

    @Test
    fun `inline bold and code are split into spans`() {
        val spans = HelpDocument.inline("これは**強調**と`コード`です")
        assertEquals(
            listOf(
                HelpSpan("これは"),
                HelpSpan("強調", bold = true),
                HelpSpan("と"),
                HelpSpan("コード", code = true),
                HelpSpan("です"),
            ),
            spans,
        )
    }

    @Test
    fun `an unterminated marker stays literal`() {
        // A typo must degrade to readable text instead of eating the line.
        assertEquals(listOf(HelpSpan("これは**強調")), HelpDocument.inline("これは**強調"))
        assertEquals(listOf(HelpSpan("これは`コード")), HelpDocument.inline("これは`コード"))
    }

    @Test
    fun `plain text produces a single span`() {
        assertEquals(listOf(HelpSpan("そのまま")), HelpDocument.inline("そのまま"))
    }

    @Test
    fun `the bundled help document parses into every block kind`() {
        // Guards the asset itself: a malformed edit should fail here rather than
        // render a half-empty screen on a phone.
        val markdown = javaClass.classLoader!!
            .getResourceAsStream("help.md")
            ?.bufferedReader()
            ?.use { it.readText() }
            ?: error("help.md is not on the test classpath")

        val blocks = HelpDocument.parse(markdown)
        assertTrue("no headings", blocks.any { it is HelpBlock.Heading })
        assertTrue("no paragraphs", blocks.any { it is HelpBlock.Paragraph })
        assertTrue("no bullets", blocks.any { it is HelpBlock.Bullets })
        assertTrue("no numbered steps", blocks.any { it is HelpBlock.Numbers })
        assertTrue("no tables", blocks.any { it is HelpBlock.Table })

        // Every table must have at least one body row, otherwise the separator
        // row was misread and the content silently vanished.
        for (table in blocks.filterIsInstance<HelpBlock.Table>()) {
            assertTrue("table without rows: ${table.headers}", table.rows.isNotEmpty())
        }
    }
}
