package com.videoconverter.android.ui

import com.videoconverter.android.R
import com.videoconverter.android.domain.Job
import com.videoconverter.android.domain.JobStatus
import com.videoconverter.android.domain.isDocumentPreset
import com.videoconverter.android.domain.resolveConfig

enum class RootTab { Convert, History, Mine }

enum class HistorySegment { Video, Audio, Document }

enum class MinePage { Root, LanShare, Language, Privacy, Terms, About }

data class MineItem(val page: MinePage, val titleRes: Int)

data class RootBack(
    val tab: RootTab,
    val minePage: MinePage,
    val convertPage: ConvertPage = ConvertPage.Home,
)

enum class ConvertPage { Home, Format, Quality, Size, Output }

enum class ConvertSetting { Format, Quality, Size, Output }

fun convertSettingsFor(preset: String): List<ConvertSetting> = buildList {
    add(ConvertSetting.Format)
    if (shouldShowQualityRow(preset)) add(ConvertSetting.Quality)
    if (shouldShowResolution(preset)) add(ConvertSetting.Size)
    add(ConvertSetting.Output)
}

fun shouldShowQualityRow(preset: String): Boolean = when {
    isCopyPreset(preset) || isLosslessAudioPreset(preset) -> false
    preset == "office-pdf" || preset == "pdf-txt" || preset == "pdf-split" || preset == "pdf-image" -> false
    preset.startsWith("image-") && preset != "image-compress" -> false
    else -> true
}

fun convertPageTitleRes(page: ConvertPage): Int = when (page) {
    ConvertPage.Home -> R.string.tab_convert
    ConvertPage.Format -> R.string.wizard_title_format
    ConvertPage.Quality -> R.string.quality_video_title
    ConvertPage.Size -> R.string.resolution_title
    ConvertPage.Output -> R.string.wizard_title_output
}

fun convertSettingTitleRes(setting: ConvertSetting, preset: String = ""): Int = when (setting) {
    ConvertSetting.Format -> R.string.wizard_title_format
    ConvertSetting.Quality -> when {
        preset == "image-compress" || preset == "pdf-compress" -> R.string.format_compress_title
        isAudioPreset(preset) -> R.string.quality_audio_title
        else -> R.string.quality_video_title
    }
    ConvertSetting.Size -> R.string.resolution_title
    ConvertSetting.Output -> R.string.wizard_title_output
}

fun convertPageFor(setting: ConvertSetting): ConvertPage = when (setting) {
    ConvertSetting.Format -> ConvertPage.Format
    ConvertSetting.Quality -> ConvertPage.Quality
    ConvertSetting.Size -> ConvertPage.Size
    ConvertSetting.Output -> ConvertPage.Output
}

fun mineItemGroups(): List<List<MineItem>> = listOf(
    listOf(
        MineItem(MinePage.LanShare, R.string.mine_lan),
        MineItem(MinePage.Language, R.string.mine_language),
    ),
    listOf(
        MineItem(MinePage.Privacy, R.string.mine_privacy),
        MineItem(MinePage.Terms, R.string.mine_terms),
    ),
    listOf(MineItem(MinePage.About, R.string.mine_about)),
)

enum class MineRowIcon { Share, Language, Lock, List, Info }

fun mineRowIcon(page: MinePage): MineRowIcon = when (page) {
    MinePage.Root -> MineRowIcon.Info
    MinePage.LanShare -> MineRowIcon.Share
    MinePage.Language -> MineRowIcon.Language
    MinePage.Privacy -> MineRowIcon.Lock
    MinePage.Terms -> MineRowIcon.List
    MinePage.About -> MineRowIcon.Info
}

fun rootTabLabelRes(tab: RootTab): Int = when (tab) {
    RootTab.Convert -> R.string.tab_convert
    RootTab.History -> R.string.tab_history
    RootTab.Mine -> R.string.tab_mine
}

fun convertModeLabelRes(mode: ConvertMode): Int = when (mode) {
    ConvertMode.Video -> R.string.lan_segment_video
    ConvertMode.Audio -> R.string.lan_segment_audio
    ConvertMode.Document -> R.string.lan_segment_document
}

fun convertModeIndex(mode: ConvertMode): Int = ConvertMode.entries.indexOf(mode)

fun convertModeAt(index: Int): ConvertMode =
    ConvertMode.entries[index.coerceIn(ConvertMode.entries.indices)]

fun historySegmentIndex(segment: HistorySegment): Int = HistorySegment.entries.indexOf(segment)

fun historySegmentAt(index: Int): HistorySegment =
    HistorySegment.entries[index.coerceIn(HistorySegment.entries.indices)]

fun mineItems(): List<MineItem> = listOf(
    MineItem(MinePage.LanShare, R.string.mine_lan),
    MineItem(MinePage.Language, R.string.mine_language),
    MineItem(MinePage.Privacy, R.string.mine_privacy),
    MineItem(MinePage.Terms, R.string.mine_terms),
    MineItem(MinePage.About, R.string.mine_about),
)

fun minePageTitleRes(page: MinePage): Int = when (page) {
    MinePage.Root -> R.string.tab_mine
    MinePage.LanShare -> R.string.mine_lan
    MinePage.Language -> R.string.mine_language
    MinePage.Privacy -> R.string.mine_privacy
    MinePage.Terms -> R.string.mine_terms
    MinePage.About -> R.string.mine_about
}

fun minePageBodyRes(page: MinePage): Int = when (page) {
    MinePage.Privacy -> R.string.privacy_body
    MinePage.Terms -> R.string.terms_body
    else -> 0
}

