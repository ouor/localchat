package com.ouor.localchat.ui.markdown

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.hrm.latex.renderer.measure.LatexMeasurerState
import com.hrm.latex.renderer.measure.rememberLatexMeasurer
import com.hrm.latex.renderer.model.LatexConfig
import com.hrm.latex.renderer.model.LatexTheme
import com.mikepenz.markdown.compose.components.markdownComponents
import com.mikepenz.markdown.compose.elements.MarkdownCodeBlock
import com.mikepenz.markdown.compose.elements.MarkdownCodeFence
import com.mikepenz.markdown.compose.elements.MarkdownListItems
import com.mikepenz.markdown.compose.elements.MarkdownOrderedList
import com.mikepenz.markdown.compose.elements.listDepth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownColor
import com.mikepenz.markdown.m3.markdownTypography
import com.mikepenz.markdown.model.markdownAnnotator
import com.mikepenz.markdown.model.markdownDimens
import com.mikepenz.markdown.model.markdownInlineContent
import com.mikepenz.markdown.model.markdownPadding
import com.ouor.localchat.core.model.Source
import com.ouor.localchat.ui.components.CitationBadge
import com.ouor.localchat.ui.theme.LcMotion
import com.ouor.localchat.ui.theme.LcTheme
import kotlinx.coroutines.delay
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.ast.getTextInNode

/** Hidden render WebView for Mermaid; provided once at the app root. Null = diagrams show their source. */
val LocalJsRenderEngine = staticCompositionLocalOf<JsRenderEngine?> { null }

/**
 * Assistant message body (R-01~R-05). [text] may grow while [streaming]: updates are throttled to 100ms,
 * closed blocks keep their parse, only the last block re-parses. `[n]` becomes a [CitationBadge] when
 * [sources] has n. Links go to [onLinkClick] (in-app browser, user tab).
 */
@Composable
fun MarkdownMessage(
    text: String,
    sources: List<Source>,
    streaming: Boolean,
    onLinkClick: (String) -> Unit,
    onCitationClick: (Source) -> Unit,
    modifier: Modifier = Modifier,
    activeCitation: Int? = null,
) {
    val shown = rememberThrottled(text, streaming)
    val segments = remember(shown) { splitSegments(shown) }
    val byNumber = remember(sources) { sources.associateBy { it.n } }
    val dark = LcTheme.colors.isDark
    val latexConfig = remember(dark) { LatexConfig(fontSize = 16.sp, theme = if (dark) LatexTheme.dark() else LatexTheme.light()) }
    val measurer = rememberLatexMeasurer(latexConfig)
    val linkClick by rememberUpdatedState(onLinkClick)
    val uriHandler = remember { object : UriHandler { override fun openUri(uri: String) = linkClick(uri) } }

    CompositionLocalProvider(LocalUriHandler provides uriHandler, LocalMdStreaming provides streaming) {
        Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            segments.forEachIndexed { i, seg ->
                key(i) {
                    when (seg) {
                        is MdSegment.Markdown -> MarkdownBlock(seg.text, byNumber, activeCitation, onCitationClick, measurer, latexConfig, dark)
                        is MdSegment.Math -> MathBlock(seg.tex, latexConfig, measurer, dark)
                    }
                }
            }
        }
    }
}

internal val LocalMdStreaming = staticCompositionLocalOf { false }

/** R-05: while streaming, pass at most one new value per 100ms; the final text is applied immediately. */
@Composable
private fun rememberThrottled(text: String, streaming: Boolean): String {
    var shown by remember { mutableStateOf(text) }
    val latest by rememberUpdatedState(text)
    LaunchedEffect(streaming) {
        if (!streaming) { shown = latest; return@LaunchedEffect }
        while (true) {
            if (shown != latest) shown = latest
            delay(LcMotion.STREAM_THROTTLE_MS)
        }
    }
    return if (streaming) shown else text
}

