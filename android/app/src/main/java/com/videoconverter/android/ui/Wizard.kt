package com.videoconverter.android.ui

import com.videoconverter.android.domain.MediaInfo

enum class WizardStep { Sources, Format, Output }

data class WizardPresetCard(
    val id: String,
    val title: String,
    val hint: String,
    val badge: String? = null,
)

val PRIMARY_PRESET_IDS = listOf("mp4-h264", "mp4-copy", "mp4-h265", "mov-h264")

val WIZARD_PRESET_CARDS = listOf(
    WizardPresetCard("mp4-h264", "MP4 · H.264", "几乎所有设备都能打开", "常用"),
    WizardPresetCard("mp4-copy", "MP4 · 不重编码", "只换外壳，速度最快", "最快"),
    WizardPresetCard("mp4-h265", "MP4 · H.265", "同样是 MP4，编码更新"),
    WizardPresetCard("mov-h264", "MOV · H.264", "苹果设备、剪辑软件"),
    WizardPresetCard("mkv-copy-friendly", "MKV · H.264", "适合封装保存"),
    WizardPresetCard("mkv-h265", "MKV · H.265", "适合长期存档"),
    WizardPresetCard("webm-vp9", "WebM · VP9", "网页常用，会比 MP4 慢一点"),
    WizardPresetCard("avi-mpeg4", "AVI · MPEG-4", "旧电脑和投影"),
    WizardPresetCard("gif", "GIF", "短视频转成动图"),
    WizardPresetCard("audio-mp3", "MP3", "只导出音频"),
    WizardPresetCard("audio-aac", "M4A · AAC", "只导出音频"),
)

private val CODEC_LABELS = mapOf(
    "h264" to "H.264",
    "hevc" to "H.265",
    "h265" to "H.265",
    "vp9" to "VP9",
    "vp8" to "VP8",
    "av1" to "AV1",
    "mpeg4" to "MPEG-4",
    "aac" to "AAC",
    "opus" to "Opus",
    "mp3" to "MP3",
)

fun canEnterStep(step: WizardStep, importableCount: Int): Boolean =
    step == WizardStep.Sources || importableCount > 0

fun advanceStep(current: WizardStep, importableCount: Int): WizardStep? = when (current) {
    WizardStep.Sources -> WizardStep.Format.takeIf { importableCount > 0 }
    WizardStep.Format -> WizardStep.Output.takeIf { importableCount > 0 }
    WizardStep.Output -> null
}

fun retreatStep(current: WizardStep): WizardStep? = when (current) {
    WizardStep.Sources -> null
    WizardStep.Format -> WizardStep.Sources
    WizardStep.Output -> WizardStep.Format
}

fun collapsedPresetCards(selectedId: String, showAll: Boolean): List<WizardPresetCard> {
    if (showAll) return WIZARD_PRESET_CARDS
    val primary = PRIMARY_PRESET_IDS.map { id -> WIZARD_PRESET_CARDS.first { it.id == id } }
    if (selectedId in PRIMARY_PRESET_IDS) return primary
    val selected = WIZARD_PRESET_CARDS.find { it.id == selectedId } ?: return primary
    return primary.take(3) + selected
}

fun dockActionLabel(step: WizardStep, busy: Boolean, transcoding: Boolean): String = when {
    step != WizardStep.Output -> "下一步"
    busy -> "正在加入队列…"
    transcoding -> "正在转码…"
    else -> "开始转码"
}

fun dockSummary(
    step: WizardStep,
    importableCount: Int,
    presetTitle: String,
    qualityLabel: String,
    sizeLabel: String,
    audioOnly: Boolean,
    copyOnly: Boolean,
    trimLabel: String,
    outputLabel: String,
    formatPreview: String = "",
): String = when (step) {
    WizardStep.Sources -> if (importableCount == 0) "先添加源视频" else "已选 ${importableCount} 个文件"
    WizardStep.Format -> formatPreview.ifBlank { "先添加源视频，再选要转成的格式" }
    WizardStep.Output -> {
        val body = when {
            importableCount == 0 -> "先添加源视频，再开始转码"
            audioOnly -> "将 $importableCount 个文件转为 $presetTitle · $qualityLabel$trimLabel"
            copyOnly -> "将 $importableCount 个视频转为 $presetTitle$trimLabel"
            else -> "将 $importableCount 个视频转为 $presetTitle · $qualityLabel · $sizeLabel$trimLabel"
        }
        if (importableCount == 0) body else "$body · 存到$outputLabel"
    }
}

