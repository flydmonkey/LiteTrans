package com.videoconverter.android.lan

import com.videoconverter.android.domain.Job
import com.videoconverter.android.domain.JobStatus
import com.videoconverter.android.domain.MediaInfo
import com.videoconverter.android.domain.OutputConfig
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LanHistoryHtmlTest {
    @Test
    fun tabsGroupReadyFilesAndHideSourceUri() {
        val jobs = listOf(
            job("v", JobStatus.Completed, listOf("/tmp/v.mp4"), "假期.mp4", "mp4-h264"),
            job("a", JobStatus.Queued, listOf("/tmp/a.mp3"), "song.mp3", "audio-mp3"),
            job("img", JobStatus.Completed, listOf("/tmp/p.png"), "shot.png", "image-png"),
            job("d", JobStatus.Completed, listOf("/tmp/a.pdf", "/tmp/b.pdf"), "scan.pdf", "pdf-split"),
        )
        val html = renderLanHistoryHtml(jobs, "pw", englishLanHistoryCopy()) { it.startsWith("/tmp/") }
        assertTrue(html.contains("LiteTrans"))
        assertTrue(html.contains("data-tab-btn=\"video\""))
        assertTrue(html.contains("data-tab-btn=\"audio\""))
        assertTrue(html.contains("data-tab-btn=\"image\""))
        assertTrue(html.contains("data-tab-btn=\"document\""))
        assertTrue(html.contains("Images"))
        assertTrue(html.contains("class=\"player\""))
        assertTrue(html.contains("<video"))
        assertTrue(html.contains("data-media=\"/m/v\""))
        assertTrue(html.contains("class=\"row-dl\""))
        assertTrue(html.contains("href=\"/d/v?k="))
        val videoItem = html.substringAfter("data-id=\"v\"").substringBefore("data-id=\"img\"")
        assertTrue(videoItem.indexOf("v.mp4") < videoItem.indexOf("class=\"row-dl\""))
        assertTrue(videoItem.contains("href=\"/d/v?k="))
        assertTrue(html.contains("data-tab=\"video\""))
        assertTrue(html.contains("data-media=\"/m/d/0\""))
        assertTrue(html.contains("data-media=\"/m/d/1\""))
        assertTrue(html.contains("data-tab=\"image\""))
        assertTrue(html.contains("class=\"thumb-src\""))
        assertTrue(html.contains("src=\"/m/img?k="))
        assertFalse(html.contains("content://secret"))
        assertFalse(html.contains("song.mp3"))
        assertFalse(html.contains("Queued"))
        assertFalse(html.contains("/d/a"))
        assertFalse(html.contains("/m/a"))
        assertTrue(html.contains("#ecece8"))
        assertTrue(html.contains("#111"))
        assertTrue(html.contains("showTab"))
        assertTrue(html.contains("prefers-reduced-motion"))
        assertFalse(html.contains("<script src"))
        assertFalse(html.contains("cdn."))
    }

    @Test
    fun emptyLabelsAndEscapesHtml() {
        val html = renderLanHistoryHtml(
            listOf(job("x", JobStatus.Completed, listOf("/t/a.mp4"), "<img>", "mp4-h264")),
            "",
            englishLanHistoryCopy(),
        ) { true }
        assertTrue(html.contains("No audio history yet"))
        assertTrue(html.contains("No document history yet"))
        assertTrue(html.contains("No image history yet"))
        assertTrue(html.contains("&lt;img&gt;") || html.contains("a.mp4"))
        assertFalse(html.contains("displayName=\"<img>\""))
        assertTrue(html.contains("data-download=\"/d/x\""))
        assertFalse(html.contains("content://secret"))
    }

    @Test
    fun singleExistingOfManyKeepsIndexWhenNotZero() {
        val html = renderLanHistoryHtml(
            listOf(job("d", JobStatus.Completed, listOf("/tmp/a.pdf", "/tmp/gone.pdf"), "scan.pdf", "pdf-split")),
            "",
            englishLanHistoryCopy(),
        ) { it == "/tmp/a.pdf" }
        assertTrue(html.contains("data-media=\"/m/d/0\"") || html.contains("data-media=\"/m/d\""))
        assertFalse(html.contains("/m/d/1"))
        assertTrue(html.contains("class=\"row-dl\"") && html.contains("href=\"/d/d/0\""))
        assertTrue(html.contains(">Download</a>"))
    }

    @Test
    fun missingFileHasNoDownload() {
        val html = renderLanHistoryHtml(
            listOf(job("v", JobStatus.Completed, listOf("/tmp/gone.mp4"), "gone.mp4")),
            "",
            englishLanHistoryCopy(),
        ) { false }
        assertFalse(html.contains("href=\"/d/v\""))
        assertFalse(html.contains("data-download=\"/d/v\""))
        assertFalse(html.contains("data-media=\"/m/v\""))
        assertTrue(html.contains("data-tab-btn=\"video\""))
    }

    @Test
    fun contentUriOutputUsesDisplayNameForVideoKind() {
        val location = "content://media/external/video/media/42"
        val html = renderLanHistoryHtml(
            listOf(job("v", JobStatus.Completed, listOf(location), "假期.mp4")),
            "",
            englishLanHistoryCopy(),
        ) { true }
        assertTrue(html.contains("data-kind=\"video\""))
        assertTrue(html.contains("data-media=\"/m/v\""))
        assertFalse(html.contains(location))
        assertFalse(html.contains("content://secret"))
    }

    @Test
    fun videoPaneListsNewestFirstAndSelectsNewer() {
        val jobs = listOf(
            job("old", JobStatus.Completed, listOf("/tmp/old.mp4"), "old-clip.mp4"),
            job("new", JobStatus.Completed, listOf("/tmp/new.mp4"), "new-clip.mp4"),
        )
        val html = renderLanHistoryHtml(jobs, "", englishLanHistoryCopy()) { true }
        val videoPane = html.substringAfter("data-pane=\"video\"").substringBefore("data-pane=\"audio\"")
        assertTrue(videoPane.indexOf("data-id=\"new\"") < videoPane.indexOf("data-id=\"old\""))
        assertTrue(videoPane.indexOf("new.mp4") < videoPane.indexOf("old.mp4"))
        val selectedAttrs = videoPane.substringAfter("item selected").substringBefore('>')
        assertTrue(selectedAttrs.contains("data-id=\"new\""))
        assertTrue(html.contains("data-tab-btn=\"video\" class=\"on\"") || html.contains("aria-selected=\"true\""))
    }

    @Test
    fun defaultTabIsFirstNonEmpty() {
        val html = renderLanHistoryHtml(
            listOf(job("a", JobStatus.Completed, listOf("/tmp/a.mp3"), "song.mp3", "audio-mp3")),
            "",
            englishLanHistoryCopy(),
        ) { true }
        val audioBtn = html.substringAfter("data-tab-btn=\"audio\"").substringBefore("</button>")
        assertTrue(audioBtn.contains("class=\"on\"") || audioBtn.contains("aria-selected=\"true\""))
        assertTrue(html.contains("data-tab=\"audio\""))
        val videoPane = html.substringAfter("data-pane=\"video\"").substringBefore("data-pane=\"audio\"")
        assertTrue(videoPane.contains("hidden"))
        val audioPane = html.substringAfter("data-pane=\"audio\"").substringBefore("data-pane=\"image\"")
        assertFalse(audioPane.contains("hidden"))
    }

    @Test
    fun contentUriAudioOutputUsesPresetContainerNotSourceDisplayName() {
        val location = "content://media/external/audio/media/99"
        val html = renderLanHistoryHtml(
            listOf(job("a", JobStatus.Completed, listOf(location), "假期.mp4", "audio-mp3")),
            "",
            englishLanHistoryCopy(),
        ) { true }
        assertTrue(html.contains("data-kind=\"audio\""))
        assertTrue(html.contains("data-media=\"/m/a\""))
        assertFalse(html.contains(location))
        assertFalse(html.contains("content://secret"))
    }

    @Test
    fun pngDoesNotAppearInDocumentPane() {
        val html = renderLanHistoryHtml(
            listOf(job("p", JobStatus.Completed, listOf("/tmp/p.png"), "shot.png", "image-png")),
            "",
            englishLanHistoryCopy(),
        ) { true }
        val docPane = html.substringAfter("data-pane=\"document\"")
        assertFalse(docPane.contains("data-id=\"p\""))
        val imagePane = html.substringAfter("data-pane=\"image\"").substringBefore("data-pane=\"document\"")
        assertTrue(imagePane.contains("data-id=\"p\""))
    }

    private fun job(
        id: String,
        status: JobStatus,
        outputPaths: List<String>,
        displayName: String = "clip.mp4",
        preset: String = "mp4-h264",
    ) = Job(
        id = id,
        sourceUri = "content://secret/$id",
        displayName = displayName,
        outputPath = outputPaths.firstOrNull(),
        status = status,
        progress = 1.0,
        error = null,
        config = OutputConfig(preset = preset),
        media = MediaInfo(sourceUri = "content://secret/$id", displayName = displayName, importable = true),
        outputPaths = outputPaths,
    )
}
