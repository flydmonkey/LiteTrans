package com.videoconverter.android.lan

import com.videoconverter.android.domain.Job
import com.videoconverter.android.domain.JobStatus
import com.videoconverter.android.domain.MediaInfo
import com.videoconverter.android.domain.OutputConfig
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LanShareDownloadTest {
    private val done = job(
        id = "a1",
        status = JobStatus.Completed,
        outputPath = "/tmp/out.mp4",
        outputPaths = listOf("/tmp/out.mp4"),
        displayName = "clip.mp4",
    )

    @Test
    fun parsesHomeAndDownloadRoutes() {
        assertEquals(LanRoute.Home, parseLanRoute("/"))
        assertEquals(LanRoute.Download("a1", 0), parseLanRoute("/d/a1"))
        assertEquals(LanRoute.Download("a1", 2), parseLanRoute("/d/a1/2"))
        assertEquals(LanRoute.NotFound, parseLanRoute("/d/"))
        assertEquals(LanRoute.NotFound, parseLanRoute("/d/a1/x"))
        assertEquals(LanRoute.NotFound, parseLanRoute("/d/../secret"))
        assertEquals(LanRoute.NotFound, parseLanRoute("/other"))
    }

    @Test
    fun onlyCompletedExistingIndexedOutputsDownload() {
        val exists = { path: String -> path == "/tmp/out.mp4" }
        val ok = resolveLanDownload(listOf(done), "a1", 0, exists)!!
        assertEquals("/tmp/out.mp4", ok.path)
        assertTrue(ok.downloadName.contains("clip") || ok.downloadName.endsWith(".mp4"))

        assertNull(resolveLanDownload(listOf(done), "missing", 0, exists))
        assertNull(resolveLanDownload(listOf(done.copy(status = JobStatus.Running)), "a1", 0, exists))
        assertNull(resolveLanDownload(listOf(done), "a1", 0) { false })
        assertNull(resolveLanDownload(listOf(done), "a1", 1, exists))
    }

    @Test
    fun fallsBackToOutputPathAndRejectsTraversalJobId() {
        val single = done.copy(outputPaths = emptyList(), outputPath = "/tmp/out.mp4")
        assertEquals("/tmp/out.mp4", jobOutputPaths(single).single())
        assertEquals(LanRoute.NotFound, parseLanRoute("/d/a1/../b"))
        assertTrue(lanContentDisposition("a\r\nb.mp4").none { it == '\r' || it == '\n' })
        assertEquals("video/mp4", lanContentType("clip.mp4"))
        assertEquals("application/octet-stream", lanContentType("clip.bin"))
    }

    @Test
    fun lanFileIsRegularRejectsSymlinkAndMissing() {
        val directory = Files.createTempDirectory("lan-file-regular")
        val regular = directory.resolve("out.mp4")
        Files.write(regular, byteArrayOf(1, 2, 3))
        val link = directory.resolve("link.mp4")
        Files.createSymbolicLink(link, regular)
        try {
            assertTrue(lanFileIsRegular(regular.toString()))
            assertFalse(lanFileIsRegular(link.toString()))
            assertFalse(lanFileIsRegular(directory.resolve("missing.mp4").toString()))
            val linkedJob = done.copy(
                outputPath = link.toString(),
                outputPaths = listOf(link.toString()),
            )
            assertNull(resolveLanDownload(listOf(linkedJob), "a1", 0, ::lanFileIsRegular))
            assertEquals(
                regular.toString(),
                resolveLanDownload(
                    listOf(done.copy(outputPath = regular.toString(), outputPaths = listOf(regular.toString()))),
                    "a1",
                    0,
                    ::lanFileIsRegular,
                )?.path,
            )
        } finally {
            directory.toFile().deleteRecursively()
        }
    }
}

internal fun job(
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
