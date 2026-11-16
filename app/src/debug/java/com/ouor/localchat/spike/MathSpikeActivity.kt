package com.ouor.localchat.spike

import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hrm.latex.renderer.Latex
import com.hrm.latex.renderer.measure.rememberLatexMeasurer
import com.hrm.latex.renderer.model.LatexConfig
import com.hrm.latex.renderer.model.LatexTheme
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.model.markdownAnnotator
import com.mikepenz.markdown.model.markdownInlineContent
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.ast.getTextInNode

/**
 * Spike (a): inline/display math with latex-renderer inside the mikepenz Markdown renderer.
 * adb shell am start -n com.ouor.localchat/.spike.MathSpikeActivity [--ez dark true]
 */
class MathSpikeActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val dark = intent.getBooleanExtra("dark", false)
        setContent {
            MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
                Surface(Modifier.fillMaxSize()) { MathSpike(dark) }
            }
        }
    }
}

private val INLINE = Regex("""\$([^$\n]+?)\$|\\\((.+?)\\\)""")
private val DISPLAY = Regex("""\$\$([\s\S]+?)\$\$|\\\[([\s\S]+?)\\]""")
private val TOKEN = Regex("⦃m(\\d+)⦄")

/** Formulas typical of model answers (KaTeX syntax), plus one broken input. */
private val SAMPLES = listOf(
    "Probe cases A:\n\n$$\\begin{cases} 1 & \\text{if } x>0 \\\\ 0 & \\text{otherwise} \\end{cases}$$",
    "Probe cases B:\n\n$$\\begin{cases} a & b \\\\ c & d \\end{cases}$$",
    "Probe cases as array:\n\n\$\$f(x)=\\left\\{\\begin{array}{ll} x^2 & x\\ge 0 \\\\ -x & \\text{otherwise} \\end{array}\\right.$$",
    "Probe unicode minus: \$a\u2212b\$, \$x^{\u22121}\$, \$\\minus 1\$.",
    "Probe textstyle: \$\\textstyle\\sum_{i=1}^n i\$, \$\\tfrac{a}{b}\$, \$\\textstyle\\int_0^1 f\$ vs \$\\sum_{i=1}^n i\$.",
    "Probe minus: \$a-b\$, \$-1\$, \$x^{-1}\$, \$a - b\$, \$\\pm 1\$, \$a \\cdot b\$.",
    "Inline basics: \$E=mc^2\$, \$a_i^2\$, \$\\alpha+\\beta\\le\\gamma\$, \\(\\sqrt[3]{x}\\) and \$\\frac{a}{b}\$ in a sentence.",
    "Sum \$\\sum_{i=1}^{n} i = \\frac{n(n+1)}{2}\$ and integral \$\\int_0^\\infty e^{-x^2}dx\$ inline.",
    "Sets \$\\mathbb{R}, \\mathcal{O}(n\\log n), \\{x \\mid x>0\\}\$ and text \$\\text{if } x \\neq 0\$.",
    "Operators \$\\operatorname{softmax}(z)_i\$, \$\\lim_{x\\to0}\\frac{\\sin x}{x}=1\$, \$\\binom{n}{k}\$, \$\\vec{v}\\cdot\\hat{n}\$.",
    "Display:\n\n$$\\int_{-\\infty}^{\\infty} e^{-x^2}\\,dx = \\sqrt{\\pi}$$",
    "$$\\begin{pmatrix} a & b \\\\ c & d \\end{pmatrix}^{-1} = \\frac{1}{ad-bc}\\begin{pmatrix} d & -b \\\\ -c & a \\end{pmatrix}$$",
    "\$\$f(x)=\\begin{cases} x^2 & x\\ge 0 \\\\ -x & x<0 \\end{cases}$$",
    "$$\\begin{aligned} (a+b)^2 &= a^2+2ab+b^2 \\\\ &\\ge 4ab \\end{aligned}$$",
    "$$\\left( \\sum_{k=1}^n a_k b_k \\right)^2 \\le \\left( \\sum_{k=1}^n a_k^2 \\right)\\left( \\sum_{k=1}^n b_k^2 \\right)$$",
    "$$\\overbrace{1+2+\\cdots+n}^{n\\text{ terms}} \\quad \\boxed{x=42} \\quad \\nabla\\times\\mathbf{B}=\\mu_0\\mathbf{J}$$",
    "$$\\begin{array}{c|cc} & x & y \\\\ \\hline 1 & 2 & 3 \\end{array} \\qquad \\ce{2H2 + O2 -> 2H2O}$$",
    "Broken inline \$\\frac{a}{\$ and broken display:\n\n$$\\begin{matrix} a & b$$",
    "**Bold** text, `code`, a [link](https://example.com) and math \$x^2\$ in one paragraph.",
)

