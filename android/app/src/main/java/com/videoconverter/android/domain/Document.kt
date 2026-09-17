package com.videoconverter.android.domain

enum class DocumentSourceKind { Image, Pdf, Word, Excel }

private val DOCUMENT_PRESET_IDS = setOf(
    "image-jpg",
    "image-png",
    "image-webp",
    "image-bmp",
    "image-gif",
    "image-compress",
    "pdf-image",
    "pdf-txt",
    "pdf-compress",
    "pdf-split",
    "office-pdf",
)

private val IMAGE_RESULT_PRESETS = setOf(
    "image-jpg",
    "image-png",
    "image-webp",
    "image-bmp",
    "image-gif",
    "image-compress",
    "pdf-image",
)

fun documentSourceKind(fileName: String): DocumentSourceKind? = when (fileName.substringAfterLast('.').lowercase()) {
    "jpg", "jpeg", "png", "webp", "bmp", "gif", "heic" -> DocumentSourceKind.Image
    "pdf" -> DocumentSourceKind.Pdf
    "docx" -> DocumentSourceKind.Word
    "xlsx" -> DocumentSourceKind.Excel
    else -> null
}

fun unsupportedDocumentReason(fileName: String): String? =
    if (documentSourceKind(fileName) == null) "不支持此格式" else null

fun sameDocumentKind(existing: List<String>, incoming: String): Boolean {
    if (existing.isEmpty()) return true
    val incomingKind = documentSourceKind(incoming) ?: return false
    return existing.all { documentSourceKind(it) == incomingKind }
}

fun clampPageRange(start: Int, end: Int, pageCount: Int): Pair<Int, Int> {
    val pages = if (pageCount < 1) 1 else pageCount
    val lo = start.coerceIn(1, pages)
    val hi = end.coerceIn(lo, pages)
    return lo to hi
}

fun defaultDocumentPreset(kind: DocumentSourceKind): String = when (kind) {
    DocumentSourceKind.Image -> "image-jpg"
    DocumentSourceKind.Pdf -> "pdf-image"
    DocumentSourceKind.Word, DocumentSourceKind.Excel -> "office-pdf"
}

fun documentPresetIds(): Set<String> = DOCUMENT_PRESET_IDS

fun isDocumentPreset(preset: String): Boolean = preset in DOCUMENT_PRESET_IDS

fun documentResultIsImage(preset: String): Boolean = preset in IMAGE_RESULT_PRESETS

fun documentExtension(preset: String, imageFormat: String?): String = when (preset) {
    "image-jpg" -> "jpg"
    "image-png" -> "png"
    "image-webp" -> "webp"
    "image-bmp" -> "bmp"
    "image-gif" -> "gif"
    "image-compress" -> imageFormat?.takeIf { it.isNotEmpty() } ?: "jpg"
    "pdf-image" -> imageFormat?.takeIf { it.isNotEmpty() } ?: "jpg"
    "pdf-txt" -> "txt"
    "pdf-compress", "pdf-split", "office-pdf" -> "pdf"
    else -> imageFormat?.takeIf { it.isNotEmpty() } ?: "bin"
}

fun documentOutputFileName(stem: String, index: Int, total: Int, ext: String): String =
    if (total <= 1) "$stem.$ext" else "$stem-${index.toString().padStart(3, '0')}.$ext"
