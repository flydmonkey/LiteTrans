package com.videoconverter.android.document

import com.videoconverter.android.domain.Job
import com.videoconverter.android.domain.JobStatus
import com.videoconverter.android.domain.MediaInfo
import com.videoconverter.android.domain.OutputConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DocumentEngineTest {
    @Test
    fun planDocumentOutputsSplitsPdfPages() {
        val planned = planDocumentOutputs(
            documentJob(
                displayName = "a.pdf",
                preset = "pdf-split",
                pageCount = 2,
                pageStart = 1,
                pageEnd = 2,
            ),
        )
        assertEquals(listOf("a-001" to "pdf", "a-002" to "pdf"), planned)
    }

    @Test
    fun planDocumentOutputsRendersPdfImages() {
        val planned = planDocumentOutputs(
            documentJob(
                displayName = "scan.pdf",
                preset = "pdf-image",
                container = "jpg",
                pageCount = 2,
                pageStart = 1,
                pageEnd = 2,
            ),
        )
        assertEquals(listOf("scan-001" to "jpg", "scan-002" to "jpg"), planned)
    }

    @Test
    fun planDocumentOutputsCompressesRangeToSinglePdf() {
        val planned = planDocumentOutputs(
            documentJob(
                displayName = "scan.pdf",
                preset = "pdf-compress",
                pageCount = 2,
                pageStart = 1,
                pageEnd = 1,
            ),
        )
        assertEquals(listOf("scan" to "pdf"), planned)
    }

    @Test
    fun planDocumentOutputsKeepsSingleImage() {
        val planned = planDocumentOutputs(
            documentJob(displayName = "photo.png", preset = "image-jpg"),
        )
        assertEquals(listOf("photo" to "jpg"), planned)
    }

    @Test
    fun shouldRunDocumentEngine() {
        assertTrue(shouldRunDocumentEngine("pdf-txt"))
        assertFalse(shouldRunDocumentEngine("mp4-h264"))
        assertFalse(shouldRunDocumentEngine("audio-mp3"))
    }

    private fun documentJob(
        displayName: String,
        preset: String,
        container: String? = null,
        pageCount: Int? = null,
        pageStart: Int? = null,
        pageEnd: Int? = null,
    ) = Job(
        id = "id1",
        sourceUri = "content://doc",
        displayName = displayName,
        outputPath = "/tmp/planned/$displayName",
        status = JobStatus.Queued,
        progress = 0.0,
        error = null,
        config = OutputConfig(preset = preset, container = container),
        media = MediaInfo(
            sourceUri = "content://doc",
            displayName = displayName,
            importable = true,
            pageCount = pageCount,
            pageStart = pageStart,
            pageEnd = pageEnd,
        ),
    )
}
