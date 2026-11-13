package com.health.app.ui.food

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.min

/**
 * A food photo made ready to send, on the phone and in memory only: turned upright, at most
 * [SIDE] pixels on its long side, and written again as a plain JPEG. Writing it again leaves the
 * EXIF block behind, so no location and no camera details go with it. (The server does the same
 * again before passing it on.)
 */
object FoodPhotoImage {
    const val SIDE = 1024
    const val MAX_BYTES = 20 * 1024 * 1024

    /**
     * [bytes] (JPEG, PNG, WebP or HEIC) ready to send. [rotation] is the camera's, in degrees;
     * without it, the picture's own EXIF orientation is used.
     */
    fun shrink(bytes: ByteArray, rotation: Int? = null, side: Int = SIDE, quality: Int = 85): ByteArray {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "That picture couldn't be read" }
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= side) sample *= 2
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: throw IllegalArgumentException("That picture couldn't be read")
        val matrix = if (rotation != null) Matrix().apply { setRotate(rotation.toFloat()) } else upright(orientation(bytes))
        val scale = min(1f, side.toFloat() / max(decoded.width, decoded.height))
        matrix.postScale(scale, scale)
        val out = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
        val jpeg = ByteArrayOutputStream().also { out.compress(Bitmap.CompressFormat.JPEG, quality, it) }.toByteArray()
        if (out !== decoded) out.recycle()
        decoded.recycle()
        return jpeg
    }

    /** The EXIF orientation (1–8) of a JPEG, or 1: read from its EXIF block's first directory, and nothing else of it. */
    fun orientation(bytes: ByteArray): Int = runCatching { exifOrientation(bytes) }.getOrDefault(1)

    private fun exifOrientation(b: ByteArray): Int {
        fun u8(i: Int) = b[i].toInt() and 0xFF
        if (b.size < 4 || u8(0) != 0xFF || u8(1) != 0xD8) return 1
        var i = 2
        while (i + 4 <= b.size) {
            if (u8(i) != 0xFF) return 1
            val marker = u8(i + 1)
            if (marker == 0xD9 || marker == 0xDA) return 1
            val length = (u8(i + 2) shl 8) or u8(i + 3)
            if (length < 2) return 1
            if (marker == 0xE1 && i + 10 <= b.size && String(b, i + 4, 6, Charsets.ISO_8859_1) == "Exif\u0000\u0000") {
                return tiffOrientation(b, i + 10, minOf(b.size, i + 2 + length))
            }
            i += 2 + length
        }
        return 1
    }

    private fun tiffOrientation(b: ByteArray, tiff: Int, end: Int): Int {
        val little = b[tiff] == 'I'.code.toByte()
        fun u16(p: Int): Int {
            require(p + 2 <= end)
            val x = b[p].toInt() and 0xFF
            val y = b[p + 1].toInt() and 0xFF
            return if (little) x or (y shl 8) else (x shl 8) or y
        }
        fun u32(p: Int): Long = if (little) u16(p).toLong() or (u16(p + 2).toLong() shl 16) else (u16(p).toLong() shl 16) or u16(p + 2).toLong()
        val ifd = tiff + u32(tiff + 4).toInt()
        for (n in 0 until u16(ifd).coerceAtMost(200)) {
            val entry = ifd + 2 + n * 12
            if (u16(entry) == 0x0112) return u16(entry + 8).takeIf { it in 1..8 } ?: 1
        }
        return 1
    }

    /** What turns a picture with EXIF [orientation] the right way up (2–8 flip or turn it; 1 leaves it). */
    private fun upright(orientation: Int): Matrix = Matrix().apply {
        when (orientation) {
            2 -> setScale(-1f, 1f)
            3 -> setRotate(180f)
            4 -> { setRotate(180f); postScale(-1f, 1f) }
            5 -> { setRotate(90f); postScale(-1f, 1f) }
            6 -> setRotate(90f)
            7 -> { setRotate(-90f); postScale(-1f, 1f) }
            8 -> setRotate(-90f)
        }
    }
}
