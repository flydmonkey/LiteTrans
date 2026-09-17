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
