package com.videoconverter.android.ui

import android.content.res.Resources
import com.videoconverter.android.R
import com.videoconverter.android.data.OutputTarget
import com.videoconverter.android.domain.DocumentSourceKind
import com.videoconverter.android.domain.Job
import com.videoconverter.android.domain.JobStatus
import com.videoconverter.android.domain.MediaInfo
import com.videoconverter.android.domain.documentResultIsImage
import com.videoconverter.android.domain.documentSourceKind

enum class WizardStep { Sources, Format, Output }

data class WizardPresetCard(
    val id: String,
    val title: String = "",
    val titleRes: Int = 0,
    val hintRes: Int,
    val badgeRes: Int? = null,
)

val PRIMARY_PRESET_IDS = listOf("mp4-h264", "mp4-copy", "mp4-h265", "mov-h264")

val WIZARD_PRESET_CARDS = listOf(
    WizardPresetCard("mp4-h264", title = "MP4 · H.264", hintRes = R.string.preset_mp4_h264_desc, badgeRes = R.string.preset_badge_common),
    WizardPresetCard("mp4-copy", titleRes = R.string.preset_mp4_copy_title, hintRes = R.string.preset_mp4_copy_desc, badgeRes = R.string.preset_badge_fastest),
    WizardPresetCard("mp4-h265", title = "MP4 · H.265", hintRes = R.string.preset_mp4_h265_desc),
    WizardPresetCard("mov-h264", title = "MOV · H.264", hintRes = R.string.preset_mov_h264_desc),
    WizardPresetCard("mkv-copy-friendly", title = "MKV · H.264", hintRes = R.string.preset_mkv_copy_friendly_desc),
    WizardPresetCard("mkv-h265", title = "MKV · H.265", hintRes = R.string.preset_mkv_h265_desc),
    WizardPresetCard("webm-vp9", title = "WebM · VP9", hintRes = R.string.preset_webm_vp9_desc),
    WizardPresetCard("avi-mpeg4", title = "AVI · MPEG-4", hintRes = R.string.preset_avi_mpeg4_desc),
    WizardPresetCard("gif", title = "GIF", hintRes = R.string.preset_gif_desc),
    WizardPresetCard("audio-mp3", title = "MP3", hintRes = R.string.preset_audio_mp3_desc),
    WizardPresetCard("audio-aac", title = "M4A · AAC", hintRes = R.string.preset_audio_aac_desc),
)

val AUDIO_PRESET_CARDS = listOf(
    WizardPresetCard("audio-mp3", title = "MP3", hintRes = R.string.preset_audio_mp3_audio_desc),
    WizardPresetCard("audio-aac", title = "M4A · AAC", hintRes = R.string.preset_audio_aac_audio_desc),
    WizardPresetCard("audio-wav", title = "WAV", hintRes = R.string.preset_audio_wav_desc),
    WizardPresetCard("audio-flac", title = "FLAC", hintRes = R.string.preset_audio_flac_desc),
    WizardPresetCard("audio-ogg", title = "OGG · Opus", hintRes = R.string.preset_audio_ogg_desc),
    WizardPresetCard("audio-amr", title = "AMR", hintRes = R.string.preset_audio_amr_desc),
)

val IMAGE_DOCUMENT_CARDS = listOf(
    WizardPresetCard("image-jpg", title = "JPG", hintRes = R.string.preset_image_jpg_desc),
    WizardPresetCard("image-png", title = "PNG", hintRes = R.string.preset_image_png_desc),
    WizardPresetCard("image-webp", title = "WebP", hintRes = R.string.preset_image_webp_desc),
    WizardPresetCard("image-bmp", title = "BMP", hintRes = R.string.preset_image_bmp_desc),
    WizardPresetCard("image-gif", title = "GIF", hintRes = R.string.preset_image_gif_desc),
    WizardPresetCard("image-compress", titleRes = R.string.preset_image_compress_title, hintRes = R.string.preset_image_compress_desc),
)