@Composable
private fun MarkdownBlock(
    markdown: String,
    sources: Map<Int, Source>,
    activeCitation: Int?,
    onCitationClick: (Source) -> Unit,
    measurer: LatexMeasurerState,
    config: LatexConfig,
    dark: Boolean,
) {
    val c = LcTheme.colors
    val t = LcTheme.text
    val parts = remember(markdown, sources.keys) { tokenizeInline(markdown, sources.keys) }
    // R-03: decide per formula whether it renders; failures fall back to code-styled source.
    val mathOk = remember(parts, config) {
        parts.math.map { tex -> MathRules.isWellFormed(tex) && runCatching { (measurer.measure(MathRules.inline(tex), config, dark)?.widthPx ?: 0f) > 0f }.getOrDefault(false) }
    }
    val codeStyle = SpanStyle(fontFamily = t.code.fontFamily, fontSize = 14.sp, background = c.surfaceSunken)
    val annotator = remember(parts, mathOk, codeStyle) {
        markdownAnnotator { content, child ->
            if (child.type != MarkdownTokenTypes.TEXT) return@markdownAnnotator false
            val node = child.getTextInNode(content).toString()
            if (!node.contains(TOKEN_OPEN)) return@markdownAnnotator false
            var i = 0
            INLINE_TOKEN.findAll(node).forEach { m ->
                append(node.substring(i, m.range.first))
                val n = m.groupValues[2].toInt()
                when {
                    m.groupValues[1] == "c" -> appendInlineContent("c$n", "[$n]")
                    mathOk.getOrElse(n) { false } -> appendInlineContent("m$n", "$" + parts.math[n] + "$")
                    else -> withStyle(codeStyle) { append("$" + parts.math[n] + "$") }
                }
                i = m.range.last + 1
            }
            append(node.substring(i))
            true
        }
    }
    val inline = buildMap<String, InlineTextContent> {
        parts.math.forEachIndexed { n, tex -> if (mathOk[n]) measurer.inlineContent(MathRules.inline(tex), config, dark)?.let { put("m$n", it) } }
        INLINE_TOKEN.findAll(parts.text).filter { it.groupValues[1] == "c" }.forEach { m ->
            val n = m.groupValues[2].toInt()
            val src = sources[n] ?: return@forEach
            put("c$n", InlineTextContent(Placeholder((0.55 * "$n".length + 0.75).em, 1.2.em, PlaceholderVerticalAlign.TextCenter)) {
                CitationBadge(n, { onCitationClick(src) }, active = n == activeCitation)
            })
        }
    }
    // BUG-07: keep the last parse on screen while the final text re-parses (no empty frame).
    val mdState = com.mikepenz.markdown.model.rememberMarkdownState(parts.text, retainState = true)
    Markdown(
        markdownState = mdState,
        modifier = Modifier.fillMaxWidth(),
        colors = markdownColor(text = c.textPrimary, codeBackground = c.codeBg, inlineCodeBackground = c.surfaceSunken, dividerColor = c.border, tableBackground = c.surfaceRaised),
        typography = markdownTypography(
            h1 = t.mdH1, h2 = t.mdH2, h3 = t.mdH3, h4 = t.titleSmall, h5 = t.bodyStrong, h6 = t.bodyStrong,
            text = t.bodyLarge, paragraph = t.bodyLarge, ordered = t.bodyLarge, bullet = t.bodyLarge, list = t.bodyLarge,
            code = t.code, inlineCode = t.code.copy(fontSize = 14.sp, background = c.surfaceSunken),
            quote = t.bodyLarge.copy(color = c.textSecondary), table = t.body,
            textLink = TextLinkStyles(SpanStyle(color = c.accentText, textDecoration = TextDecoration.Underline)),
        ),
        padding = markdownPadding(block = 4.dp, listIndent = 6.dp),
        dimens = markdownDimens(codeBackgroundCornerSize = 12.dp, tableCornerSize = 12.dp, tableCellWidth = 140.dp),
        annotator = annotator,
        inlineContent = markdownInlineContent(inline),
        components = remember {
            markdownComponents(
                codeFence = { MarkdownCodeFence(it.content, it.node, it.typography.code) { code, lang, _ -> CodeOrDiagram(code, lang) } },
                codeBlock = { MarkdownCodeBlock(it.content, it.node, it.typography.code) { code, lang, _ -> CodeBlock(code, lang) } },
                // Mockup: body-size dot / number on the first line's baseline (library default floats it to the top).
                unorderedList = {
                    MarkdownListItems(it.content, it.node, depth = it.listDepth, markerModifier = { Modifier.alignByBaseline() }, listModifier = { Modifier.alignByBaseline() }) { _, _, _ ->
                        Text("•", style = it.typography.bullet.copy(fontSize = it.typography.bullet.fontSize * 1.25f), color = c.textPrimary, modifier = Modifier.padding(start = 2.dp, end = 8.dp))
                    }
                },
                orderedList = {
                    MarkdownOrderedList(it.content, it.node, it.typography.ordered, it.listDepth, markerModifier = { Modifier.alignByBaseline() }, listModifier = { Modifier.alignByBaseline() })
                },
            )
        },
    )
}

@Composable
private fun CodeOrDiagram(code: String, language: String?) {
    if (language.equals("mermaid", ignoreCase = true)) MermaidBlock(code, LocalJsRenderEngine.current, renderNow = !LocalMdStreaming.current)
    else CodeBlock(code, language)
}