fun conversionPreview(items: List<MediaInfo>, target: String): String {
    val importable = items.filter { it.importable }
    if (importable.isEmpty()) return "先添加源视频，再选要转成的格式"
    val labels = importable.map(::sourceFromLabel).toSet()
    val from = if (labels.size == 1) labels.first() else "${labels.size} 种源格式"
    return "$from  →  $target"
}

fun presetTitle(id: String): String = WIZARD_PRESET_CARDS.find { it.id == id }?.title ?: id

fun qualityLabel(id: String): String = when (id) {
    "original" -> "原画"
    "small" -> "节省体积"
    else -> "标准"
}

fun sizeLabel(id: String): String = when (id) {
    "1080p" -> "1080p"
    "720p" -> "720p"
    "480p" -> "480p"
    else -> "原尺寸"
}

fun isAudioPreset(preset: String): Boolean = preset == "audio-mp3" || preset == "audio-aac"

fun isCopyPreset(preset: String): Boolean = preset == "mp4-copy"

fun itemHasDuration(media: MediaInfo): Boolean =
    media.importable && (media.durationSecs ?: 0.0) > 0.05

fun isTrimmed(media: MediaInfo): Boolean {
    if (!itemHasDuration(media)) return false
    val duration = media.durationSecs ?: 0.0
    return (media.trimStartSecs ?: 0.0) > 0.2 ||
        (media.trimEndSecs != null && duration - media.trimEndSecs!! > 0.2)
}

fun timeAt(x: Float, width: Float, duration: Double): Double {
    if (width <= 0f || duration <= 0.0) return 0.0
    return ((x / width).toDouble().coerceIn(0.0, 1.0)) * duration
}

fun clampTrim(start: Double, end: Double, duration: Double): Pair<Double, Double> {
    if (duration <= 0.0) return 0.0 to 0.0
    val minGap = minOf(0.2, duration / 20.0).coerceAtLeast(0.01)
    val s = start.coerceIn(0.0, duration)
    val e = end.coerceIn(0.0, duration)
    return if (e - s < minGap) {
        val nextEnd = (s + minGap).coerceAtMost(duration)
        (nextEnd - minGap).coerceAtLeast(0.0) to nextEnd
    } else {
        s to e
    }
}

fun outputFileName(outputPath: String?): String {
    val raw = outputPath?.substringAfterLast('/')?.substringAfterLast('\\')?.substringBefore('?')
    return raw?.takeIf { it.isNotBlank() } ?: "未命名"
}

fun sourceFromLabel(media: MediaInfo): String {
    val container = friendlyContainer(media.container, media.displayName)
    val codec = friendlyCodec(media.videoCodec).ifBlank { friendlyCodec(media.audioCodec) }
    return listOf(container, codec).filter { it.isNotBlank() }.joinToString(" · ")
}

fun sourceFormatLine(media: MediaInfo, probing: Boolean): String {
    if (probing) return "正在读取格式…"
    if (!media.importable) return media.error ?: "这个文件打不开"
    return listOf(
        friendlyContainer(media.container, media.displayName),
        friendlyCodec(media.videoCodec).ifBlank { friendlyCodec(media.audioCodec) },
        if (media.width != null && media.height != null) "${media.width}×${media.height}" else "",
        media.frameRate?.let { "${kotlin.math.round(it).toInt()} fps" } ?: "",
        formatDuration(media.durationSecs),
        if (isTrimmed(media)) "已裁剪" else "",
    ).filter { it.isNotBlank() }.joinToString(" · ")
}

private fun friendlyCodec(codec: String?): String {
    if (codec.isNullOrBlank()) return ""
    return CODEC_LABELS[codec.lowercase()] ?: codec.uppercase()
}

private fun friendlyContainer(container: String?, name: String): String {
    val ext = name.substringAfterLast('.', "").uppercase()
    val value = (container ?: "").lowercase()
    return when {
        "matroska" in value || ext == "MKV" -> "MKV"
        "webm" in value || ext == "WEBM" -> "WebM"
        "mp3" in value || ext == "MP3" -> "MP3"
        "avi" in value || ext == "AVI" -> "AVI"
        ext == "MOV" -> "MOV"
        "mp4" in value || "mov" in value || ext == "MP4" || ext == "M4V" -> "MP4"
        ext.isNotBlank() -> ext
        else -> "视频"
    }
}

private fun formatDuration(seconds: Double?): String {
    if (seconds == null || seconds.isNaN()) return ""
    val total = kotlin.math.round(seconds).toInt()
    if (total < 60) return "$total 秒"
    val mins = total / 60
    val secs = total % 60
    return if (mins < 60) {
        if (secs == 0) "$mins 分钟" else "$mins 分 $secs 秒"
    } else {
        val hours = mins / 60
        val rest = mins % 60
        "$hours 小时 $rest 分"
    }
}
