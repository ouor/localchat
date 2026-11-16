package com.ouor.localchat.browser

import android.os.ParcelFileDescriptor
import android.print.PdfPrintBridge
import android.print.PrintAttributes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** B-08 print_to_pdf without the print UI (Phase 0 spike (c), android.print.PdfPrintBridge). Call after the page loaded. */
object PdfPrinter {

    private val A4 = PrintAttributes.Builder()
        .setMediaSize(PrintAttributes.MediaSize.ISO_A4)
        .setResolution(PrintAttributes.Resolution("pdf", "pdf", 600, 600))
        .setMinMargins(PrintAttributes.Margins.NO_MARGINS)
        .build()

    suspend fun print(tab: BrowserTab): ByteArray {
        val file = withContext(Dispatchers.IO) { File.createTempFile("print", ".pdf", tab.webView.context.applicationContext.cacheDir) }
        try {
            withContext(Dispatchers.Main.immediate) {
                val pfd = ParcelFileDescriptor.open(
                    file,
                    ParcelFileDescriptor.MODE_READ_WRITE or ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE,
                )
                try {
                    suspendCancellableCoroutine { cont ->
                        PdfPrintBridge.write(tab.webView.createPrintDocumentAdapter(tab.title.ifBlank { "page" }), A4, pfd) { ok, error ->
                            if (ok) cont.resume(Unit) else cont.resumeWithException(PageError("Printing to PDF failed: ${error ?: "unknown error"}"))
                        }
                    }
                } finally {
                    pfd.close()
                }
            }
            return withContext(Dispatchers.IO) { file.readBytes() }
        } finally {
            file.delete()
        }
    }
}
