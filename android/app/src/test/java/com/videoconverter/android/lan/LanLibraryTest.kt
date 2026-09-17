package com.videoconverter.android.lan

import com.videoconverter.android.domain.Job
import com.videoconverter.android.domain.JobStatus
import com.videoconverter.android.domain.MediaInfo
import com.videoconverter.android.domain.OutputConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LanLibraryTest {
    @Test
    fun groupsByOutputKindNotHistorySegment() {
        val jobs = listOf(
            job("v", JobStatus.Completed, listOf("/tmp/v.mp4"), "clip.mp4", "mp4-h264"),
            job("a", JobStatus.Completed, listOf("/tmp/a.mp3"), "song.mp3", "audio-mp3"),
            job("p", JobStatus.Completed, listOf("/tmp/p.png"), "shot.png", "image-png"),
            job("d", JobStatus.Completed, listOf("/tmp/a.pdf"), "scan.pdf", "pdf-split"),
            job("x", JobStatus.Completed, listOf("/tmp/x.docx"), "doc.docx", "office-pdf"),
        )
        val items = lanLibraryItems(jobs) { true }
        assertEquals(listOf("v"), lanLibraryItemsFor(items, LanLibraryTab.Video).map { it.jobId })
        assertEquals(listOf("a"), lanLibraryItemsFor(items, LanLibraryTab.Audio).map { it.jobId })
        assertEquals(listOf("p"), lanLibraryItemsFor(items, LanLibraryTab.Image).map { it.jobId })
        assertEquals(listOf("x", "d"), lanLibraryItemsFor(items, LanLibraryTab.Document).map { it.jobId })
        assertEquals(LanPreviewKind.Pdf, items.first { it.jobId == "d" }.kind)
        assertEquals(LanPreviewKind.File, items.first { it.jobId == "x" }.kind)
        assertEquals("image", LanLibraryTab.Image.wireName())
    }

    @Test
    fun skipsIncompleteAndMissing() {
        val jobs = listOf(
            job("q", JobStatus.Queued, listOf("/tmp/q.mp4"), "q.mp4"),
            job("f", JobStatus.Failed, listOf("/tmp/f.mp4"), "f.mp4"),
            job("g", JobStatus.Completed, listOf("/tmp/gone.mp4"), "gone.mp4"),
            job("ok", JobStatus.Completed, listOf("/tmp/ok.mp4"), "ok.mp4"),
        )
        val items = lanLibraryItems(jobs) { it == "/tmp/ok.mp4" }
        assertEquals(listOf("ok"), items.map { it.jobId })
    }

    @Test
    fun splitsMultiOutputAcrossTabsNewestJobFirst() {
        val jobs = listOf(
            job("old", JobStatus.Completed, listOf("/tmp/old.mp4"), "old.mp4"),
            job("mix", JobStatus.Completed, listOf("/tmp/a.pdf", "/tmp/b.png"), "scan.pdf", "pdf-split"),
        )
        val items = lanLibraryItems(jobs) { true }
        assertEquals(listOf("old"), lanLibraryItemsFor(items, LanLibraryTab.Video).map { it.jobId })
        val images = lanLibraryItemsFor(items, LanLibraryTab.Image)
        assertEquals(1, images.size)
        assertEquals("mix", images[0].jobId)
        assertEquals(1, images[0].index)
        assertTrue(images[0].needsIndex)
        val docs = lanLibraryItemsFor(items, LanLibraryTab.Document)
        assertEquals(0, docs[0].index)
        assertTrue(docs[0].needsIndex)
    }

    @Test
    fun defaultTabSkipsEmpty() {
        val onlyAudio = lanLibraryItems(
            listOf(job("a", JobStatus.Completed, listOf("/tmp/a.mp3"), "a.mp3", "audio-mp3")),
        ) { true }
        assertEquals(LanLibraryTab.Audio, lanDefaultLibraryTab(onlyAudio))
        assertEquals(LanLibraryTab.Video, lanDefaultLibraryTab(emptyList()))
        val imageThenDoc = lanLibraryItems(
            listOf(
                job("p", JobStatus.Completed, listOf("/tmp/p.png"), "p.png", "image-png"),
                job("d", JobStatus.Completed, listOf("/tmp/a.pdf"), "a.pdf", "pdf-split"),
            ),
        ) { true }
        assertEquals(LanLibraryTab.Image, lanDefaultLibraryTab(imageThenDoc))
    }

    @Test
    fun loneLaterIndexStillNeedsIndex() {
        val items = lanLibraryItems(
            listOf(job("d", JobStatus.Completed, listOf("/tmp/gone.pdf", "/tmp/b.pdf"), "scan.pdf", "pdf-split")),
        ) { it == "/tmp/b.pdf" }
        assertEquals(1, items.single().index)
        assertTrue(items.single().needsIndex)
        assertEquals(LanLibraryTab.Document, items.single().tab)
    }

    private fun job(
        id: String,
        status: JobStatus,
        outputPaths: List<String>,
        displayName: String,
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
