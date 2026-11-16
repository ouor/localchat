package com.ouor.localchat.ui.markdown

import android.graphics.Picture
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.caverock.androidsvg.SVG
import com.hrm.latex.renderer.Latex
import com.hrm.latex.renderer.measure.LatexMeasurerState
import com.hrm.latex.renderer.model.LatexConfig
import com.ouor.localchat.R
import com.ouor.localchat.ui.components.ButtonSize
import com.ouor.localchat.ui.components.ButtonVariant
import com.ouor.localchat.ui.components.IconButtonSize
import com.ouor.localchat.ui.components.IconButtonVariant
import com.ouor.localchat.ui.components.LcButton
import com.ouor.localchat.ui.components.LcIconButton
import com.ouor.localchat.ui.components.LcSpinner
import com.ouor.localchat.ui.theme.LcColors
import com.ouor.localchat.ui.theme.LcRadius
import com.ouor.localchat.ui.theme.LcTheme
import dev.snipme.highlights.Highlights
import dev.snipme.highlights.model.BoldHighlight
import dev.snipme.highlights.model.ColorHighlight
import dev.snipme.highlights.model.SyntaxLanguage
import dev.snipme.highlights.model.SyntaxTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ---------------- CodeBlock (.code) ----------------

/** R-02: header (language · copy), horizontally scrolling body, syntax colors from the design tokens. */
@Composable
fun CodeBlock(code: String, language: String?, modifier: Modifier = Modifier) {
    val c = LcTheme.colors
    val highlighted by produceState(AnnotatedString(code), code, language, c.isDark) {
        value = withContext(Dispatchers.Default) { highlight(code, language, c) }
    }
    CodeFrame(language?.takeIf { it.isNotBlank() } ?: stringResource(R.string.ui_code), code, modifier) {
        Text(
            highlighted, style = LcTheme.text.code, color = c.textPrimary,
            modifier = Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 10.dp),
        )
    }
}

@Composable
private fun CodeFrame(label: String, copyText: String, modifier: Modifier, headerExtra: @Composable () -> Unit = {}, body: @Composable () -> Unit) {
    val c = LcTheme.colors
    Column(modifier.fillMaxWidth().clip(LcRadius.md).background(c.codeBg).border(1.dp, c.border, LcRadius.md)) {
        Row(
            Modifier.fillMaxWidth().height(36.dp).background(c.codeHeader).padding(start = 12.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(label, style = LcTheme.text.caption, color = c.textSecondary)
            Box(Modifier.weight(1f))
            headerExtra()
            CopyButton(copyText)
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(c.border))
        body()
    }
}

@Composable
private fun CopyButton(text: String) {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) { if (copied) { delay(1500); copied = false } }
    LcIconButton(
        if (copied) Icons.Rounded.Check else Icons.Outlined.ContentCopy,
        stringResource(if (copied) R.string.ui_copied else R.string.ui_copy_code),
        { scope.launch { clipboard.setClipEntry(ClipEntry(android.content.ClipData.newPlainText("code", text))); copied = true } },
        variant = IconButtonVariant.Secondary, size = IconButtonSize.Xs,
    )
}

private fun highlight(code: String, language: String?, c: LcColors): AnnotatedString {
    val theme = SyntaxTheme(
        key = if (c.isDark) "lc-dark" else "lc-light",
        code = c.textPrimary.toArgb(), keyword = c.synKeyword.toArgb(), string = c.synString.toArgb(),
        literal = c.synFn.toArgb(), comment = c.synComment.toArgb(), metadata = c.synFn.toArgb(),
        multilineComment = c.synComment.toArgb(), punctuation = c.textSecondary.toArgb(), mark = c.synKeyword.toArgb(),
    )
    val lang = language?.let { SyntaxLanguage.getByName(it) }
    val spans = runCatching {
        Highlights.Builder().theme(theme).code(code).let { if (lang != null) it.language(lang) else it }.build().getHighlights()
    }.getOrDefault(emptyList())
    return buildAnnotatedString {
        append(code)
        spans.forEach {
            val style = when (it) {
                is ColorHighlight -> SpanStyle(color = androidx.compose.ui.graphics.Color(it.rgb).copy(alpha = 1f))
                is BoldHighlight -> SpanStyle(fontWeight = FontWeight.Bold)
            }
            if (it.location.end <= code.length) addStyle(style, it.location.start, it.location.end)
        }
    }
}

// ---------------- MathBlock ----------------

