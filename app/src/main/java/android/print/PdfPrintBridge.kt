package android.print

import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor

/**
 * Drives a PrintDocumentAdapter without the print UI. The result callbacks' constructors
 * are package-private in the SDK stubs (public + @hide at runtime), so this file must live in
 * android.print (architecture 7.4, R5). Verified on API 36 in Phase 0 spike (c); used by browser.PdfPrinter.
 */
object PdfPrintBridge {
    fun write(
        adapter: PrintDocumentAdapter,
        attributes: PrintAttributes,
        out: ParcelFileDescriptor,
        done: (ok: Boolean, error: String?) -> Unit,
    ) {
        adapter.onStart()
        adapter.onLayout(null, attributes, null, object : PrintDocumentAdapter.LayoutResultCallback() {
            override fun onLayoutFinished(info: PrintDocumentInfo, changed: Boolean) {
                adapter.onWrite(arrayOf(PageRange.ALL_PAGES), out, CancellationSignal(),
                    object : PrintDocumentAdapter.WriteResultCallback() {
                        override fun onWriteFinished(pages: Array<out PageRange>) {
                            adapter.onFinish()
                            done(true, null)
                        }

                        override fun onWriteFailed(error: CharSequence?) {
                            adapter.onFinish()
                            done(false, error?.toString())
                        }
                    })
            }

            override fun onLayoutFailed(error: CharSequence?) {
                adapter.onFinish()
                done(false, error?.toString())
            }
        }, Bundle())
    }
}
