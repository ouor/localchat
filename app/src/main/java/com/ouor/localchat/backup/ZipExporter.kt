package com.ouor.localchat.backup

import java.io.File
import java.io.OutputStream
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Streaming ZIP writer for backups (architecture 9.1): JSON entries DEFLATE, attachment files STORE
 * (usually compressed already). [onBytes] gets each chunk's size for progress. Entry order is the caller's.
 */
internal class ZipExporter(out: OutputStream, private val onBytes: (Long) -> Unit = {}) : AutoCloseable {
    private val zip = ZipOutputStream(out)

    fun json(path: String, text: String) {
        val bytes = text.encodeToByteArray()
        zip.putNextEntry(ZipEntry(path).apply { method = ZipEntry.DEFLATED; time = FIXED_TIME })
        zip.write(bytes)
        zip.closeEntry()
        onBytes(bytes.size.toLong())
    }

    /** STORE needs size and CRC before the data, so the file is read twice (local file, cheap). */
    fun file(path: String, file: File) {
        val crc = CRC32()
        file.inputStream().use { input -> forEachChunk(input) { b, n -> crc.update(b, 0, n) } }
        val entry = ZipEntry(path).apply {
            method = ZipEntry.STORED
            size = file.length()
            compressedSize = file.length()
            this.crc = crc.value
            time = FIXED_TIME
        }
        zip.putNextEntry(entry)
        file.inputStream().use { input ->
            forEachChunk(input) { b, n ->
                zip.write(b, 0, n)
                onBytes(n.toLong())
            }
        }
        zip.closeEntry()
    }

    override fun close() = zip.close()

    private inline fun forEachChunk(input: java.io.InputStream, block: (ByteArray, Int) -> Unit) {
        val buf = ByteArray(64 * 1024)
        while (true) {
            val n = input.read(buf)
            if (n < 0) return
            block(buf, n)
        }
    }

    private companion object {
        /** Stable bytes for the same data (golden/snapshot tests); manifest.createdAt has the real time. */
        const val FIXED_TIME = 315532800000L // 1980-01-01, the ZIP epoch
    }
}
