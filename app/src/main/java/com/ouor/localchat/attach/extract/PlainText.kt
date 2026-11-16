package com.ouor.localchat.attach.extract

import com.ouor.localchat.attach.PrepFailure
import java.io.BufferedInputStream
import java.io.File

/** Text, code, CSV, JSON, HTML ... decoded as UTF-8 (BOM dropped). Binary content is refused. */
object PlainText {
    private const val SNIFF = 8192

    fun extract(file: File, out: TextOut) {
        BufferedInputStream(file.inputStream()).use { input ->
            input.mark(SNIFF)
            val head = ByteArray(SNIFF).let { b -> b.copyOf(readFully(input, b)) }
            // A NUL byte never appears in text; h5, grib, epub ... are allowed by the list but not readable.
            if (head.any { it == 0.toByte() }) throw ExtractException(PrepFailure.UNSUPPORTED_CONTENT)
            input.reset()
            if (head.size >= 3 && head[0] == 0xEF.toByte() && head[1] == 0xBB.toByte() && head[2] == 0xBF.toByte()) {
                readFully(input, ByteArray(3))
            }
            input.reader(Charsets.UTF_8).copyTo(out)
        }
    }

    /** Fills [b] as far as the stream allows (readNBytes needs API 33). */
    private fun readFully(input: java.io.InputStream, b: ByteArray): Int {
        var n = 0
        while (n < b.size) {
            val r = input.read(b, n, b.size - n)
            if (r < 0) break
            n += r
        }
        return n
    }
}
