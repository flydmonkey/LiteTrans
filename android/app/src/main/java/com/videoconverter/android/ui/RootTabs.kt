package com.videoconverter.android.ui

import com.videoconverter.android.domain.Job
import com.videoconverter.android.domain.JobStatus
import com.videoconverter.android.domain.isDocumentPreset
import com.videoconverter.android.domain.resolveConfig

enum class RootTab { Transcode, Audio, History, Mine }

enum class HistorySegment { Video, Audio, Document }

enum class MinePage { Root, Privacy, Terms, About }

data class MineItem(val page: MinePage, val title: String)

data class RootBack(
    val tab: RootTab,
    val minePage: MinePage,
    val wizardStep: WizardStep,
)

fun rootTabLabel(tab: RootTab): String = when (tab) {
    RootTab.Transcode -> "视频转码"
    RootTab.Audio -> "音频转换"
    RootTab.History -> "历史记录"
    RootTab.Mine -> "我的"
}

fun mineItems(): List<MineItem> = listOf(
    MineItem(MinePage.Privacy, "隐私协议"),
    MineItem(MinePage.Terms, "使用条款"),
    MineItem(MinePage.About, "关于"),
)

fun minePageTitle(page: MinePage): String = when (page) {
    MinePage.Root -> "我的"
    MinePage.Privacy -> "隐私协议"
    MinePage.Terms -> "使用条款"
    MinePage.About -> "关于"
}

fun minePageBody(page: MinePage): String = when (page) {
    MinePage.Root -> ""
    MinePage.Privacy -> PRIVACY_BODY
    MinePage.Terms -> TERMS_BODY
    MinePage.About -> aboutBody("0.1.0")
}

fun aboutBody(versionName: String): String =
    "轻转码 $versionName\n\n本机视频转码工具。所选视频只在这台设备上处理，不上传，不要求联网。当前版本仅提供 Android 侧载安装。"

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

fun historyEmptyLabel(segment: HistorySegment): String = when (segment) {
    HistorySegment.Video -> "还没有视频记录"
    HistorySegment.Audio -> "还没有音频记录"
    HistorySegment.Document -> "还没有文档记录"
}

fun remainingJobsAfterClearFinished(jobs: List<Job>, segment: HistorySegment): List<Job> =
    jobs.filter { job ->
        if (historySegmentFor(job) != segment) true
        else job.status == JobStatus.Queued || job.status == JobStatus.Running
    }

fun historySegmentAfterEnqueue(mode: ConvertMode, preset: String): HistorySegment = when {
    isDocumentPreset(preset) -> HistorySegment.Document
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
    tab == RootTab.Transcode || tab == RootTab.Audio ->
        retreatStep(wizardStep)?.let { RootBack(tab, minePage, it) }
    else -> null
}

fun minePageAfterLeavingTab(nextTab: RootTab, minePage: MinePage): MinePage =
    if (nextTab == RootTab.Mine) minePage else MinePage.Root

private const val PRIVACY_BODY =
    "轻转码在这台设备上处理你选中的视频。源文件和转出的文件都留在手机或你指定的文件夹里，不会上传到任何服务器。\n\n" +
        "应用不收集账号、不统计使用行为，也不要求联网才能转码。相册和文件访问只用于读取你选中的视频；通知权限只用于显示转码进度。"

private const val TERMS_BODY =
    "轻转码供个人将自己有权处理的视频转成其他格式。请确保你拥有源文件的相应权利。\n\n" +
        "转出画质取决于源文件和你选择的预设，应用不保证每台设备都能打开结果。转码在本地进行；中断、取消或失败时可能留下不完整文件，可在历史记录里重试或清理。"
