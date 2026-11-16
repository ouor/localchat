package com.ouor.localchat.ui.markdown

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
    val grown = remember { mutableListOf<Int>() } // measured widths, never shrink (no jumps while streaming)
    val scroll = rememberScrollState()
    val shape = RoundedCornerShape(12.dp)
    val wrap = TextStyle(lineBreak = LineBreak.Simple, hyphens = Hyphens.None)
    val body = style.merge(wrap)
    val head = style.merge(LcTheme.text.bodyStrong).merge(wrap)

    BoxWithConstraints(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        val avail = constraints.maxWidth
        Box(
            Modifier
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
            Layout(content = {
                rows.forEachIndexed { r, cells ->
                    for (j in 0 until cols) {
                        val cell = cells.getOrNull(j)
                        Box(
                            Modifier
                                .then(if (r == 0) Modifier.background(c.surfaceSunken) else Modifier)
                                .drawBehind { if (r > 0) drawLine(c.border, Offset.Zero, Offset(size.width, 0f), 1.dp.toPx()) }
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                        ) {
                            if (cell != null) MarkdownTableBasicText(content, cell, if (r == 0) head else body, Int.MAX_VALUE, TextOverflow.Clip, settings)
                        }
                    }
                }
            }) { ms, _ ->
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
                val h = grid.map { row -> row.withIndex().maxOf { (j, m) -> m.minIntrinsicHeight(w[j]) } }
                val ps = grid.mapIndexed { i, row -> row.mapIndexed { j, m -> m.measure(Constraints.fixed(w[j], h[i])) } }
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
}
