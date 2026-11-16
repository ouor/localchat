package com.ouor.localchat.attach

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import java.io.File

/**
 * Request copy of an image: long side at most [MAX_SIDE], JPEG (architecture 3.3). Always
 * re-encoded, which also drops EXIF (GPS) before anything is sent. ImageDecoder applies EXIF rotation.
 */
object ImageDownscaler {
    const val MAX_SIDE = 2048
    private const val QUALITY = 90

    /** Writes [dest] and returns the original pixel size. Throws on undecodable input. */
    fun downscale(source: File, dest: File): Pair<Int, Int> {
        var original = 0 to 0
        val bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(source)) { decoder, info, _ ->
            original = info.size.width to info.size.height
            val (w, h) = fit(info.size.width, info.size.height, MAX_SIDE)
            decoder.setTargetSize(w, h)
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
        // JPEG has no alpha: flatten transparent PNG/WebP onto white instead of black.
        val opaque = if (bitmap.hasAlpha()) {
            Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888).also {
                Canvas(it).apply { drawColor(Color.WHITE); drawBitmap(bitmap, 0f, 0f, null) }
                bitmap.recycle()
            }
        } else {
            bitmap
        }
        dest.outputStream().use { opaque.compress(Bitmap.CompressFormat.JPEG, QUALITY, it) }
        opaque.recycle()
        return original
    }

    /** Size that fits in [max]x[max] keeping the aspect ratio; never upscales. */
    fun fit(width: Int, height: Int, max: Int): Pair<Int, Int> {
        val long = maxOf(width, height)
        if (long <= max) return width to height
        val scale = max.toDouble() / long
        return maxOf(1, Math.round(width * scale).toInt()) to maxOf(1, Math.round(height * scale).toInt())
    }
}