/** Display formula (R-03). Malformed or zero-width → source shown as code. Wide formulas scroll. */
@Composable
fun MathBlock(tex: String, config: LatexConfig, measurer: LatexMeasurerState, dark: Boolean, modifier: Modifier = Modifier) {
    val c = LcTheme.colors
    val shown = remember(tex) { MathRules.display(tex) }
    val ok = remember(shown, config) {
        MathRules.isWellFormed(tex) && runCatching { (measurer.measure(shown, config, dark)?.widthPx ?: 0f) > 0f }.getOrDefault(false)
    }
    if (ok) {
        Box(modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 4.dp)) {
            Latex(shown, config = config)
        }
    } else {
        Text(
            "$$\n$tex\n$$", style = LcTheme.text.code, color = c.textSecondary,
            modifier = modifier.fillMaxWidth().clip(LcRadius.md).background(c.surfaceSunken).horizontalScroll(rememberScrollState()).padding(12.dp),
        )
    }
}

// ---------------- MermaidBlock ----------------

private sealed interface DiagramState {
    data object Rendering : DiagramState
    data class Ready(val picture: Picture, val width: Float, val height: Float) : DiagramState
    data class Failed(val message: String) : DiagramState
}

/**
 * R-04: header [다이어그램 | 코드]; renders through [engine] (hidden WebView, Mermaid → SVG → AndroidSVG).
 * Not rendered while the message streams ([renderNow] false) or when there is no engine: code is shown.
 * Tap the diagram → [onExpand] (zoom viewer, owned by the screen).
 */
@Composable
fun MermaidBlock(source: String, engine: JsRenderEngine?, modifier: Modifier = Modifier, renderNow: Boolean = true, onExpand: (() -> Unit)? = null) {
    val c = LcTheme.colors
    var showCode by remember { mutableStateOf(false) }
    val empty = source.isBlank()
    val state by produceState<DiagramState>(DiagramState.Rendering, source, c.isDark, engine, renderNow) {
        if (engine == null || !renderNow || empty) return@produceState
        value = DiagramState.Rendering
        value = engine.mermaidToSvg(source, c.isDark).fold(
            onSuccess = { svg ->
                withContext(Dispatchers.Default) {
                    runCatching {
                        val doc = SVG.getFromString(svg)
                        val vb = doc.documentViewBox
                        val w = vb?.width() ?: doc.documentWidth
                        val h = vb?.height() ?: doc.documentHeight
                        DiagramState.Ready(doc.renderToPicture(w.toInt().coerceAtLeast(1), h.toInt().coerceAtLeast(1)), w, h)
                    }.getOrElse { DiagramState.Failed(it.message ?: "svg") }
                }
            },
            onFailure = { DiagramState.Failed(it.message ?: "render") },
        )
    }
    val codeMode = showCode || engine == null || !renderNow || empty || state is DiagramState.Failed
    CodeFrame(
        stringResource(R.string.ui_diagram), source, modifier,
        headerExtra = {
            if (engine != null && renderNow && !empty) {
                LcButton(
                    stringResource(if (codeMode) R.string.ui_show_diagram else R.string.ui_show_code), { showCode = !codeMode },
                    variant = ButtonVariant.Text, size = ButtonSize.Sm, enabled = state !is DiagramState.Failed,
                )
            }
        },
    ) {
        val s = state
        when {
            empty -> Text(stringResource(R.string.ui_diagram_empty), style = LcTheme.text.caption, color = c.textTertiary, modifier = Modifier.padding(12.dp))
            codeMode -> Column {
                if (s is DiagramState.Failed && engine != null && renderNow) {
                    Text(stringResource(R.string.ui_diagram_failed), style = LcTheme.text.caption, color = c.dangerText, modifier = Modifier.padding(start = 12.dp, top = 10.dp))
                }
                Text(source, style = LcTheme.text.code, color = c.textPrimary, modifier = Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 10.dp))
            }
            s is DiagramState.Rendering -> Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LcSpinner(size = 14.dp)
                Text(stringResource(R.string.ui_diagram_rendering), style = LcTheme.text.caption, color = c.textTertiary)
            }
            s is DiagramState.Ready -> {
                val density = LocalDensity.current
                val desc = stringResource(R.string.ui_diagram)
                Box(
                    Modifier.padding(12.dp).widthIn(max = with(density) { (s.width * density.density).toDp() })
                        .fillMaxWidth().aspectRatio(s.width / s.height)
                        .then(if (onExpand != null) Modifier.clickable(onClick = onExpand) else Modifier)
                        .semantics { contentDescription = desc }
                        .drawBehind {
                            drawIntoCanvas { canvas ->
                                val scale = size.width / s.width
                                canvas.nativeCanvas.save()
                                canvas.nativeCanvas.scale(scale, scale)
                                canvas.nativeCanvas.drawPicture(s.picture)
                                canvas.nativeCanvas.restore()
                            }
                        },
                )
            }
        }
    }
}
