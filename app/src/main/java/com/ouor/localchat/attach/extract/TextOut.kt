package com.ouor.localchat.attach.extract

import java.io.Writer

/**
 * Extraction output with a size cap, so a zip bomb or a huge log cannot fill the disk. Past the
 * cap the rest is dropped. Counts visible characters to tell "no text" apart.
 */
class TextOut(private val out: Writer, private val maxChars: Int = MAX_CHARS) : Writer() {
    var written = 0
        private set
    var visible = 0
        private set

    override fun write(cbuf: CharArray, off: Int, len: Int) {
        val n = minOf(len, maxChars - written)
        if (n <= 0) return
        out.write(cbuf, off, n)
        written += n
        for (i in off until off + n) if (!cbuf[i].isWhitespace()) visible++
    }

    override fun flush() = out.flush()

    override fun close() = out.close()

    companion object {
        /** About 1M tokens; far beyond any context window, so nothing useful is lost. */
        const val MAX_CHARS = 4_000_000
    }
}

/** Extraction failed for a reason the user can act on; [code] is a PrepFailure code. */
class ExtractException(val code: String, cause: Throwable? = null) : Exception(code, cause)
