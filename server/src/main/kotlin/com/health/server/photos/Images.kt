package com.health.server.photos

import com.health.server.ApiError
import java.awt.Color
import java.awt.RenderingHints
import java.awt.geom.AffineTransform
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Turns an uploaded picture into what's kept: JPEG, PNG or WebP in (checked by its first bytes,
 * not what the upload says it is), turned the right way up from its EXIF orientation, at most
 * [MAX_SIDE] pixels on its long side, and written out again as a plain JPEG: no EXIF, no location,
 * no camera details. Plus a small thumbnail.
 */
object Images {
    const val MAX_BYTES = 20 * 1024 * 1024
    const val MAX_SIDE = 3000
    const val THUMB_SIDE = 400
    private const val MAX_PIXELS = 60_000_000L

    class Processed(val jpeg: ByteArray, val thumb: ByteArray, val width: Int, val height: Int)

    enum class Format { JPEG, PNG, WEBP }

    fun sniff(bytes: ByteArray): Format? = when {
        bytes.size >= 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() && bytes[2] == 0xFF.toByte() -> Format.JPEG
        bytes.size >= 8 && bytes.copyOf(8).contentEquals(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)) -> Format.PNG
        bytes.size >= 12 && String(bytes, 0, 4, Charsets.ISO_8859_1) == "RIFF" && String(bytes, 8, 4, Charsets.ISO_8859_1) == "WEBP" -> Format.WEBP
        else -> null
    }

    fun process(bytes: ByteArray): Processed {
        if (bytes.size > MAX_BYTES) throw ApiError(io.ktor.http.HttpStatusCode.PayloadTooLarge, "Photos can be up to 20 MB")
        val format = sniff(bytes) ?: throw ApiError.badRequest("Photos can be JPEG, PNG or WebP")
        val decoded = decode(bytes, MAX_SIDE)
        val upright = if (format == Format.JPEG) orient(decoded, exifOrientation(bytes)) else flatten(decoded)
        val kept = scaled(upright, MAX_SIDE)
        return Processed(jpeg(kept, 0.9f), jpeg(scaled(kept, THUMB_SIDE), 0.82f), kept.width, kept.height)
    }

    /**
     * A picture as it's sent on (to the AI assistant, for a food photo): upright, at most [side]
     * pixels on its long side, written out again as a plain JPEG without EXIF, location or camera
     * details, whatever the device sent. In memory only.
     */
    fun shrunk(bytes: ByteArray, side: Int, quality: Float = 0.85f): ByteArray {
        if (bytes.size > MAX_BYTES) throw ApiError(io.ktor.http.HttpStatusCode.PayloadTooLarge, "Photos can be up to 20 MB")
        val format = sniff(bytes) ?: throw ApiError.badRequest("Photos can be JPEG, PNG or WebP")
        val decoded = decode(bytes, side)
        val upright = if (format == Format.JPEG) orient(decoded, exifOrientation(bytes)) else flatten(decoded)
        return jpeg(scaled(upright, side), quality)
    }

    /** Pictures decoded at once: each can take a lot of memory for a moment. */
    private val decoding = java.util.concurrent.Semaphore(2)

    /**
     * Decodes, refusing pictures too big to be a photo (a decompression bomb) before reading their
     * pixels, and reading a big one at a fraction of its size straight away (every 2nd, 3rd…
     * pixel), so it's never held at full size when only [side] pixels are wanted.
     */
    private fun decode(bytes: ByteArray, side: Int): BufferedImage {
        decoding.acquire()
        try {
            return decodeNow(bytes, side)
        } finally {
            decoding.release()
        }
    }

    private fun decodeNow(bytes: ByteArray, side: Int): BufferedImage {
        val input = ImageIO.createImageInputStream(ByteArrayInputStream(bytes)) ?: throw ApiError.badRequest("That picture couldn't be read")
        input.use { stream ->
            val reader = ImageIO.getImageReaders(stream).asSequence().firstOrNull() ?: throw ApiError.badRequest("That picture couldn't be read")
            try {
                reader.input = stream
                val w = reader.getWidth(0).toLong()
                val h = reader.getHeight(0).toLong()
                if (w <= 0 || h <= 0 || w * h > MAX_PIXELS) throw ApiError.badRequest("That picture is too big (60 megapixels at most)")
                val step = (maxOf(w, h) / side.coerceAtLeast(1)).toInt().coerceAtLeast(1)
                val param = reader.defaultReadParam.apply { if (step > 1) setSourceSubsampling(step, step, 0, 0) }
                return reader.read(0, param) ?: throw ApiError.badRequest("That picture couldn't be read")
            } catch (e: ApiError) {
                throw e
            } catch (e: Exception) {
                throw ApiError.badRequest("That picture couldn't be read")
            } finally {
                reader.dispose()
            }
        }
    }

    /** The EXIF orientation (1–8) of a JPEG, or 1. */
    fun exifOrientation(bytes: ByteArray): Int {
        fun u8(i: Int) = bytes[i].toInt() and 0xFF
        var i = 2
        while (i + 4 <= bytes.size) {
            if (u8(i) != 0xFF) return 1
            val marker = u8(i + 1)
            if (marker == 0xD9 || marker == 0xDA) return 1
            val length = (u8(i + 2) shl 8) or u8(i + 3)
            if (length < 2) return 1
            if (marker == 0xE1 && i + 10 <= bytes.size && String(bytes, i + 4, 6, Charsets.ISO_8859_1) == "Exif\u0000\u0000") {
                return runCatching { tiffOrientation(bytes, i + 10, minOf(bytes.size, i + 2 + length)) }.getOrDefault(1)
            }
            i += 2 + length
        }
        return 1
    }

    private fun tiffOrientation(b: ByteArray, tiff: Int, end: Int): Int {
        val little = b[tiff] == 'I'.code.toByte()
        fun u16(p: Int): Int {
            require(p + 2 <= end)
            val a = b[p].toInt() and 0xFF
            val c = b[p + 1].toInt() and 0xFF
            return if (little) a or (c shl 8) else (a shl 8) or c
        }
        fun u32(p: Int): Long {
            require(p + 4 <= end)
            return if (little) (u16(p).toLong() or (u16(p + 2).toLong() shl 16)) else ((u16(p).toLong() shl 16) or u16(p + 2).toLong())
        }
        val ifd = tiff + u32(tiff + 4).toInt()
        val count = u16(ifd)
        for (n in 0 until count.coerceAtMost(200)) {
            val entry = ifd + 2 + n * 12
            if (u16(entry) == 0x0112) return u16(entry + 8).takeIf { it in 1..8 } ?: 1
        }
        return 1
    }

    /** Turns a picture the right way up, onto white (JPEG has no transparency). */
    private fun orient(src: BufferedImage, orientation: Int): BufferedImage {
        val w = src.width.toDouble()
        val h = src.height.toDouble()
        val swap = orientation in 5..8
        val transform = when (orientation) {
            2 -> AffineTransform(-1.0, 0.0, 0.0, 1.0, w, 0.0)
            3 -> AffineTransform(-1.0, 0.0, 0.0, -1.0, w, h)
            4 -> AffineTransform(1.0, 0.0, 0.0, -1.0, 0.0, h)
            5 -> AffineTransform(0.0, 1.0, 1.0, 0.0, 0.0, 0.0)
            6 -> AffineTransform(0.0, 1.0, -1.0, 0.0, h, 0.0)
            7 -> AffineTransform(0.0, -1.0, -1.0, 0.0, h, w)
            8 -> AffineTransform(0.0, -1.0, 1.0, 0.0, 0.0, w)
            else -> AffineTransform()
        }
        val out = BufferedImage(if (swap) src.height else src.width, if (swap) src.width else src.height, BufferedImage.TYPE_INT_RGB)
        val g = out.createGraphics()
        try {
            g.color = Color.WHITE
            g.fillRect(0, 0, out.width, out.height)
            g.drawImage(src, transform, null)
        } finally {
            g.dispose()
        }
        return out
    }

    private fun flatten(src: BufferedImage): BufferedImage = orient(src, 1)

    /** Scales down (never up) to [side] on the long side, halving first for a smooth result. */
    private fun scaled(src: BufferedImage, side: Int): BufferedImage {
        var img = src
        val longest = max(img.width, img.height)
        if (longest <= side) return img
        val targetW = (img.width * side.toDouble() / longest).roundToInt().coerceAtLeast(1)
        val targetH = (img.height * side.toDouble() / longest).roundToInt().coerceAtLeast(1)
        while (img.width / 2 >= targetW && img.height / 2 >= targetH) img = resize(img, img.width / 2, img.height / 2)
        return if (img.width == targetW && img.height == targetH) img else resize(img, targetW, targetH)
    }

    private fun resize(src: BufferedImage, w: Int, h: Int): BufferedImage {
        val out = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        val g = out.createGraphics()
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
            g.drawImage(src, 0, 0, w, h, null)
        } finally {
            g.dispose()
        }
        return out
    }

    /** A plain JPEG: the writer adds no metadata of its own beyond the basic header. */
    private fun jpeg(img: BufferedImage, quality: Float): ByteArray {
        val writer = ImageIO.getImageWritersByFormatName("jpeg").next()
        val out = ByteArrayOutputStream()
        try {
            ImageIO.createImageOutputStream(out).use { stream ->
                writer.output = stream
                val param = writer.defaultWriteParam.apply {
                    compressionMode = ImageWriteParam.MODE_EXPLICIT
                    compressionQuality = quality
                }
                writer.write(null, IIOImage(img, null, null), param)
            }
        } finally {
            writer.dispose()
        }
        return out.toByteArray()
    }
}
