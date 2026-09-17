package com.videoconverter.android.document

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class ImageConvertTest {
    @Test
    fun compressKeepsJpegQualitySteps() {
        assertEquals("jpg" to 92, imageCompressFormat("image-compress", "jpg"))
        assertEquals("jpg" to 75, imageCompressFormat("image-jpg", "png"))
        assertEquals("webp" to 80, imageCompressFormat("image-webp", "png"))
        assertEquals("png" to 100, imageCompressFormat("image-png", "jpg"))
        assertEquals(0.7f, scaleForQuality("small", "image-compress"), 0.001f)
        assertEquals(1f, scaleForQuality("small", "image-jpg"), 0.001f)
        assertEquals("png" to 100, imageCompressFormat("image-compress", "png"))
        assertEquals("bmp" to 100, imageCompressFormat("image-compress", "bmp"))
        assertEquals("jpg" to 92, imageCompressFormat("image-compress", "webp", "high"))
        assertEquals("jpg" to 75, imageCompressFormat("image-compress", "jpg", "standard"))
        assertEquals("jpg" to 60, imageCompressFormat("image-compress", "jpeg", "small"))
        assertEquals(1f, scaleForQuality("high", "image-compress"), 0.001f)
        assertEquals(1f, scaleForQuality("standard", "image-compress"), 0.001f)
    }

    @Test
    fun bmpAndGifEncodersWriteSignatures() {
        val bmp = android.graphics.Bitmap.createBitmap(2, 2, android.graphics.Bitmap.Config.ARGB_8888)
        val gifOut = ByteArrayOutputStream()
        encodeBitmap(bmp, "gif", 80, gifOut)
        assertEquals('G'.code.toByte(), gifOut.toByteArray()[0])
        assertEquals('I'.code.toByte(), gifOut.toByteArray()[1])
        val bmpOut = ByteArrayOutputStream()
        encodeBitmap(bmp, "bmp", 100, bmpOut)
        val bytes = bmpOut.toByteArray()
        assertEquals('B'.code.toByte(), bytes[0])
        assertEquals('M'.code.toByte(), bytes[1])
        assertTrue(bytes.size > 14)
    }
}
