package com.videoconverter.android.lan

enum class LanPreviewKind { Video, Audio, Pdf, Image, File }

fun lanPreviewKind(fileName: String): LanPreviewKind = when (fileName.substringAfterLast('.', "").lowercase()) {
    "mp4", "mov", "mkv", "webm", "avi" -> LanPreviewKind.Video
    "mp3", "m4a", "wav", "ogg", "flac", "amr" -> LanPreviewKind.Audio
    "pdf" -> LanPreviewKind.Pdf
    "jpg", "jpeg", "png", "webp", "gif", "bmp" -> LanPreviewKind.Image
    else -> LanPreviewKind.File
}

fun lanContentType(fileName: String): String = when (fileName.substringAfterLast('.', "").lowercase()) {
    "mp4" -> "video/mp4"
    "mov" -> "video/quicktime"
    "mkv" -> "video/x-matroska"
    "webm" -> "video/webm"
    "avi" -> "video/x-msvideo"
    "mp3" -> "audio/mpeg"
    "m4a" -> "audio/mp4"
    "wav" -> "audio/wav"
    "ogg" -> "audio/ogg"
    "flac" -> "audio/flac"
    "amr" -> "audio/amr"
    "jpg", "jpeg" -> "image/jpeg"
    "png" -> "image/png"
    "webp" -> "image/webp"
    "gif" -> "image/gif"
    "bmp" -> "image/bmp"
    "pdf" -> "application/pdf"
    "txt" -> "text/plain; charset=utf-8"
    else -> "application/octet-stream"
}

fun lanContentDisposition(fileName: String, inline: Boolean = false): String {
    val safe = fileName.replace(Regex("[\r\n\"]"), "_")
    val encoded = java.net.URLEncoder.encode(safe, "UTF-8").replace("+", "%20")
    val kind = if (inline) "inline" else "attachment"
    return "$kind; filename=\"$safe\"; filename*=UTF-8''$encoded"
}

fun isLanContentLocation(location: String): Boolean =
    location.startsWith("content:", ignoreCase = true)

sealed class LanByteRange {
    data object Whole : LanByteRange()
    data class Partial(val start: Long, val endInclusive: Long) : LanByteRange()
    data object Unsatisfiable : LanByteRange()
}

fun parseLanByteRange(header: String?, total: Long): LanByteRange {
    val raw = header?.trim().orEmpty()
    if (raw.isEmpty()) return LanByteRange.Whole
    if (total <= 0L) return LanByteRange.Unsatisfiable
    if (',' in raw) return LanByteRange.Unsatisfiable
    val prefix = "bytes="
    if (!raw.startsWith(prefix, ignoreCase = true)) return LanByteRange.Unsatisfiable
    val spec = raw.substring(prefix.length)
    val dash = spec.indexOf('-')
    if (dash < 0) return LanByteRange.Unsatisfiable
    val startText = spec.substring(0, dash)
    val endText = spec.substring(dash + 1)
    val start = startText.toLongOrNull() ?: return LanByteRange.Unsatisfiable
    if (start < 0L || start >= total) return LanByteRange.Unsatisfiable
    val end = if (endText.isEmpty()) total - 1 else endText.toLongOrNull() ?: return LanByteRange.Unsatisfiable
    if (end < start) return LanByteRange.Unsatisfiable
    return LanByteRange.Partial(start, minOf(end, total - 1))
}

fun lanContentRangeValue(start: Long, endInclusive: Long, total: Long): String =
    "bytes $start-$endInclusive/$total"

fun lanUnsatisfiableContentRange(total: Long): String = "bytes */$total"

fun copyLanRange(input: java.io.InputStream, output: java.io.OutputStream, start: Long, length: Long) {
    var skipped = 0L
    while (skipped < start) {
        val n = input.skip(start - skipped)
        if (n <= 0L) {
            if (input.read() < 0) return
            skipped += 1
        } else {
            skipped += n
        }
    }
    var remaining = length
    val buffer = ByteArray(8192)
    while (remaining > 0) {
        val want = minOf(buffer.size.toLong(), remaining).toInt()
        val read = input.read(buffer, 0, want)
        if (read < 0) return
        output.write(buffer, 0, read)
        remaining -= read
    }
}
