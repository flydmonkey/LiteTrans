package com.videoconverter.android.data

import com.videoconverter.android.domain.Job
import com.videoconverter.android.domain.JobStatus
import com.videoconverter.android.domain.MediaInfo
import com.videoconverter.android.domain.OutputConfig
import java.io.File
import java.nio.file.NoSuchFileException
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class JobStoreTest {
    @Test
    fun corruptJobsFileLoadsAsEmptyAndIsMovedAside() {
        val directory = Files.createTempDirectory("job-store-test").toFile()
        try {
            val live = File(directory, "jobs.json").apply { writeText("{not json") }
            val bad = File(directory, "jobs.json.bad")

            assertEquals(emptyList<Job>(), loadJobsOrEmpty(live, bad))

            assertFalse(live.exists())
            assertTrue(bad.isFile)
            assertEquals("{not json", bad.readText())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun replaceFileOverwritesExistingFile() {
        val directory = Files.createTempDirectory("job-store-test").toFile()
        try {
            val live = File(directory, "jobs.json").apply { writeText("old") }
            val temporary = File(directory, "jobs.json.tmp").apply { writeText("new") }

            replaceFile(temporary, live)

            assertEquals("new", live.readText())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun failedReplacePreservesExistingFile() {
        val directory = Files.createTempDirectory("job-store-test").toFile()
        try {
            val live = File(directory, "jobs.json").apply { writeText("old") }
            val missing = File(directory, "missing.tmp")

            assertThrows(NoSuchFileException::class.java) {
                replaceFile(missing, live)
            }
            assertEquals("old", live.readText())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun jobJsonRoundTripKeepsOutputKindAndTreeUri() {
        val job = sampleJob(outputKind = "Music", outputTreeUri = "content://tree")
        val parsed = jobsFromJson(jobsToJson(listOf(job))).single()
        assertEquals("Music", parsed.outputKind)
        assertEquals("content://tree", parsed.outputTreeUri)
    }

    @Test
    fun missingOutputKeysOnOldJobsLoadAsNull() {
        val oldJson = """
            [{"id":"j1","sourceUri":"content://a","displayName":"a.mp4","outputPath":null,
              "status":"Queued","progress":0,"error":null,
              "config":{"preset":"mp4-h264"},
              "media":{"sourceUri":"content://a","displayName":"a.mp4","importable":true}}]
        """.trimIndent()
        val parsed = jobsFromJson(oldJson).single()
        assertNull(parsed.outputKind)
        assertNull(parsed.outputTreeUri)
        assertNull(parsed.createdAtEpochMs)
    }

    @Test
    fun jobJsonRoundTripKeepsCreatedAt() {
        val job = sampleJob().copy(createdAtEpochMs = 1_779_160_980_000L)
        val parsed = jobsFromJson(jobsToJson(listOf(job))).single()
        assertEquals(1_779_160_980_000L, parsed.createdAtEpochMs)
    }

    @Test
    fun jobJsonRoundTripKeepsPagesAndOutputPaths() {
        val job = sampleJob().copy(
            outputPath = "content://first",
            outputPaths = listOf("content://first", "content://second"),
            media = sampleJob().media.copy(pageCount = 12, pageStart = 2, pageEnd = 5),
            config = OutputConfig(preset = "pdf-image", container = "jpg"),
        )
        val parsed = jobsFromJson(jobsToJson(listOf(job))).single()
        assertEquals(listOf("content://first", "content://second"), parsed.outputPaths)
        assertEquals(12, parsed.media.pageCount)
        assertEquals(2, parsed.media.pageStart)
        assertEquals(5, parsed.media.pageEnd)
    }

    @Test
    fun missingPageAndOutputPathsLoadAsEmpty() {
        val oldJson = """
            [{"id":"j1","sourceUri":"content://a","displayName":"a.mp4","outputPath":null,
              "status":"Queued","progress":0,"error":null,
              "config":{"preset":"mp4-h264"},
              "media":{"sourceUri":"content://a","displayName":"a.mp4","importable":true}}]
        """.trimIndent()
        val parsed = jobsFromJson(oldJson).single()
        assertEquals(emptyList<String>(), parsed.outputPaths)
        assertNull(parsed.media.pageCount)
    }

    private fun sampleJob(
        outputKind: String? = null,
        outputTreeUri: String? = null,
    ) = Job(
        id = "j1",
        sourceUri = "content://a",
        displayName = "a.mp4",
        outputPath = null,
        status = JobStatus.Queued,
        progress = 0.0,
        error = null,
        config = OutputConfig(),
        media = MediaInfo(sourceUri = "content://a", displayName = "a.mp4", importable = true),
        outputKind = outputKind,
        outputTreeUri = outputTreeUri,
    )
}
