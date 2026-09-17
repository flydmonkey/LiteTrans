package com.videoconverter.android.lan

import com.videoconverter.android.domain.Job
import com.videoconverter.android.domain.JobStatus
import com.videoconverter.android.domain.resolveConfig

enum class LanLibraryTab {
    Video, Audio, Image, Document
}

fun LanLibraryTab.wireName(): String = when (this) {
    LanLibraryTab.Video -> "video"
    LanLibraryTab.Audio -> "audio"
    LanLibraryTab.Image -> "image"
    LanLibraryTab.Document -> "document"
}

fun lanLibraryTabFor(kind: LanPreviewKind): LanLibraryTab = when (kind) {
    LanPreviewKind.Video -> LanLibraryTab.Video
    LanPreviewKind.Audio -> LanLibraryTab.Audio
    LanPreviewKind.Image -> LanLibraryTab.Image
    LanPreviewKind.Pdf, LanPreviewKind.File -> LanLibraryTab.Document
}

data class LanLibraryItem(
    val jobId: String,
    val index: Int,
    val path: String,
    val kind: LanPreviewKind,
    val tab: LanLibraryTab,
    val label: String,
    val format: String,
    val needsIndex: Boolean,
)

fun lanLibraryItems(jobs: List<Job>, fileExists: (String) -> Boolean): List<LanLibraryItem> {
    val out = ArrayList<LanLibraryItem>()
    for (job in jobs.asReversed()) {
        if (job.status != JobStatus.Completed) continue
        val paths = jobOutputPaths(job)
        for ((index, path) in paths.withIndex()) {
            if (path.isBlank() || !fileExists(path)) continue
            val label = lanPreviewFileName(path, job)
            val kind = lanPreviewKind(label)
            val format = resolveConfig(job.config).getOrNull()?.container
                ?: label.substringAfterLast('.', job.config.preset)
            out += LanLibraryItem(
                jobId = job.id,
                index = index,
                path = path,
                kind = kind,
                tab = lanLibraryTabFor(kind),
                label = label,
                format = format,
                needsIndex = paths.size > 1 || index > 0,
            )
        }
    }
    return out
}

fun lanLibraryItemsFor(items: List<LanLibraryItem>, tab: LanLibraryTab): List<LanLibraryItem> =
    items.filter { it.tab == tab }

fun lanDefaultLibraryTab(items: List<LanLibraryItem>): LanLibraryTab {
    val order = listOf(
        LanLibraryTab.Video,
        LanLibraryTab.Audio,
        LanLibraryTab.Image,
        LanLibraryTab.Document,
    )
    return order.firstOrNull { tab -> items.any { it.tab == tab } } ?: LanLibraryTab.Video
}
