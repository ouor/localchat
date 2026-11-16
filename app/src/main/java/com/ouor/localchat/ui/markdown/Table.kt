package com.ouor.localchat.ui.markdown

import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.Hyphens
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import com.mikepenz.markdown.annotator.annotatorSettings
import com.mikepenz.markdown.compose.elements.MarkdownTableBasicText
import com.ouor.localchat.ui.theme.LcTheme
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.flavours.gfm.GFMElementTypes
import org.intellij.markdown.flavours.gfm.GFMTokenTypes

/**
 * FB-4 (screens-fb1): Markdown table whose cells wrap fully (mikepenz's default is one line + ellipsis).
 * Column width = clamp(widest cell, 64dp, 240dp), stretched to fill when narrower than the screen; only the
 * table scrolls sideways when wider, with a 24dp edge fade on the side that has more. Cells keep the
 * library text (annotator → citation badges, inline code, bold). Widths only grow while streaming.
 */
@Composable
internal fun WrapTable(content: String, node: ASTNode, style: TextStyle) {
    val c = LcTheme.colors
    val rows = node.children.filter { it.type == GFMElementTypes.HEADER || it.type == GFMElementTypes.ROW }
        .map { r -> r.children.filter { it.type == GFMTokenTypes.CELL } }
    val cols = rows.maxOfOrNull { it.size } ?: 0
    if (cols == 0) return
    val settings = annotatorSettings()
    // Measured widths only grow while streaming (no jumps). Keyed by where the table starts and its header text, so
    // another table taking this slot starts fresh; both stay the same while rows stream in.
    val headerText = node.children.firstOrNull { it.type == GFMElementTypes.HEADER }?.let { content.substring(it.startOffset, it.endOffset) }
    val grown = remember(node.startOffset, headerText) { mutableListOf<Int>() }
    val rowTops = remember { mutableListOf<Int>() } // last layout's row offsets, for the header fill and row lines
    val scroll = rememberScrollState()
    val shape = RoundedCornerShape(12.dp)
    val wrap = TextStyle(lineBreak = LineBreak.Simple, hyphens = Hyphens.None)
    val body = style.merge(wrap)
    val head = style.merge(LcTheme.text.bodyStrong).merge(wrap)

    // Plain layouts only (no BoxWithConstraints), so a parent may ask for intrinsic sizes: the available width
    // arrives as the min width (fillMaxWidth), which horizontalScroll keeps and the Box passes on.
    Box(
        propagateMinConstraints = true,
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawWithContent {
                drawContent()
                val f = 24.dp.toPx()
                if (scroll.canScrollForward) drawRect(Brush.horizontalGradient(listOf(Color.Black, Color.Transparent), size.width - f, size.width), blendMode = BlendMode.DstIn)
                if (scroll.canScrollBackward) drawRect(Brush.horizontalGradient(listOf(Color.Transparent, Color.Black), 0f, f), blendMode = BlendMode.DstIn)
            }
            .border(1.dp, c.border, shape)
            .clip(shape)
            .horizontalScroll(scroll),
    ) {
        Layout(modifier = Modifier.drawBehind {
            if (rowTops.isEmpty()) return@drawBehind
            drawRect(c.surfaceSunken, size = size.copy(height = rowTops.getOrNull(1)?.toFloat() ?: size.height))
            for (y in rowTops.drop(1)) drawLine(c.border, Offset(0f, y.toFloat()), Offset(size.width, y.toFloat()), 1.dp.toPx())
        }, content = {
            rows.forEachIndexed { r, cells ->
                for (j in 0 until cols) {
                    val cell = cells.getOrNull(j)
                    Box(Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
                        if (cell != null) MarkdownTableBasicText(content, cell, if (r == 0) head else body, Int.MAX_VALUE, TextOverflow.Clip, settings)
                    }
                }
            }
        }) { ms, cs ->
            val avail = cs.minWidth
            val grid = ms.chunked(cols)
            val minW = 64.dp.roundToPx()
            val maxW = 240.dp.roundToPx()
            val w = IntArray(cols) { j ->
                val m = grid.maxOf { it[j].maxIntrinsicWidth(Constraints.Infinity) }.coerceIn(minW, maxW)
                if (j < grown.size) maxOf(m, grown[j]).also { grown[j] = it } else m.also { grown += it }
            }
            val extra = avail - w.sum()
            if (extra > 0) {
                val total = w.sum()
                var left = extra
                for (j in 0 until cols) {
                    val add = if (j == cols - 1) left else extra * w[j] / total
                    w[j] += add; left -= add
                }
            }
            // One measure per cell at its column width; the row is as tall as its tallest cell (cells sit at the top).
            val ps = grid.map { row -> row.mapIndexed { j, m -> m.measure(Constraints(minWidth = w[j], maxWidth = w[j])) } }
            val h = ps.map { row -> row.maxOf { it.height } }
            rowTops.clear(); h.runningFold(0) { a, b -> a + b }.dropLast(1).forEach { rowTops += it }
            layout(w.sum(), h.sum()) {
                var y = 0
                ps.forEachIndexed { i, row ->
                    var x = 0
                    row.forEachIndexed { j, p -> p.place(x, y); x += w[j] }
                    y += h[i]
                }
            }
        }
    }
}
