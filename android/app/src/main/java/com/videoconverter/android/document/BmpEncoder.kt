package com.videoconverter.android.document

import android.graphics.Bitmap
import java.io.OutputStream

object BmpEncoder {
    fun write(bitmap: Bitmap, out: OutputStream) {
        val width = bitmap.width
        val height = bitmap.height
        val rowStride = (width * 3 + 3) and 3.inv()
        val pixelBytes = rowStride * height
        val fileSize = 14 + 40 + pixelBytes

        val header = ByteArray(54)
        header[0] = 'B'.code.toByte()
        header[1] = 'M'.code.toByte()
        writeIntLe(header, 2, fileSize)
        writeIntLe(header, 10, 54)
        writeIntLe(header, 14, 40)
        writeIntLe(header, 18, width)
        writeIntLe(header, 22, height)
        writeShortLe(header, 26, 1)
        writeShortLe(header, 28, 24)
        writeIntLe(header, 34, pixelBytes)
        out.write(header)

        val row = ByteArray(rowStride)
        val pixels = IntArray(width)
        for (y in height - 1 downTo 0) {
            bitmap.getPixels(pixels, 0, width, 0, y, width, 1)
            var offset = 0
            for (color in pixels) {
                row[offset++] = (color and 0xFF).toByte()
                row[offset++] = (color shr 8 and 0xFF).toByte()
                row[offset++] = (color shr 16 and 0xFF).toByte()
            }
            out.write(row)
            row.fill(0)
        }
        out.flush()
    }
}

private fun writeIntLe(dest: ByteArray, offset: Int, value: Int) {
    dest[offset] = (value and 0xFF).toByte()
    dest[offset + 1] = (value shr 8 and 0xFF).toByte()
    dest[offset + 2] = (value shr 16 and 0xFF).toByte()
    dest[offset + 3] = (value shr 24 and 0xFF).toByte()
}

private fun writeShortLe(dest: ByteArray, offset: Int, value: Int) {
    dest[offset] = (value and 0xFF).toByte()
    dest[offset + 1] = (value shr 8 and 0xFF).toByte()
}