val PDF_DOCUMENT_CARDS = listOf(
    WizardPresetCard("pdf-image", titleRes = R.string.preset_pdf_image_title, hintRes = R.string.preset_pdf_image_desc),
    WizardPresetCard("pdf-txt", titleRes = R.string.preset_pdf_txt_title, hintRes = R.string.preset_pdf_txt_desc),
    WizardPresetCard("pdf-compress", titleRes = R.string.preset_pdf_compress_title, hintRes = R.string.preset_pdf_compress_desc),
    WizardPresetCard("pdf-split", titleRes = R.string.preset_pdf_split_title, hintRes = R.string.preset_pdf_split_desc),
)

val OFFICE_DOCUMENT_CARDS = listOf(
    WizardPresetCard("office-pdf", titleRes = R.string.preset_office_pdf_title, hintRes = R.string.preset_office_pdf_desc),
)

fun documentCardsFor(kind: DocumentSourceKind): List<WizardPresetCard> = when (kind) {
    DocumentSourceKind.Image -> IMAGE_DOCUMENT_CARDS
    DocumentSourceKind.Pdf -> PDF_DOCUMENT_CARDS
    DocumentSourceKind.Word, DocumentSourceKind.Excel -> OFFICE_DOCUMENT_CARDS
}

fun outputChoicesForDocument(preset: String): List<OutputChoiceCard> =
    if (documentResultIsImage(preset)) {
        listOf(
            OutputChoiceCard(OUTPUT_CHOICE_GALLERY, R.string.output_gallery, R.string.output_gallery_hint),
            OutputChoiceCard(OUTPUT_CHOICE_DOWNLOADS, R.string.output_downloads, R.string.output_downloads_hint),
            OutputChoiceCard(OUTPUT_CHOICE_CUSTOM, R.string.output_custom, R.string.output_custom_hint),
        )
    } else {
        listOf(
            OutputChoiceCard(OUTPUT_CHOICE_DOCUMENTS, R.string.output_documents, R.string.output_documents_hint),
            OutputChoiceCard(OUTPUT_CHOICE_DOWNLOADS, R.string.output_downloads, R.string.output_downloads_hint),
            OutputChoiceCard(OUTPUT_CHOICE_CUSTOM, R.string.output_custom, R.string.output_custom_hint),
        )
    }

fun allowedDocumentOutputKinds(preset: String): Set<OutputTarget.Kind> =
    if (documentResultIsImage(preset)) {
        setOf(
            OutputTarget.Kind.Gallery,
            OutputTarget.Kind.Downloads,
            OutputTarget.Kind.SafTree,
            OutputTarget.Kind.AppExternal,
        )
    } else {
        setOf(
            OutputTarget.Kind.Documents,
            OutputTarget.Kind.Downloads,
            OutputTarget.Kind.SafTree,
            OutputTarget.Kind.AppExternal,
        )
    }

fun defaultDocumentOutput(preset: String): OutputTarget = OutputTarget(
    if (documentResultIsImage(preset)) OutputTarget.Kind.Gallery else OutputTarget.Kind.Documents,
)

fun documentKindOf(sources: List<SourceItem>): DocumentSourceKind? =
    sources.firstOrNull()?.let { documentSourceKind(it.media.displayName) }

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
    "flac" to "FLAC",
)

fun wizardScreenTitleRes(step: WizardStep): Int = when (step) {
    WizardStep.Sources -> R.string.wizard_title_sources
    WizardStep.Format -> R.string.wizard_title_format
    WizardStep.Output -> R.string.wizard_title_output
}

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

data class WizardReset(
    val step: WizardStep = WizardStep.Sources,
    val showAll: Boolean = false,
    val selectedUri: String? = null,
)

fun resetWizardAfterStart(): WizardReset = WizardReset()

const val OUTPUT_CHOICE_GALLERY = "gallery"
const val OUTPUT_CHOICE_MOVIES = "movies"
const val OUTPUT_CHOICE_DOWNLOADS = "downloads"
const val OUTPUT_CHOICE_CUSTOM = "custom"
const val OUTPUT_CHOICE_MUSIC = "music"
const val OUTPUT_CHOICE_DOCUMENTS = "documents"

data class OutputChoiceCard(
    val id: String,
    val titleRes: Int,
    val hintRes: Int,
)

