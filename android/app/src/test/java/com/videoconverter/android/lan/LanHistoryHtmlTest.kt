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
        val html = renderLanHistoryHtml(jobs, "pw") { it.startsWith("/tmp/") }
        assertTrue(html.contains("视频"))
        assertTrue(html.contains("音频"))
        assertTrue(html.contains("文档"))
        assertTrue(html.contains("假期.mp4"))
        assertTrue(html.contains("/d/v?k="))
        assertTrue(html.contains("/d/d/1?k="))
        assertFalse(html.contains("content://secret"))
        assertTrue(html.contains("排队中"))
        assertFalse(html.contains("/d/a"))
    }

    @Test
    fun emptyLabelsAndEscapesHtml() {
        val html = renderLanHistoryHtml(
            listOf(job("x", JobStatus.Completed, "/t/a.mp4", listOf("/t/a.mp4"), "<img>", "mp4-h264")),
            "",
        ) { true }
        assertTrue(html.contains("还没有音频记录"))
        assertTrue(html.contains("还没有文档记录"))
        assertFalse(html.contains("<img>"))
        assertTrue(html.contains("&lt;img&gt;"))
        assertTrue(html.contains("href=\"/d/x\""))
    }

    @Test
    fun missingFileHasNoDownload() {
        val html = renderLanHistoryHtml(
            listOf(job("v", JobStatus.Completed, "/tmp/gone.mp4", listOf("/tmp/gone.mp4"), "gone.mp4")),
            "",
        ) { false }
        assertFalse(html.contains("href=\"/d/v\""))
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
