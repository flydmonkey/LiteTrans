package com.videoconverter.android.document

import android.graphics.Bitmap
import com.videoconverter.android.domain.documentExtension
import java.io.OutputStream

fun imageCompressFormat(
    preset: String,
    sourceExt: String,
    quality: String = "high",
): Pair<String, Int> {
    val src = sourceExt.lowercase().substringAfterLast('.')
    val ext = when (preset) {
        "image-compress" -> if (src == "png" || src == "bmp") src else "jpg"
        else -> documentExtension(preset, src.ifEmpty { null })
    }
    val jpegQuality = when {
        preset == "image-compress" && (ext == "jpg" || ext == "jpeg") -> when (quality) {
            "high" -> 92
            "small" -> 60
            else -> 75
        }
        ext == "webp" -> 80
        ext == "jpg" || ext == "jpeg" -> 75
        else -> 100
    }
    return ext to jpegQuality
}

fun scaleForQuality(quality: String, preset: String): Float =
    if (preset == "image-compress" && quality == "small") 0.7f else 1f

fun encodeBitmap(bitmap: Bitmap, ext: String, quality: Int, out: OutputStream) {
    when (ext.lowercase()) {
        "jpg", "jpeg" -> bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
        "png" -> bitmap.compress(Bitmap.CompressFormat.PNG, quality, out)
        "webp" -> bitmap.compress(Bitmap.CompressFormat.WEBP, quality, out)
        "bmp" -> BmpEncoder.write(bitmap, out)
        "gif" -> GifEncoder.write(bitmap, out)
        else -> error("unsupported image extension: $ext")
    }
}
