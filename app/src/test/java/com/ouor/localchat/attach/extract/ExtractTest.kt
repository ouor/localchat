package com.ouor.localchat.attach.extract

import com.ouor.localchat.attach.PrepFailure
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.io.StringWriter
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ExtractTest {

    private fun temp(ext: String, bytes: ByteArray) = File.createTempFile("extract", ".$ext").apply { deleteOnExit(); writeBytes(bytes) }

    private fun zip(ext: String, vararg parts: Pair<String, String>): File {
        val f = File.createTempFile("extract", ".$ext").apply { deleteOnExit() }
        ZipOutputStream(f.outputStream()).use { z -> parts.forEach { (n, t) -> z.putNextEntry(ZipEntry(n)); z.write(t.encodeToByteArray()); z.closeEntry() } }
        return f
    }

    private fun run(block: (TextOut) -> Unit): Pair<String, TextOut> {
        val w = StringWriter()
        val out = TextOut(w)
        block(out)
        return w.toString() to out
    }

    @Test
    fun `plain text drops the BOM and refuses binary`() {
        val (text, out) = run { PlainText.extract(temp("txt", byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "héllo\n".encodeToByteArray()), it) }
        assertEquals("héllo\n", text)
        assertEquals(5, out.visible)
        expect(PrepFailure.UNSUPPORTED_CONTENT) { PlainText.extract(temp("h5", byteArrayOf(1, 0, 2)), TextOut(StringWriter())) }
    }

    @Test
    fun `text is capped`() {
        val w = StringWriter()
        PlainText.extract(temp("txt", "abcdefgh".encodeToByteArray()), TextOut(w, maxChars = 4))
        assertEquals("abcd", w.toString())
    }

    @Test
    fun docx() {
        val f = zip(
            "docx",
            "word/document.xml" to """<?xml version="1.0"?><w:document xmlns:w="x"><w:body>
                <w:p><w:r><w:t>Hello</w:t></w:r><w:r><w:tab/><w:t xml:space="preserve"> world</w:t></w:r></w:p>
                <w:p><w:r><w:t>Line &amp; two</w:t><w:br/><w:t>three</w:t></w:r></w:p></w:body></w:document>""",
        )
        assertEquals("Hello\t world\nLine & two\nthree\n", run { OfficeText.extract(f, "docx", it) }.first)
    }

    @Test
    fun xlsx() {
        val f = zip(
            "xlsx",
            "xl/sharedStrings.xml" to """<sst><si><t>Name</t></si><si><r><t>Ki</t></r><r><t>m</t></r></si></sst>""",
            "xl/worksheets/sheet2.xml" to """<worksheet><sheetData><row><c><v>2</v></c></row></sheetData></worksheet>""",
            "xl/worksheets/sheet1.xml" to """<worksheet><sheetData>
                <row><c t="s"><v>0</v></c><c><v>42</v></c></row>
                <row><c t="s"><v>1</v></c><c t="inlineStr"><is><t>inline</t></is></c><c><f>A1</f><v>7</v></c></row></sheetData></worksheet>""",
        )
        assertEquals("--- sheet 1 ---\nName\t42\nKim\tinline\t7\n\n--- sheet 2 ---\n2\n", run { OfficeText.extract(f, "xlsx", it) }.first)
    }

    @Test
    fun pptx() {
        val f = zip(
            "pptx",
            "ppt/slides/slide10.xml" to """<p:sld xmlns:a="a" xmlns:p="p"><a:p><a:r><a:t>Ten</a:t></a:r></a:p></p:sld>""",
            "ppt/slides/slide2.xml" to """<p:sld xmlns:a="a" xmlns:p="p"><a:p><a:r><a:t>Two</a:t></a:r></a:p><a:p><a:r><a:t>B</a:t></a:r></a:p></p:sld>""",
        )
        assertEquals("--- slide 1 ---\nTwo\nB\n\n--- slide 2 ---\nTen\n", run { OfficeText.extract(f, "pptx", it) }.first)
    }

    @Test
    fun `legacy or broken office files fail`() {
        expect(PrepFailure.EXTRACT_FAILED) { OfficeText.extract(temp("docx", "not a zip".encodeToByteArray()), "docx", TextOut(StringWriter())) }
        expect(PrepFailure.EXTRACT_FAILED) { OfficeText.extract(zip("docx", "other.xml" to "<a/>"), "docx", TextOut(StringWriter())) }
        expect(PrepFailure.EXTRACT_FAILED) { OfficeText.extract(zip("docx", "word/document.xml" to "<w:p>"), "docx", TextOut(StringWriter())) }
    }

    @Test
    fun `doctype is refused (no XXE)`() {
        val evil = """<?xml version="1.0"?><!DOCTYPE d [<!ENTITY x SYSTEM "file:///etc/passwd">]><w:document><w:p><w:t>&x;</w:t></w:p></w:document>"""
        expect(PrepFailure.EXTRACT_FAILED) { OfficeText.extract(zip("docx", "word/document.xml" to evil), "docx", TextOut(StringWriter())) }
    }

    private fun expect(code: String, block: () -> Unit) {
        try {
            block()
            fail("expected $code")
        } catch (e: ExtractException) {
            assertEquals(code, e.code)
        }
    }
}
