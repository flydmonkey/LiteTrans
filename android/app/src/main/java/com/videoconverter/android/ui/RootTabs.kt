package com.videoconverter.android.ui

import com.videoconverter.android.R
import com.videoconverter.android.domain.Job
import com.videoconverter.android.domain.JobStatus
import com.videoconverter.android.domain.isDocumentPreset
import com.videoconverter.android.domain.resolveConfig

enum class RootTab { Transcode, Audio, Document, History, Mine }

enum class HistorySegment { Video, Audio, Document }

enum class MinePage { Root, LanShare, Language, Privacy, Terms, About }

data class MineItem(val page: MinePage, val titleRes: Int)

data class RootBack(
    val tab: RootTab,
    val minePage: MinePage,
    val wizardStep: WizardStep,
)

fun rootTabLabelRes(tab: RootTab): Int = when (tab) {
    RootTab.Transcode -> R.string.tab_transcode
    RootTab.Audio -> R.string.tab_audio
    RootTab.Document -> R.string.tab_document
    RootTab.History -> R.string.tab_history
    RootTab.Mine -> R.string.tab_mine
}

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

fun historySegmentAfterEnqueue(mode: ConvertMode, preset: String): HistorySegment = when {
    mode == ConvertMode.Document || isDocumentPreset(preset) -> HistorySegment.Document
    mode == ConvertMode.Audio || isAudioPreset(preset) -> HistorySegment.Audio
    else -> HistorySegment.Video
}

fun consumeRootBack(
    tab: RootTab,
    minePage: MinePage,
    wizardStep: WizardStep,
): RootBack? = when {
    tab == RootTab.Mine && minePage != MinePage.Root ->
        RootBack(tab, MinePage.Root, wizardStep)
    tab == RootTab.Transcode || tab == RootTab.Audio || tab == RootTab.Document ->
        retreatStep(wizardStep)?.let { RootBack(tab, minePage, it) }
    else -> null
}

fun minePageAfterLeavingTab(nextTab: RootTab, minePage: MinePage): MinePage =
    if (nextTab == RootTab.Mine) minePage else MinePage.Root