val OUTPUT_CHOICE_CARDS = listOf(
    OutputChoiceCard(OUTPUT_CHOICE_GALLERY, R.string.output_gallery, R.string.output_gallery_hint),
    OutputChoiceCard(OUTPUT_CHOICE_MOVIES, R.string.output_movies, R.string.output_movies_hint),
    OutputChoiceCard(OUTPUT_CHOICE_DOWNLOADS, R.string.output_downloads, R.string.output_downloads_hint),
    OutputChoiceCard(OUTPUT_CHOICE_CUSTOM, R.string.output_custom, R.string.output_custom_hint),
)

val AUDIO_OUTPUT_CHOICE_CARDS = listOf(
    OutputChoiceCard(OUTPUT_CHOICE_MUSIC, R.string.output_music, R.string.output_music_hint),
    OutputChoiceCard(OUTPUT_CHOICE_DOWNLOADS, R.string.output_downloads, R.string.output_downloads_hint),
    OutputChoiceCard(OUTPUT_CHOICE_CUSTOM, R.string.output_custom, R.string.output_custom_hint),
)

fun outputChoiceId(output: OutputTarget): String = when (output.kind) {
    OutputTarget.Kind.Gallery -> OUTPUT_CHOICE_GALLERY
    OutputTarget.Kind.Movies -> OUTPUT_CHOICE_MOVIES
    OutputTarget.Kind.Downloads -> OUTPUT_CHOICE_DOWNLOADS
    OutputTarget.Kind.Music -> OUTPUT_CHOICE_MUSIC
    OutputTarget.Kind.Documents -> OUTPUT_CHOICE_DOCUMENTS
    OutputTarget.Kind.SafTree, OutputTarget.Kind.AppExternal -> OUTPUT_CHOICE_CUSTOM
}

fun outputKindForChoice(id: String): OutputTarget.Kind? = when (id) {
    OUTPUT_CHOICE_GALLERY -> OutputTarget.Kind.Gallery
    OUTPUT_CHOICE_MOVIES -> OutputTarget.Kind.Movies
    OUTPUT_CHOICE_DOWNLOADS -> OutputTarget.Kind.Downloads
    OUTPUT_CHOICE_MUSIC -> OutputTarget.Kind.Music
    OUTPUT_CHOICE_DOCUMENTS -> OutputTarget.Kind.Documents
    else -> null
}

fun collapsedPresetCards(selectedId: String, showAll: Boolean): List<WizardPresetCard> {
    if (showAll) return WIZARD_PRESET_CARDS
    val primary = PRIMARY_PRESET_IDS.map { id -> WIZARD_PRESET_CARDS.first { it.id == id } }
    if (selectedId in PRIMARY_PRESET_IDS) return primary
    val selected = WIZARD_PRESET_CARDS.find { it.id == selectedId } ?: return primary
    return primary.take(3) + selected
}

fun dockActionLabelRes(
    step: WizardStep,
    busy: Boolean,
    transcoding: Boolean,
    startLabelRes: Int = R.string.wizard_start_transcode,
): Int = when {
    step != WizardStep.Output -> R.string.action_next
    busy -> R.string.wizard_joining_queue
    transcoding -> R.string.wizard_converting
    else -> startLabelRes
}

fun dockSummary(
    resources: Resources,
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
    audioMode: Boolean = false,
    losslessAudio: Boolean = false,
    documentMode: Boolean = false,
    pageRangeLabel: String = "",
): String {
    if (documentMode && importableCount == 0) return resources.getString(R.string.wizard_need_document)
    if (audioMode && importableCount == 0) return resources.getString(R.string.wizard_need_audio)
    return when (step) {
        WizardStep.Sources -> if (importableCount == 0) {
            resources.getString(R.string.wizard_need_video)
        } else {
            resources.getString(R.string.wizard_selected_count, importableCount)
        }
        WizardStep.Format -> formatPreview.ifBlank { resources.getString(R.string.wizard_need_video_then_format) }
        WizardStep.Output -> {
            val qualityPart = if (losslessAudio) "" else " · $qualityLabel"
            val body = when {
                importableCount == 0 -> resources.getString(R.string.wizard_need_video_then_start)
                documentMode -> resources.getString(
                    R.string.wizard_convert_files,
                    importableCount,
                    "$presetTitle$pageRangeLabel",
                )
                audioOnly || audioMode -> resources.getString(
                    R.string.wizard_convert_files,
                    importableCount,
                    "$presetTitle$qualityPart$trimLabel",
                )
                copyOnly -> resources.getString(
                    R.string.wizard_convert_videos,
                    importableCount,
                    "$presetTitle$trimLabel",
                )
                else -> resources.getString(
                    R.string.wizard_convert_videos_quality,
                    importableCount,
                    presetTitle,
                    qualityLabel,
                    "$sizeLabel$trimLabel",
                )
            }
            if (importableCount == 0) {
                body
            } else {
                "$body · ${resources.getString(R.string.wizard_save_to, outputLabel)}"
            }
        }
    }
}