@Composable
private fun MathSpike(dark: Boolean) {
    val config = LatexConfig(fontSize = 16.sp, theme = if (dark) LatexTheme.dark() else LatexTheme.light())
    val measurer = rememberLatexMeasurer(config)
    // Timing: parse + measure of every inline formula once (cold), then again (cached).
    remember(config) {
        val all = SAMPLES.flatMap { s -> INLINE.findAll(s).map { it.groupValues[1].ifEmpty { it.groupValues[2] } } }
        val t0 = SystemClock.elapsedRealtimeNanos()
        all.forEach { measurer.inlineContent(it, config, dark) }
        val t1 = SystemClock.elapsedRealtimeNanos()
        all.forEach { measurer.inlineContent(it, config, dark) }
        val t2 = SystemClock.elapsedRealtimeNanos()
        Log.i("SPIKE_A", "inline n=${all.size} cold=${(t1 - t0) / 1_000_000}ms warm=${(t2 - t1) / 1_000}us")
        true
    }
    LazyColumn(Modifier.fillMaxSize().padding(16.dp)) {
        items(SAMPLES) { sample ->
            Column(Modifier.padding(vertical = 8.dp)) {
                MathMarkdown(sample, config, dark)
                HorizontalDivider(Modifier.padding(top = 8.dp))
            }
        }
    }
}

/** Display formulas become their own Latex(); inline ones become placeholder tokens in Markdown. */
@Composable
private fun MathMarkdown(text: String, config: LatexConfig, dark: Boolean) {
    var last = 0
    DISPLAY.findAll(text).forEach { m ->
        InlineMathMarkdown(text.substring(last, m.range.first), config, dark)
        Latex(m.groupValues[1].ifEmpty { m.groupValues[2] }.trim(), config = config)
        last = m.range.last + 1
    }
    InlineMathMarkdown(text.substring(last), config, dark)
}

@Composable
private fun InlineMathMarkdown(text: String, config: LatexConfig, dark: Boolean) {
    if (text.isBlank()) return
    val formulas = mutableListOf<String>()
    val tokenized = INLINE.replace(text) { m ->
        formulas += m.groupValues[1].ifEmpty { m.groupValues[2] }
        "⦃m${formulas.size - 1}⦄"
    }
    val measurer = rememberLatexMeasurer(config)
    val annotator = remember(tokenized) {
        markdownAnnotator { content, child ->
            if (child.type != MarkdownTokenTypes.TEXT) return@markdownAnnotator false
            val nodeText = child.getTextInNode(content).toString()
            if (!TOKEN.containsMatchIn(nodeText)) return@markdownAnnotator false
            var i = 0
            TOKEN.findAll(nodeText).forEach { t ->
                append(nodeText.substring(i, t.range.first))
                val n = t.groupValues[1].toInt()
                appendInlineContent("m$n", "\$${formulas[n]}\$") // alternate text = source (R-03 fallback)
                i = t.range.last + 1
            }
            append(nodeText.substring(i))
            true
        }
    }
    val inline = mutableMapOf<String, InlineTextContent>()
    formulas.forEachIndexed { n, tex ->
        // Documented to return null on failure (platform type in the Java signature).
        val content: InlineTextContent? = measurer.inlineContent(tex, config, dark)
        if (content != null) inline["m$n"] = content
    }
    if (inline.size < formulas.size) Log.w("SPIKE_A", "inline fallback: ${formulas.size - inline.size} of $formulas")
    Markdown(tokenized, annotator = annotator, inlineContent = markdownInlineContent(inline))
}
