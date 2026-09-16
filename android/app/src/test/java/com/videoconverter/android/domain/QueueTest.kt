package com.videoconverter.android.domain

import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class QueueTest {
    private fun media(
        sourceUri: String = "content://a",
        displayName: String = "clip.mp4",
    ) = MediaInfo(
        sourceUri = sourceUri,
        displayName = displayName,
        durationSecs = 5.0,
        videoCodec = "h264",
        audioCodec = "aac",
        importable = true,
    )

    @Test
    fun skipsNonImportableSources() {
        val (ok, skipped) = splitImportable(
            listOf(
                MediaInfo("content://a", "a.mp4", importable = true),
                MediaInfo("content://b", "b.bin", importable = false, error = "无法读取"),
            ),
        )

        assertEquals(1, ok.size)
        assertEquals(1, skipped.size)
        assertEquals("无法读取", skipped[0].reason)
    }

    @Test
    fun sourceTrimOverridesConfig() {
        val config = OutputConfig(trimStartSecs = 1.0, trimEndSecs = 9.0)
        val overridden = configForSource(
            config,
            media().copy(trimStartSecs = 2.0, trimEndSecs = 8.0),
        )

        assertEquals(2.0, overridden.trimStartSecs)
        assertEquals(8.0, overridden.trimEndSecs)
    }

    @Test
    fun enqueueAllocatesUniqueNamesAndIds() {
        val id = AtomicInteger(1)
        val report = enqueueJobs(
            sources = listOf(media(), media(sourceUri = "content://b")),
            config = OutputConfig(),
            outputDir = "/out",
            nextId = { "job-${id.getAndIncrement()}" },
            exists = { false },
        ).getOrThrow()

        assertEquals(listOf("job-1", "job-2"), report.jobs.map { it.id })
        assertEquals(listOf("/out/clip.mp4", "/out/clip-1.mp4"), report.jobs.map { it.outputPath })
        assertEquals(listOf(JobStatus.Queued, JobStatus.Queued), report.jobs.map { it.status })
    }

    @Test
    fun enqueueTreatsPartialPathsAsOccupied() {
        val report = enqueueJobs(
            sources = listOf(media()),
            config = OutputConfig(),
            outputDir = "/out",
            nextId = { "job-1" },
            exists = { it == "/out/clip.partial.mp4" },
        ).getOrThrow()

        assertEquals("/out/clip-1.mp4", report.jobs.single().outputPath)
    }

    @Test
    fun enqueueRejectsBlankOutputDirectory() {
        val error = enqueueJobs(
            sources = listOf(media()),
            config = OutputConfig(),
            outputDir = "  ",
            nextId = { "job-1" },
            exists = { false },
        ).exceptionOrNull()

        assertEquals("请先选择输出目录", error?.message)
    }

    @Test
    fun validationFailuresAreSkipped() {
        val invalid = media().copy(videoCodec = null)
        val report = enqueueJobs(
            sources = listOf(invalid),
            config = OutputConfig(preset = "mp4-copy"),
            outputDir = "/out",
            nextId = { "job-1" },
            exists = { false },
        ).getOrThrow()

        assertTrue(report.jobs.isEmpty())
        assertEquals(1, report.skipped.size)
        assertEquals("该文件没有视频流，无法复制视频", report.skipped.single().reason)
    }

    @Test
    fun markInterruptedConvertsOnlyRunningToFailed() {
        val running = Job(
            id = "job-1",
            sourceUri = "content://a",
            displayName = "a.mp4",
            outputPath = "/out/a.mp4",
            status = JobStatus.Running,
            progress = 40.0,
            error = null,
            config = OutputConfig(),
            media = MediaInfo("content://a", "a.mp4"),
        )
        val queued = running.copy(id = "job-2", status = JobStatus.Queued, progress = 0.0)

        val next = markInterrupted(listOf(running, queued))

        assertEquals(JobStatus.Failed, next[0].status)
        assertEquals("转码被中断", next[0].error)
        assertEquals(JobStatus.Queued, next[1].status)
        assertEquals(null, next[1].error)
    }
}