fun conversionPreview(resources: Resources, items: List<MediaInfo>, target: String): String {
    val importable = items.filter { it.importable }
    if (importable.isEmpty()) return resources.getString(R.string.wizard_need_video_then_format)
    val labels = importable.map(::sourceFromLabel).toSet()
    val from = if (labels.size == 1) labels.first() else resources.getString(R.string.wizard_source_kinds, labels.size)
    return "$from  →  $target"
}

fun presetCard(id: String): WizardPresetCard? =
    WIZARD_PRESET_CARDS.find { it.id == id }
        ?: AUDIO_PRESET_CARDS.find { it.id == id }
        ?: IMAGE_DOCUMENT_CARDS.find { it.id == id }
        ?: PDF_DOCUMENT_CARDS.find { it.id == id }
        ?: OFFICE_DOCUMENT_CARDS.find { it.id == id }

fun presetTitleRes(id: String): Int = presetCard(id)?.titleRes ?: 0

fun presetTitle(resources: Resources, id: String): String {
    val card = presetCard(id) ?: return id
    return if (card.titleRes != 0) resources.getString(card.titleRes) else card.title.ifBlank { id }
}

fun qualityLabelRes(id: String): Int = when (id) {
    "original" -> R.string.quality_original
    "small" -> R.string.quality_small
    "high" -> R.string.quality_high
    else -> R.string.quality_standard
}

fun sizeLabelRes(id: String): Int = when (id) {
    "1080p", "720p", "480p" -> 0
    else -> R.string.size_original
}

fun sizeLabel(resources: Resources, id: String): String =
    if (id == "1080p" || id == "720p" || id == "480p") id else resources.getString(R.string.size_original)

fun isAudioPreset(preset: String): Boolean =
    preset == "audio-mp3" || preset == "audio-aac" || preset == "audio-wav" ||
        preset == "audio-flac" || preset == "audio-ogg" || preset == "audio-amr"

fun isLosslessAudioPreset(preset: String): Boolean = preset == "audio-wav" || preset == "audio-flac"

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

fun outputFileName(outputPath: String?, untitled: String): String {
    val raw = outputPath?.substringAfterLast('/')?.substringAfterLast('\\')?.substringBefore('?')
    return raw?.takeIf { it.isNotBlank() } ?: untitled
}

fun historyTitle(job: Job, untitled: String): String {
    val output = outputFileName(job.outputPath, untitled)
    return if (output != untitled) output else job.displayName
}

fun historyDetail(resources: Resources, job: Job, status: String): String {
    val preset = presetTitle(resources, job.config.preset)
    return buildString {
        append(status)
        if (preset.isNotBlank()) append(" · ").append(preset)
        if (job.outputPaths.size > 1) {
            val count = job.outputPaths.size
            append(" · ").append(
                if (documentResultIsImage(job.config.preset)) {
                    resources.getString(R.string.wizard_result_images, count)
                } else {
                    resources.getString(R.string.wizard_result_pdfs, count)
                },
            )
        }
        if (job.status == JobStatus.Failed && !job.error.isNullOrBlank()) {
            append(" · ").append(job.error)
        }
    }
}

