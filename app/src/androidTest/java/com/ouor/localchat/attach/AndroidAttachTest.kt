package com.ouor.localchat.attach

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ouor.localchat.attach.extract.PdfText
import com.ouor.localchat.attach.extract.TextOut
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.StringWriter

/** The Android-only parts of attach: pdfbox needs its assets, ImageDecoder needs the platform. */
@RunWith(AndroidJUnit4::class)
class AndroidAttachTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun pdfText() {
        PDFBoxResourceLoader.init(context)
        val f = File(context.cacheDir, "t.pdf").apply { writeBytes(minimalPdf("Hello PDF")) }
        val w = StringWriter()
        PdfText.extract(f, TextOut(w))
        assertTrue(w.toString(), w.toString().contains("Hello PDF"))
    }

    @Test
    fun downscaleFlattensAndShrinks() {
        val src = File(context.cacheDir, "big.png")
        Bitmap.createBitmap(4096, 1024, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.TRANSPARENT) }
            .also { b -> src.outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) } }
        val dest = File(context.cacheDir, "big.req.jpg")
        assertEquals(4096 to 1024, ImageDownscaler.downscale(src, dest))
        val out = BitmapFactory.decodeFile(dest.path)
        assertEquals(2048 to 512, out.width to out.height)
        assertEquals(Color.WHITE, out.getPixel(10, 10))
    }

    /** Smallest valid PDF with one Helvetica text line; xref offsets computed. */
    private fun minimalPdf(text: String): ByteArray {
        val stream = "BT /F1 24 Tf 72 700 Td ($text) Tj ET"
        val objs = listOf(
            "<< /Type /Catalog /Pages 2 0 R >>",
            "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
            "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Contents 4 0 R /Resources << /Font << /F1 5 0 R >> >> >>",
            "<< /Length ${stream.length} >>\nstream\n$stream\nendstream",
            "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>",
        )
        val sb = StringBuilder("%PDF-1.4\n")
        val offsets = objs.mapIndexed { i, o -> sb.length.also { sb.append("${i + 1} 0 obj\n$o\nendobj\n") } }
        val xref = sb.length
        sb.append("xref\n0 ${objs.size + 1}\n0000000000 65535 f \n")
        offsets.forEach { sb.append("%010d 00000 n \n".format(it)) }
        sb.append("trailer\n<< /Size ${objs.size + 1} /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n")
        return sb.toString().encodeToByteArray()
    }
}
