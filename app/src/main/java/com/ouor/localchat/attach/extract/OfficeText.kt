package com.ouor.localchat.attach.extract

import com.ouor.localchat.attach.PrepFailure
import org.xml.sax.Attributes
import org.xml.sax.helpers.DefaultHandler
import java.io.File
import java.io.InputStream
import java.util.zip.ZipException
import java.util.zip.ZipFile
import javax.xml.parsers.SAXParserFactory

/**
 * docx / xlsx / pptx text from their XML parts (I-04). SAX instead of XmlPullParser: it is in both
 * Android and the JVM, so the same code runs in unit tests. Legacy doc/xls/ppt are not zips and fail.
 */
object OfficeText {

    fun extract(file: File, ext: String, out: TextOut) {
        val zip = try {
            ZipFile(file)
        } catch (e: ZipException) {
            throw ExtractException(PrepFailure.EXTRACT_FAILED, e)
        }
        zip.use {
            when (ext) {
                "docx" -> docx(it, out)
                "xlsx" -> xlsx(it, out)
                "pptx" -> pptx(it, out)
                else -> error("not an OOXML extension: $ext")
            }
        }
    }

    private fun docx(zip: ZipFile, out: TextOut) {
        val part = zip.getEntry("word/document.xml") ?: throw ExtractException(PrepFailure.EXTRACT_FAILED)
        zip.getInputStream(part).use { parse(it, Paragraphs(out, text = "w:t", paragraph = "w:p", tab = "w:tab", br = "w:br")) }
    }

    private fun pptx(zip: ZipFile, out: TextOut) {
        val slides = numbered(zip, Regex("""ppt/slides/slide(\d+)\.xml"""))
        if (slides.isEmpty()) throw ExtractException(PrepFailure.EXTRACT_FAILED)
        slides.forEachIndexed { i, name ->
            out.write("${if (i > 0) "\n" else ""}--- slide ${i + 1} ---\n")
            zip.getInputStream(zip.getEntry(name)).use { parse(it, Paragraphs(out, text = "a:t", paragraph = "a:p", tab = null, br = "a:br")) }
        }
    }

    private fun xlsx(zip: ZipFile, out: TextOut) {
        val shared = zip.getEntry("xl/sharedStrings.xml")?.let { e -> zip.getInputStream(e).use { SharedStrings().also { h -> parse(it, h) }.items } }
            .orEmpty()
        val sheets = numbered(zip, Regex("""xl/worksheets/sheet(\d+)\.xml"""))
        if (sheets.isEmpty()) throw ExtractException(PrepFailure.EXTRACT_FAILED)
        sheets.forEachIndexed { i, name ->
            out.write("${if (i > 0) "\n" else ""}--- sheet ${i + 1} ---\n")
            zip.getInputStream(zip.getEntry(name)).use { parse(it, Cells(out, shared)) }
        }
    }

    /** Entry names matching [pattern] ordered by their number (slide10 after slide9). */
    private fun numbered(zip: ZipFile, pattern: Regex): List<String> =
        zip.entries().asSequence().mapNotNull { e -> pattern.matchEntire(e.name)?.let { e.name to it.groupValues[1].toInt() } }
            .sortedBy { it.second }.map { it.first }.toList()

    private fun parse(input: InputStream, handler: DefaultHandler) {
        val factory = SAXParserFactory.newInstance()
        // Keep the qualified names (w:t, a:p) and never resolve external entities (XXE).
        factory.isNamespaceAware = false
        runCatching { factory.setFeature("http://xml.org/sax/features/external-general-entities", false) }
        runCatching { factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
        try {
            factory.newSAXParser().parse(input, handler)
        } catch (e: org.xml.sax.SAXException) {
            throw ExtractException(PrepFailure.EXTRACT_FAILED, e)
        }
    }

    /** Text runs inside paragraphs; one line per paragraph. */
    private class Paragraphs(
        private val out: TextOut,
        private val text: String,
        private val paragraph: String,
        private val tab: String?,
        private val br: String,
    ) : DefaultHandler() {
        private var inText = false

        override fun startElement(uri: String?, localName: String?, qName: String, attributes: Attributes?) {
            when (qName) {
                text -> inText = true
                tab -> out.write("\t")
                br -> out.write("\n")
            }
        }

        override fun endElement(uri: String?, localName: String?, qName: String) {
            when (qName) {
                text -> inText = false
                paragraph -> out.write("\n")
            }
        }

        override fun characters(ch: CharArray, start: Int, length: Int) {
            if (inText) out.write(ch, start, length)
        }
    }

    private class SharedStrings : DefaultHandler() {
        val items = mutableListOf<String>()
        private val current = StringBuilder()
        private var inText = false

        override fun startElement(uri: String?, localName: String?, qName: String, attributes: Attributes?) {
            if (qName == "si") current.setLength(0)
            if (qName == "t") inText = true
        }

        override fun endElement(uri: String?, localName: String?, qName: String) {
            if (qName == "t") inText = false
            if (qName == "si") items += current.toString()
        }

        override fun characters(ch: CharArray, start: Int, length: Int) {
            if (inText) current.appendRange(ch, start, start + length)
        }
    }

    /** One line per row, cells separated by tabs. Shared strings are looked up by index. */
    private class Cells(private val out: TextOut, private val shared: List<String>) : DefaultHandler() {
        private var type: String? = null
        private val value = StringBuilder()
        private var inValue = false
        private var firstCell = true

        override fun startElement(uri: String?, localName: String?, qName: String, attributes: Attributes) {
            when (qName) {
                "row" -> firstCell = true
                "c" -> {
                    type = attributes.getValue("t")
                    value.setLength(0)
                }
                "v", "t" -> inValue = true
            }
        }

        override fun endElement(uri: String?, localName: String?, qName: String) {
            when (qName) {
                "v", "t" -> inValue = false
                "c" -> {
                    val raw = value.toString()
                    val cell = if (type == "s") raw.toIntOrNull()?.let(shared::getOrNull).orEmpty() else raw
                    if (!firstCell) out.write("\t")
                    out.write(cell)
                    firstCell = false
                }
                "row" -> out.write("\n")
            }
        }

        override fun characters(ch: CharArray, start: Int, length: Int) {
            if (inValue) value.appendRange(ch, start, start + length)
        }
    }
}