fun sourceFromLabel(media: MediaInfo): String {
    val container = friendlyContainerLabel(media.container, media.displayName)
    val codec = friendlyCodec(media.videoCodec).ifBlank { friendlyCodec(media.audioCodec) }
    return listOf(container, codec).filter { it.isNotBlank() }.joinToString(" · ")
}

fun sourceFormatLine(resources: Resources, media: MediaInfo, probing: Boolean): String {
    if (probing) return resources.getString(R.string.wizard_reading_format)
    if (!media.importable) return media.error ?: resources.getString(R.string.wizard_file_unreadable)
    when (documentSourceKind(media.displayName)) {
        DocumentSourceKind.Pdf -> {
            val pages = media.pageCount
            val start = media.pageStart ?: 1
            val end = media.pageEnd ?: pages ?: 1
            return buildString {
                append("PDF")
                if (pages != null) append(" · ").append(resources.getString(R.string.wizard_pages, pages))
                if (pages != null && (start != 1 || end != pages)) {
                    append(" · ").append(resources.getString(R.string.wizard_page_range, start, end))
                }
            }
        }
        DocumentSourceKind.Word -> return "Word"
        DocumentSourceKind.Excel -> return "Excel"
        DocumentSourceKind.Image -> {
            val ext = media.displayName.substringAfterLast('.', "").uppercase()
            return ext.ifBlank { resources.getString(R.string.wizard_image) }
        }
        null -> Unit
    }
    return listOf(
        friendlyContainer(resources, media.container, media.displayName).ifBlank {
            friendlyContainerLabel(media.container, media.displayName)
        },
        friendlyCodec(media.videoCodec).ifBlank { friendlyCodec(media.audioCodec) },
        if (media.width != null && media.height != null) "${media.width}×${media.height}" else "",
        media.frameRate?.let { "${kotlin.math.round(it).toInt()} fps" } ?: "",
        formatDuration(resources, media.durationSecs),
        if (isTrimmed(media)) resources.getString(R.string.wizard_trimmed) else "",
    ).filter { it.isNotBlank() }.joinToString(" · ")
}

private fun friendlyCodec(codec: String?): String {
    if (codec.isNullOrBlank()) return ""
    return CODEC_LABELS[codec.lowercase()] ?: codec.uppercase()
}

private fun friendlyContainerLabel(container: String?, name: String): String {
    val ext = name.substringAfterLast('.', "").uppercase()
    val value = (container ?: "").lowercase()
    return when {
        "matroska" in value || ext == "MKV" -> "MKV"
        "webm" in value || ext == "WEBM" -> "WebM"
        "mp3" in value || ext == "MP3" -> "MP3"
        ext == "M4A" || value == "m4a" || value.startsWith("m4a,") -> "M4A"
        ext == "AAC" || value == "aac" || value.startsWith("aac,") -> "AAC"
        ext == "FLAC" || "flac" in value -> "FLAC"
        ext == "WAV" || value == "wav" || "wav" in value -> "WAV"
        ext == "OGG" || ext == "OPUS" || "ogg" in value -> "OGG"
        ext == "AMR" || "amr" in value -> "AMR"
        ext == "APE" || "ape" in value -> "APE"
        "avi" in value || ext == "AVI" -> "AVI"
        ext == "MOV" -> "MOV"
        "mp4" in value || "mov" in value || ext == "MP4" || ext == "M4V" -> "MP4"
        ext.isNotBlank() -> ext
        else -> ""
    }
}

private fun friendlyContainer(resources: Resources, container: String?, name: String): String =
    friendlyContainerLabel(container, name).ifBlank { resources.getString(R.string.wizard_kind_video) }

private fun formatDuration(resources: Resources, seconds: Double?): String {
    if (seconds == null || seconds.isNaN()) return ""
    val total = kotlin.math.round(seconds).toInt()
    if (total < 60) return resources.getString(R.string.duration_seconds, total)
    val mins = total / 60
    val secs = total % 60
    return if (mins < 60) {
        if (secs == 0) {
            resources.getString(R.string.duration_minutes, mins)
        } else {
            resources.getString(R.string.duration_min_sec, mins, secs)
        }
    } else {
        val hours = mins / 60
        val rest = mins % 60
        resources.getString(R.string.duration_hour_min, hours, rest)
    }
}
