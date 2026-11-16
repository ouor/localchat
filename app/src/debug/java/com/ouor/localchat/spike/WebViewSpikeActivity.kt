package com.ouor.localchat.spike

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.print.PdfPrintBridge
import android.print.PrintAttributes
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.view.drawToBitmap
import org.json.JSONObject
import org.json.JSONTokener
import java.io.File

/**
 * Spikes (b) and (c). The WebView sits at full size in an always-attached container at the Activity
 * root; [mode] decides how it is hidden. Every second the page's timer/rAF counters are logged,
 * a native tap is injected at 3 s, drawToBitmap at 5 s and print-to-PDF at 6 s.
 *
 * adb shell am start -n com.ouor.localchat/.spike.WebViewSpikeActivity --es mode offscreen [--ez preraster true]
 * modes: onscreen | covered (opaque view on top) | offscreen (translated out) | alpha0
 */
class WebViewSpikeActivity : Activity() {

    private val main = Handler(Looper.getMainLooper())
    private lateinit var web: WebView
    private val start = SystemClock.elapsedRealtime()
    private var lastTicks = 0

    private fun log(msg: String) = Log.i("SPIKE_B", "[${SystemClock.elapsedRealtime() - start}ms] $msg")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val mode = intent.getStringExtra("mode") ?: "onscreen"
        val preRaster = intent.getBooleanExtra("preraster", false)

        val root = FrameLayout(this)
        val parking = FrameLayout(this)
        web = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.offscreenPreRaster = preRaster
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String) {
                    log("pageFinished mode=$mode preRaster=$preRaster")
                    schedule()
                }
            }
        }
        parking.addView(web, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        root.addView(parking, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        val cover = TextView(this).apply {
            setBackgroundColor(Color.WHITE)
            text = "spike b: mode=$mode"
        }
        when (mode) {
            "covered" -> root.addView(cover, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            "offscreen" -> parking.post { parking.translationX = root.width.toFloat() }
            "alpha0" -> parking.alpha = 0f
        }
        setContentView(root)
        web.loadDataWithBaseURL("https://spike.local/", PAGE, "text/html", "utf-8", null)
    }

    private fun schedule() {
        for (s in 1..12) main.postDelayed({ readCounters(s) }, s * 1000L)
        main.postDelayed(::nativeTap, 3000)
        main.postDelayed(::snapshot, 5000)
        main.postDelayed(::printPdf, 6000)
    }

    private fun readCounters(second: Int) {
        web.evaluateJavascript("JSON.stringify(window.spike)") { raw ->
            // evaluateJavascript returns the JSON encoding of the returned string.
            val o = JSONObject(JSONTokener(raw).nextValue() as String)
            val ticks = o.getInt("ticks")
            log("t=${second}s ticks=$ticks (+${ticks - lastTicks}/s) raf=${o.getInt("raf")} clicks=${o.getInt("clicks")} trusted=${o.getInt("trusted")} vis=${o.getString("vis")} attached=${web.isAttachedToWindow} shown=${web.isShown}")
            lastTicks = ticks
        }
    }

    /** Native tap on #btn: CSS px -> view px with view.width / innerWidth (architecture 7.4). */
    private fun nativeTap() {
        web.evaluateJavascript("(function(){var r=document.getElementById('btn').getBoundingClientRect();return [r.left+r.width/2,r.top+r.height/2,innerWidth];})()") { raw ->
            val (cx, cy, iw) = raw.trim('[', ']').split(',').map { it.toFloat() }
            val scale = web.width / iw
            val x = cx * scale
            val y = cy * scale
            val t = SystemClock.uptimeMillis()
            val down = MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, x, y, 0)
            val up = MotionEvent.obtain(t, t + 50, MotionEvent.ACTION_UP, x, y, 0)
            val a = web.dispatchTouchEvent(down)
            val b = web.dispatchTouchEvent(up)
            down.recycle()
            up.recycle()
            log("nativeTap css=($cx,$cy) innerWidth=$iw scale=$scale view=($x,$y) handled=$a/$b")
        }
    }

    private fun snapshot() {
        val bmp = runCatching { web.drawToBitmap() }.getOrElse { log("drawToBitmap failed: $it"); return }
        val distinct = (0 until 50).map { bmp.getPixel(bmp.width * it / 50, bmp.height / 8) }.toSet().size
        File(filesDir, "spike_b_shot.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        log("drawToBitmap ${bmp.width}x${bmp.height} distinctColorsInRow=$distinct")
    }

    private fun printPdf() {
        val file = File(filesDir, "spike_c.pdf")
        val pfd = ParcelFileDescriptor.open(
            file,
            ParcelFileDescriptor.MODE_READ_WRITE or ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE,
        )
        val attrs = PrintAttributes.Builder()
            .setMediaSize(PrintAttributes.MediaSize.ISO_A4)
            .setResolution(PrintAttributes.Resolution("pdf", "pdf", 600, 600))
            .setMinMargins(PrintAttributes.Margins.NO_MARGINS)
            .build()
        val t0 = SystemClock.elapsedRealtime()
        PdfPrintBridge.write(web.createPrintDocumentAdapter("spike"), attrs, pfd) { ok, error ->
            pfd.close()
            if (!ok) { log("pdf failed: $error"); return@write }
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { ro ->
                PdfRenderer(ro).use { pdf ->
                    pdf.openPage(0).use { page ->
                        val bmp = Bitmap.createBitmap(page.width * 2, page.height * 2, Bitmap.Config.ARGB_8888)
                        bmp.eraseColor(Color.WHITE)
                        page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        File(filesDir, "spike_c_page1.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    }
                    log("pdf ok bytes=${file.length()} pages=${pdf.pageCount} in ${SystemClock.elapsedRealtime() - t0}ms")
                }
            }
        }
    }

    override fun onStop() {
        super.onStop()
        log("onStop (no pauseTimers/onPause called)")
    }

    private companion object {
        val PAGE = """
            <!doctype html><html><head><meta name="viewport" content="width=device-width, initial-scale=1">
            <style>body{font:16px sans-serif;margin:16px} #btn{margin-top:40px;width:200px;height:60px}</style></head>
            <body><h1>Spike B</h1><p id="out">-</p><button id="btn">Tap me</button>
            ${(1..80).joinToString("") { "<p>Paragraph $it: lorem ipsum dolor sit amet, consectetur adipiscing elit.</p>" }}
            <script>
              window.spike = {ticks:0, raf:0, clicks:0, trusted:0, vis:document.visibilityState};
              setInterval(function(){ spike.ticks++; spike.vis = document.visibilityState; }, 100);
              (function f(){ spike.raf++; requestAnimationFrame(f); })();
              document.getElementById('btn').addEventListener('click', function(e){
                spike.clicks++; if (e.isTrusted) spike.trusted++;
                document.getElementById('out').textContent = 'clicked trusted=' + e.isTrusted;
              });
            </script></body></html>
        """.trimIndent()
    }
}
