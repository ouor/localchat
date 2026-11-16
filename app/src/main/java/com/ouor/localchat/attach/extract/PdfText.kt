package com.ouor.localchat.attach.extract

import com.ouor.localchat.attach.PrepFailure
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.File
import java.io.IOException

/** PDF text on the device (I-04). Callers run PDFBoxResourceLoader.init once before the first call. */
object PdfText {

    fun extract(file: File, out: TextOut) {
        val doc = try {
            PDDocument.load(file)
        } catch (e: InvalidPasswordException) {
            throw ExtractException(PrepFailure.ENCRYPTED, e)
        } catch (e: IOException) {
            throw ExtractException(PrepFailure.EXTRACT_FAILED, e)
        }
        doc.use {
            if (it.isEncrypted && !it.currentAccessPermission.canExtractContent()) throw ExtractException(PrepFailure.ENCRYPTED)
            PDFTextStripper().writeText(it, out)
        }
    }
}
