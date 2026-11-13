package com.health.app.ui.food

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream

/** Getting a food photo ready to send: upright, 1024 px at most, and without its EXIF (location, camera). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class)
class FoodPhotoImageTest {
    /** A [w]×[h] JPEG, red on the left half and blue on the right, as a phone writes it: EXIF with [orientation], the camera's make and a GPS block. */
    private fun phoneJpeg(w: Int, h: Int, orientation: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).apply {
            drawRect(0f, 0f, w / 2f, h.toFloat(), Paint().apply { color = Color.RED })
            drawRect(w / 2f, 0f, w.toFloat(), h.toFloat(), Paint().apply { color = Color.BLUE })
        }
        val plain = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }.toByteArray()
        val tiff = ByteArrayOutputStream()
        fun u16(v: Int) { tiff.write(v shr 8); tiff.write(v and 0xFF) }
        fun u32(v: Int) { u16(v ushr 16); u16(v and 0xFFFF) }
        tiff.write("MM".toByteArray()); u16(42); u32(8)
        u16(3) // IFD0: the camera's make, the orientation, and where the GPS block is
        u16(0x010F); u16(2); u32(10); u32(50)
        u16(0x0112); u16(3); u32(1); u16(orientation); u16(0)
        u16(0x8825); u16(4); u32(1); u32(60)
        u32(0)
        tiff.write("PHONECORP\u0000".toByteArray())
        u16(1) // GPS IFD: a map datum naming the place
        u16(0x0012); u16(2); u32(12); u32(78)
        u32(0)
        tiff.write("SECRETPLACE\u0000".toByteArray())
        val exif = "Exif\u0000\u0000".toByteArray() + tiff.toByteArray()
        val app1 = byteArrayOf(0xFF.toByte(), 0xE1.toByte(), ((exif.size + 2) shr 8).toByte(), ((exif.size + 2) and 0xFF).toByte()) + exif
        return plain.copyOfRange(0, 2) + app1 + plain.copyOfRange(2, plain.size)
    }

    private fun ByteArray.has(text: String) = String(this, Charsets.ISO_8859_1).contains(text)

    private fun size(jpeg: ByteArray): Pair<Int, Int> {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, o)
        return o.outWidth to o.outHeight
    }

    @Test
    fun `a phone photo is turned upright, shrunk to 1024 and loses its EXIF`() {
        val original = phoneJpeg(3000, 2000, orientation = 6)
        assertTrue(original.has("Exif") && original.has("SECRETPLACE") && original.has("PHONECORP"))
        assertEquals(6, FoodPhotoImage.orientation(original))

        val sent = FoodPhotoImage.shrink(original)
        assertTrue(sent[0] == 0xFF.toByte() && sent[1] == 0xD8.toByte())
        assertFalse("no EXIF", sent.has("Exif"))
        assertFalse("no place", sent.has("SECRETPLACE"))
        assertFalse("no camera", sent.has("PHONECORP"))
        assertEquals(1, FoodPhotoImage.orientation(sent))
        val (w, h) = size(sent)
        assertEquals(1024, h)
        assertTrue("$w", w in 682..683)
        // Turned a quarter clockwise: the red left half is now on top.
        val back = BitmapFactory.decodeByteArray(sent, 0, sent.size)
        val top = back.getPixel(w / 2, 40)
        val bottom = back.getPixel(w / 2, h - 40)
        assertTrue(Integer.toHexString(top), Color.red(top) > 200 && Color.blue(top) < 60)
        assertTrue(Integer.toHexString(bottom), Color.blue(bottom) > 200 && Color.red(bottom) < 60)
        assertTrue(sent.size < original.size)
    }

    @Test
    fun `the camera's rotation is used when it's given, and small photos aren't made bigger`() {
        val camera = FoodPhotoImage.shrink(phoneJpeg(1600, 1200, orientation = 1), rotation = 90)
        assertEquals(768 to 1024, size(camera))
        assertFalse(camera.has("Exif"))
        val small = FoodPhotoImage.shrink(phoneJpeg(500, 400, orientation = 1))
        assertEquals(500 to 400, size(small))
        assertFalse(small.has("SECRETPLACE"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `something that isn't a picture is said plainly`() {
        FoodPhotoImage.shrink("not a picture".toByteArray())
    }
}
