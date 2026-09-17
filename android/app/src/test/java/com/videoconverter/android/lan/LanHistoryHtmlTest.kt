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
    fun groupsAndHidesSourceUri() {
        val jobs = listOf(
            job("v", JobStatus.Completed, "/tmp/v.mp4", listOf("/tmp/v.mp4"), "假期.mp4", "mp4-h264"),
            job("a", JobStatus.Queued, null, emptyList(), "song.mp3", "audio-mp3"),
            job("d", JobStatus.Completed, "/tmp/a.pdf", listOf("/tmp/a.pdf", "/tmp/b.pdf"), "scan.pdf", "pdf-split"),
        )
        val html = renderLanHistoryHtml(jobs, "pw", englishLanHistoryCopy()) { it.startsWith("/tmp/") }
        assertTrue(html.contains("LiteTrans"))
        assertTrue(html.contains("class=\"player\""))
        assertTrue(html.contains("<video"))
        assertTrue(html.contains("<audio"))
        assertTrue(html.contains("data-media=\"/m/v\""))
        assertTrue(html.contains("data-download=\"/d/v?k="))
        assertTrue(html.contains("data-media=\"/m/d/0\""))
        assertTrue(html.contains("data-media=\"/m/d/1\""))
        assertFalse(html.contains("content://secret"))
        assertTrue(html.contains("#ecece8"))
        assertTrue(html.contains("#111"))
        assertTrue(html.contains("Video"))
        assertTrue(html.contains("Audio"))
        assertTrue(html.contains("Documents"))
        assertTrue(html.contains("假期.mp4"))
        assertTrue(html.contains("a.pdf"))
        assertTrue(html.contains("b.pdf"))
        assertTrue(html.contains("Queued"))
        assertFalse(html.contains("/d/a"))
        assertFalse(html.contains("/m/a"))
    }

    @Test
    fun emptyLabelsAndEscapesHtml() {
        val html = renderLanHistoryHtml(
            listOf(job("x", JobStatus.Completed, "/t/a.mp4", listOf("/t/a.mp4"), "<img>", "mp4-h264")),
            "",
            englishLanHistoryCopy(),
        ) { true }
        assertTrue(html.contains("No audio history yet"))
        assertTrue(html.contains("No document history yet"))
        assertTrue(html.contains("&lt;img&gt;"))
        assertFalse(html.contains("displayName=\"<img>\""))
        assertTrue(html.contains("data-download=\"/d/x\""))
        assertFalse(html.contains("content://secret"))
    }

    @Test
    fun singleExistingOfManyKeepsPlainDownloadLabel() {
        val html = renderLanHistoryHtml(
            listOf(job("d", JobStatus.Completed, "/tmp/a.pdf", listOf("/tmp/a.pdf", "/tmp/gone.pdf"), "scan.pdf", "pdf-split")),
            "",
            englishLanHistoryCopy(),
        ) { it == "/tmp/a.pdf" }
        assertTrue(html.contains("data-media=\"/m/d\"") || html.contains("data-index=\"0\""))
        assertFalse(html.contains("/m/d/1"))
        assertTrue(html.contains(">Download</a>"))
        assertFalse(html.contains("Download a.pdf"))
    }

    @Test
    fun missingFileHasNoDownload() {
        val html = renderLanHistoryHtml(
            listOf(job("v", JobStatus.Completed, "/tmp/gone.mp4", listOf("/tmp/gone.mp4"), "gone.mp4")),
            "",
            englishLanHistoryCopy(),
        ) { false }
        assertFalse(html.contains("href=\"/d/v\""))
        assertFalse(html.contains("data-download=\"/d/v\""))
        assertFalse(html.contains("data-media=\"/m/v\""))
    }

    private fun job(
        id: String,
        status: JobStatus,
        outputPath: String?,
        outputPaths: List<String>,
        displayName: String = "clip.mp4",
        preset: String = "mp4-h264",
    ) = Job(
        id = id,
        sourceUri = "content://secret/$id",
        displayName = displayName,
        outputPath = outputPath,
        status = status,
        progress = 1.0,
        error = null,
        config = OutputConfig(preset = preset),
        media = MediaInfo(sourceUri = "content://secret/$id", displayName = displayName, importable = true),
        outputPaths = outputPaths,
    )
}
