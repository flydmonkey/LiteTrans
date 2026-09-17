package com.videoconverter.android.lan

import com.videoconverter.android.domain.Job
import com.videoconverter.android.domain.JobStatus
import com.videoconverter.android.domain.MediaInfo
import com.videoconverter.android.domain.OutputConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LanMediaTest {
    @Test
    fun previewKindByExtension() {
        assertEquals(LanPreviewKind.Video, lanPreviewKind("a.mp4"))
        assertEquals(LanPreviewKind.Video, lanPreviewKind("a.MKV"))
        assertEquals(LanPreviewKind.Audio, lanPreviewKind("a.mp3"))
        assertEquals(LanPreviewKind.Pdf, lanPreviewKind("a.pdf"))
        assertEquals(LanPreviewKind.Image, lanPreviewKind("a.png"))
        assertEquals(LanPreviewKind.Image, lanPreviewKind("a.gif"))
        assertEquals(LanPreviewKind.File, lanPreviewKind("a.docx"))
        assertEquals(LanPreviewKind.File, lanPreviewKind("a.xlsx"))
        assertEquals(LanPreviewKind.File, lanPreviewKind("a.bin"))
    }

    @Test
    fun contentTypeCoversPlayableContainers() {
        assertEquals("video/mp4", lanContentType("a.mp4"))
        assertEquals("video/quicktime", lanContentType("a.mov"))
        assertEquals("video/x-matroska", lanContentType("a.mkv"))
        assertEquals("video/webm", lanContentType("a.webm"))
        assertEquals("video/x-msvideo", lanContentType("a.avi"))
        assertEquals("application/pdf", lanContentType("a.pdf"))
        assertEquals("application/octet-stream", lanContentType("a.bin"))
    }

    @Test
    fun dispositionInlineVsAttachment() {
        assertTrue(lanContentDisposition("a.mp4").startsWith("attachment;"))
        assertTrue(lanContentDisposition("a.mp4", inline = true).startsWith("inline;"))
        assertTrue(lanContentDisposition("a\r\nb.mp4").none { it == '\r' || it == '\n' })
    }

    @Test
    fun contentLocationDetection() {
        assertTrue(isLanContentLocation("content://media/external/video/123"))
        assertFalse(isLanContentLocation("/storage/emulated/0/Download/a.mp4"))
        assertFalse(isLanContentLocation("file:///tmp/a.mp4"))
    }

    @Test
    fun parseByteRange() {
        assertEquals(LanByteRange.Whole, parseLanByteRange(null, 100))
        assertEquals(LanByteRange.Whole, parseLanByteRange("", 100))
        assertEquals(LanByteRange.Partial(0, 49), parseLanByteRange("bytes=0-49", 100))
        assertEquals(LanByteRange.Partial(50, 99), parseLanByteRange("Bytes=50-", 100))
        assertEquals(LanByteRange.Partial(0, 99), parseLanByteRange("bytes=0-9999", 100))
        assertEquals(LanByteRange.Unsatisfiable, parseLanByteRange("bytes=100-110", 100))
        assertEquals(LanByteRange.Unsatisfiable, parseLanByteRange("bytes=80-20", 100))
        assertEquals(LanByteRange.Unsatisfiable, parseLanByteRange("bytes=0-10,11-20", 100))
        assertEquals("bytes 0-49/100", lanContentRangeValue(0, 49, 100))
        assertEquals("bytes */100", lanUnsatisfiableContentRange(100))
    }

    @Test
    fun previewNameFollowsHistoryTitleForContentUris() {
        val video = job(
            "content://media/external/video/media/42",
            "假期.mov",
            "mp4-h264",
        )
        assertEquals("假期.mp4", lanPreviewFileName(video.outputPath!!, video))
        val audio = job(
            "content://media/external/audio/media/99",
            "假期.mp4",
            "audio-mp3",
        )
        assertEquals("假期.mp3", lanPreviewFileName(audio.outputPath!!, audio))
        val stamped = job("/tmp/clip_20260918_033012.mp4", "假期.mov", "mp4-h264")
        assertEquals("clip_20260918_033012.mp4", lanPreviewFileName(stamped.outputPath!!, stamped))
    }

    private fun job(path: String, displayName: String, preset: String) = Job(
        id = "j1",
        sourceUri = "content://source",
        displayName = displayName,
        outputPath = path,
        status = JobStatus.Completed,
        progress = 100.0,
        error = null,
        config = OutputConfig(preset = preset),
        media = MediaInfo(sourceUri = "content://source", displayName = displayName, importable = true),
        outputPaths = listOf(path),
    )

    @Test
    fun copyLanRangeSkipsAndLimits() {
        val input = java.io.ByteArrayInputStream(byteArrayOf(10, 11, 12, 13, 14))
        val output = java.io.ByteArrayOutputStream()
        copyLanRange(input, output, 1, 3)
        assertTrue(output.toByteArray().contentEquals(byteArrayOf(11, 12, 13)))
    }
}
