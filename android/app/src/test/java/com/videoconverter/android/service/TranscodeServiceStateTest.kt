package com.videoconverter.android.service

import com.videoconverter.android.domain.Job
import com.videoconverter.android.domain.JobStatus
import com.videoconverter.android.domain.MediaInfo
import com.videoconverter.android.domain.OutputConfig
import org.junit.Assert.assertEquals
import org.junit.Test

class TranscodeServiceStateTest {
    @Test
    fun pendingEnqueueMailboxDrainsJobsOnceInOrder() {
        val mailbox = PendingJobMailbox()
        val first = job(JobStatus.Queued, id = "job-1")
        val second = job(JobStatus.Queued, id = "job-2")

        mailbox.append(listOf(first))
        mailbox.append(listOf(second))

        assertEquals(listOf("job-1", "job-2"), mailbox.drain().map(Job::id))
        assertEquals(emptyList<Job>(), mailbox.drain())
    }

    @Test
    fun runningJobCanBeRetriedImmediatelyAfterCancel() {
        val running = job(JobStatus.Running, progress = 42.0, error = "old error")

        val cancelled = listOf(running).cancelJob("job-1", activeJobId = "job-1")
        val retried = cancelled.retryJob("job-1")
        val afterLateCompletion = retried.completeRunningJob(
            job(JobStatus.Completed, progress = 100.0),
        )

        assertEquals(JobStatus.Cancelled, cancelled.single().status)
        assertEquals(0.0, cancelled.single().progress, 0.0)
        assertEquals(null, cancelled.single().error)
        assertEquals(JobStatus.Queued, retried.single().status)
        assertEquals(JobStatus.Queued, afterLateCompletion.single().status)
    }

    @Test
    fun lateTranscodeCompletionDoesNotClobberCancelledJob() {
        val cancelled = listOf(job(JobStatus.Cancelled))
        val lateResult = job(JobStatus.Completed, progress = 100.0)

        val updated = cancelled.completeRunningJob(lateResult)

        assertEquals(JobStatus.Cancelled, updated.single().status)
    }

    private fun job(
        status: JobStatus,
        id: String = "job-1",
        progress: Double = 0.0,
        error: String? = null,
    ) = Job(
        id = id,
        sourceUri = "content://video",
        displayName = "video.mp4",
        outputPath = "/tmp/video.mp4",
        status = status,
        progress = progress,
        error = error,
        config = OutputConfig(),
        media = MediaInfo(
            sourceUri = "content://video",
            displayName = "video.mp4",
        ),
    )
}
