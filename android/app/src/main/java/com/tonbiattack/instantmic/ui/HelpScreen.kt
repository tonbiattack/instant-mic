package com.tonbiattack.instantmic.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.tonbiattack.instantmic.domain.HelpBlock
import com.tonbiattack.instantmic.domain.HelpDocument
import com.tonbiattack.instantmic.domain.HelpSpan
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Asset name of the bundled help document. */
const val HELP_ASSET = "help.md"

/**
 * Shows the bundled help document.
 *
 * The text lives in `assets/help.md` rather than in Kotlin so it can be edited
 * as prose and reviewed as a diff. It is read on a background thread because the
 * document is a few tens of kilobytes and parsing it on the main thread would
 * show up as a stutter when the screen opens.
 */
@Composable
fun HelpScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var blocks by remember { mutableStateOf<List<HelpBlock>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        try {
            val markdown = withContext(Dispatchers.IO) {
                context.assets.open(HELP_ASSET).bufferedReader().use { it.readText() }
            }
            blocks = withContext(Dispatchers.Default) { HelpDocument.parse(markdown) }
        } catch (e: Exception) {
            // A missing asset is a packaging mistake, not a user error, but the
            // screen must still say something instead of staying blank.
            error = "ヘルプを読み込めませんでした（${e.message ?: "原因不明"}）"
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        val loaded = blocks
        when {
            error != null -> Text(
                error!!,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier
                    .padding(20.dp)
                    .testTag(TestTags.HELP_ERROR),
            )

            loaded == null -> CircularProgressIndicator(
                Modifier
                    .align(Alignment.Center)
                    .testTag(TestTags.HELP_LOADING),
            )

            else -> Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 8.dp)
                    .testTag(TestTags.HELP_SCREEN),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                for (block in loaded) {
                    HelpBlockView(block)
                }
                // Breathing room so the last line is not glued to the edge.
                Box(Modifier.padding(bottom = 24.dp))
            }
        }
    }
}

@Composable
private fun HelpBlockView(block: HelpBlock) {
    when (block) {
        is HelpBlock.Heading -> Text(
            block.text,
            style = when (block.level) {
                1 -> MaterialTheme.typography.headlineSmall
                2 -> MaterialTheme.typography.titleLarge
                else -> MaterialTheme.typography.titleMedium
            },
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(top = if (block.level == 1) 0.dp else 8.dp),
        )

        is HelpBlock.Paragraph -> Text(
            inlineText(block.text),
            style = MaterialTheme.typography.bodyMedium,
        )

        is HelpBlock.Bullets -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            for (item in block.items) {
                Row {
                    Text("・", style = MaterialTheme.typography.bodyMedium)
                    Text(inlineText(item), style = MaterialTheme.typography.bodyMedium)
                }
            }
        }

        is HelpBlock.Numbers -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            for ((index, item) in block.items.withIndex()) {
                Row {
                    Text(
                        "${index + 1}. ",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(inlineText(item), style = MaterialTheme.typography.bodyMedium)
                }
            }
        }

        is HelpBlock.Table -> HelpTableView(block)
    }
}

/**
 * Renders a pipe table as rows of equal-width cells.
 *
 * Every table in the help document is a two-column "say this / get that" list, so
 * equal weights read well and avoid a layout pass to measure content.
 */
@Composable
private fun HelpTableView(table: HelpBlock.Table) {
    val columns = maxOf(table.headers.size, table.rows.maxOfOrNull { it.size } ?: 0)
    if (columns == 0) return

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                MaterialTheme.colorScheme.surfaceVariant,
                RoundedCornerShape(8.dp),
            )
            .padding(vertical = 4.dp),
    ) {
        TableRow(table.headers, columns, header = true)
        for (row in table.rows) {
            TableRow(row, columns, header = false)
        }
    }
}

@Composable
private fun TableRow(cells: List<String>, columns: Int, header: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.Top,
    ) {
        for (index in 0 until columns) {
            val cell = cells.getOrNull(index).orEmpty()
            Text(
                inlineText(cell),
                style = MaterialTheme.typography.bodySmall,
                fontWeight = if (header) FontWeight.SemiBold else FontWeight.Normal,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/** Applies the inline emphasis the parser found to a Compose string. */
@Composable
private fun inlineText(text: String): AnnotatedString {
    val spans = HelpDocument.inline(text)
    if (spans.size == 1 && !spans[0].bold && !spans[0].code) return AnnotatedString(text)
    return buildAnnotatedString {
        for (span in spans) appendSpan(span)
    }
}

private fun AnnotatedString.Builder.appendSpan(span: HelpSpan) {
    when {
        span.code -> withStyle(
            SpanStyle(
                fontFamily = FontFamily.Monospace,
                background = androidx.compose.ui.graphics.Color(0x22808080),
            ),
        ) { append(span.text) }

        span.bold -> withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) {
            append(span.text)
        }

        else -> append(span.text)
    }
}