fun legalUrl(page: MinePage): String? = when (page) {
    MinePage.Privacy -> "https://flydmonkey.github.io/LiteTrans/docs/privacy.html"
    MinePage.Terms -> "https://flydmonkey.github.io/LiteTrans/docs/terms.html"
    else -> null
}

fun aboutBodyRes(): Int = R.string.about_body

fun isAudioHistoryJob(job: Job): Boolean {
    val preset = job.config.preset
    if (preset in listOf(
            "audio-mp3", "audio-aac", "audio-wav", "audio-flac", "audio-ogg", "audio-amr",
        )
    ) {
        return true
    }
    val container = resolveConfig(job.config).getOrNull()?.container
    return container in listOf("mp3", "m4a", "wav", "ogg", "flac", "amr")
}

fun isDocumentHistoryJob(job: Job): Boolean = isDocumentPreset(job.config.preset)

fun historySegmentFor(job: Job): HistorySegment = when {
    isDocumentHistoryJob(job) -> HistorySegment.Document
    isAudioHistoryJob(job) -> HistorySegment.Audio
    else -> HistorySegment.Video
}

enum class HistoryThumbKind { Video, Audio, Image, Document }

private val IMAGE_THUMB_EXTENSIONS = setOf(
    "jpg", "jpeg", "png", "webp", "gif", "bmp", "heic", "heif",
)

fun historyThumbFileName(job: Job): String =
    (job.outputPaths.firstOrNull() ?: job.outputPath ?: job.displayName)

fun historyThumbKind(job: Job): HistoryThumbKind {
    val name = historyThumbFileName(job)
    val ext = name.substringAfterLast('/', name)
        .substringAfterLast('.', "")
        .substringBefore('?')
        .lowercase()
    val image = ext in IMAGE_THUMB_EXTENSIONS
    return when (historySegmentFor(job)) {
        HistorySegment.Audio -> HistoryThumbKind.Audio
        HistorySegment.Document -> if (image) HistoryThumbKind.Image else HistoryThumbKind.Document
        HistorySegment.Video -> if (image) HistoryThumbKind.Image else HistoryThumbKind.Video
    }
}

fun historyJobs(jobs: List<Job>, segment: HistorySegment): List<Job> =
    jobs.filter { historySegmentFor(it) == segment }

fun historyEmptyLabelRes(segment: HistorySegment): Int = when (segment) {
    HistorySegment.Video -> R.string.history_empty_video
    HistorySegment.Audio -> R.string.history_empty_audio
    HistorySegment.Document -> R.string.history_empty_document
}

fun remainingJobsAfterClearFinished(jobs: List<Job>, segment: HistorySegment): List<Job> =
    jobs.filter { job ->
        if (historySegmentFor(job) != segment) true
        else job.status == JobStatus.Queued || job.status == JobStatus.Running
    }

enum class JobRowAction { Cancel, Retry, Open, Share, Rename, Delete }

fun jobRowActions(status: JobStatus): List<JobRowAction> = when (status) {
    JobStatus.Queued, JobStatus.Running -> listOf(JobRowAction.Cancel)
    JobStatus.Failed, JobStatus.Cancelled -> listOf(JobRowAction.Retry, JobRowAction.Delete)
    JobStatus.Completed -> listOf(JobRowAction.Open, JobRowAction.Share, JobRowAction.Rename, JobRowAction.Delete)
}

fun jobRowPrimaryAction(status: JobStatus): JobRowAction? = when (status) {
    JobStatus.Queued, JobStatus.Running -> JobRowAction.Cancel
    JobStatus.Failed, JobStatus.Cancelled -> JobRowAction.Retry
    JobStatus.Completed -> JobRowAction.Open
}

fun jobRowOverflowActions(status: JobStatus): List<JobRowAction> {
    val primary = jobRowPrimaryAction(status)
    return jobRowActions(status).filter { it != primary }
}

fun hasFinishedJobs(jobs: List<Job>): Boolean =
    jobs.any { it.status != JobStatus.Queued && it.status != JobStatus.Running }

fun hasActiveJobs(jobs: List<Job>): Boolean =
    jobs.any { it.status == JobStatus.Queued || it.status == JobStatus.Running }

fun historyActiveCount(jobs: List<Job>): Int =
    jobs.count { it.status == JobStatus.Queued || it.status == JobStatus.Running }

fun historyEmptyGlyph(segment: HistorySegment): AppGlyph = when (segment) {
    HistorySegment.Video -> AppGlyph.Video
    HistorySegment.Audio -> AppGlyph.Audio
    HistorySegment.Document -> AppGlyph.Document
}

fun convertModeForHistorySegment(segment: HistorySegment): ConvertMode = when (segment) {
    HistorySegment.Video -> ConvertMode.Video
    HistorySegment.Audio -> ConvertMode.Audio
    HistorySegment.Document -> ConvertMode.Document
}

fun historySegmentAfterEnqueue(mode: ConvertMode, preset: String): HistorySegment = when {
    mode == ConvertMode.Document || isDocumentPreset(preset) -> HistorySegment.Document
    mode == ConvertMode.Audio || isAudioPreset(preset) -> HistorySegment.Audio
    else -> HistorySegment.Video
}

fun consumeRootBack(
    tab: RootTab,
    minePage: MinePage,
    convertPage: ConvertPage,
): RootBack? = when {
    tab == RootTab.Mine && minePage != MinePage.Root ->
        RootBack(tab, MinePage.Root, convertPage)
    tab == RootTab.Convert && convertPage != ConvertPage.Home ->
        RootBack(tab, minePage, ConvertPage.Home)
    else -> null
}

fun minePageAfterLeavingTab(nextTab: RootTab, minePage: MinePage): MinePage =
    if (nextTab == RootTab.Mine) minePage else MinePage.Root
