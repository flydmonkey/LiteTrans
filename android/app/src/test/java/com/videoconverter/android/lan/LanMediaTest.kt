package com.videoconverter.android.lan

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
}
