package com.videoconverter.android.document

import android.graphics.Bitmap
import java.io.ByteArrayOutputStream
import java.io.OutputStream

object GifEncoder {
    fun write(bitmap: Bitmap, out: OutputStream) {
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        val (palette, indices) = quantize(pixels)

        out.write("GIF89a".toByteArray(Charsets.US_ASCII))
        writeShortLe(out, width)
        writeShortLe(out, height)
        out.write(0xF7)
        out.write(0)
        out.write(0)
        for (i in 0 until 256) {
            val color = if (i < palette.size) palette[i] else 0
            out.write(color shr 16 and 0xFF)
            out.write(color shr 8 and 0xFF)
            out.write(color and 0xFF)
        }

        out.write(0x2C)
        writeShortLe(out, 0)
        writeShortLe(out, 0)
        writeShortLe(out, width)
        writeShortLe(out, height)
        out.write(0)

        val minCodeSize = 8
        out.write(minCodeSize)
        val compressed = lzwCompress(indices, minCodeSize)
        var offset = 0
        while (offset < compressed.size) {
            val length = minOf(255, compressed.size - offset)
            out.write(length)
            out.write(compressed, offset, length)
            offset += length
        }
        out.write(0)
        out.write(0x3B)
        out.flush()
    }
}

private fun quantize(pixels: IntArray): Pair<IntArray, ByteArray> {
    val unique = LinkedHashMap<Int, Int>()
    for (pixel in pixels) {
        val rgb = pixel and 0xFFFFFF
        if (rgb !in unique && unique.size < 256) {
            unique[rgb] = unique.size
        }
    }
    val palette = IntArray(unique.size)
    unique.forEach { (rgb, index) -> palette[index] = rgb }
    val indices = ByteArray(pixels.size)
    for (i in pixels.indices) {
        val rgb = pixels[i] and 0xFFFFFF
        indices[i] = (unique[rgb] ?: nearestIndex(palette, rgb)).toByte()
    }
    return palette to indices
}

private fun nearestIndex(palette: IntArray, rgb: Int): Int {
    val red = rgb shr 16
    val green = rgb shr 8 and 0xFF
    val blue = rgb and 0xFF
    var best = 0
    var bestDistance = Int.MAX_VALUE
    for (i in palette.indices) {
        val color = palette[i]
        val dr = red - (color shr 16)
        val dg = green - (color shr 8 and 0xFF)
        val db = blue - (color and 0xFF)
        val distance = dr * dr + dg * dg + db * db
        if (distance < bestDistance) {
            bestDistance = distance
            best = i
        }
    }
    return best
}

private fun lzwCompress(indices: ByteArray, minCodeSize: Int): ByteArray {
    val clearCode = 1 shl minCodeSize
    val eoiCode = clearCode + 1
    val maxCode = 4095
    val packed = ByteArrayOutputStream()
    var bitBuffer = 0
    var bitCount = 0

    fun writeCode(code: Int, codeSize: Int) {
        bitBuffer = bitBuffer or (code shl bitCount)
        bitCount += codeSize
        while (bitCount >= 8) {
            packed.write(bitBuffer and 0xFF)
            bitBuffer = bitBuffer ushr 8
            bitCount -= 8
        }
    }

    val table = HashMap<Long, Int>()
    var codeSize = minCodeSize + 1
    var nextCode = eoiCode + 1

    fun resetTable() {
        table.clear()
        codeSize = minCodeSize + 1
        nextCode = eoiCode + 1
    }

    writeCode(clearCode, codeSize)
    if (indices.isEmpty()) {
        writeCode(eoiCode, codeSize)
        if (bitCount > 0) packed.write(bitBuffer and 0xFF)
        return packed.toByteArray()
    }

    var prefix = indices[0].toInt() and 0xFF
    for (i in 1 until indices.size) {
        val suffix = indices[i].toInt() and 0xFF
        val key = prefix.toLong() shl 8 or suffix.toLong()
        val existing = table[key]
        if (existing != null) {
            prefix = existing
            continue
        }
        writeCode(prefix, codeSize)
        if (nextCode <= maxCode) {
            table[key] = nextCode
            if (nextCode == 1 shl codeSize && codeSize < 12) {
                codeSize++
            }
            nextCode++
        } else {
            writeCode(clearCode, codeSize)
            resetTable()
        }
        prefix = suffix
    }
    writeCode(prefix, codeSize)
    writeCode(eoiCode, codeSize)
    if (bitCount > 0) packed.write(bitBuffer and 0xFF)
    return packed.toByteArray()
}

private fun writeShortLe(out: OutputStream, value: Int) {
    out.write(value and 0xFF)
    out.write(value shr 8 and 0xFF)
}
