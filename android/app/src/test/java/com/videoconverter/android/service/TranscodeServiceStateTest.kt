package com.videoconverter.android.service

import com.videoconverter.android.document.shouldRunDocumentEngine
import com.videoconverter.android.domain.Job
import com.videoconverter.android.domain.JobStatus
import com.videoconverter.android.domain.MediaInfo
import com.videoconverter.android.domain.OutputConfig
import com.videoconverter.android.engine.ActiveProcessSlot
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class TranscodeServiceStateTest {
    @Test
    fun serviceEntersForegroundBeforeDispatchingEvenWithNoQueuedJob() {
        val events = mutableListOf<String>()

        startForegroundBeforeDispatch(
            startForeground = { events += "foreground" },
            dispatch = { events += "empty-queue" },
        )

        assertEquals(listOf("foreground", "empty-queue"), events)
    }

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
        assertEquals(99L, listOf(job(JobStatus.Failed)).retryJob("job-1") { 99L }.single().createdAtEpochMs)
    }

    @Test
    fun lateTranscodeCompletionDoesNotClobberCancelledJob() {
        val cancelled = listOf(job(JobStatus.Cancelled))
        val lateResult = job(JobStatus.Completed, progress = 100.0)

        val updated = cancelled.completeRunningJob(lateResult)

        assertEquals(JobStatus.Cancelled, updated.single().status)
    }

    @Test
    fun claimingQueuedJobReservesFfmpegSlotBeforeCancellationCanRun() {
        val slot = ActiveProcessSlot()

        val claim = listOf(job(JobStatus.Queued)).claimNextQueued(slot::claim)

        assertEquals(JobStatus.Running, claim.claimed?.status)
        assertTrue(slot.cancel("job-1"))
        assertTrue(slot.wasCancelled("job-1"))
    }

    @Test
    fun failedClaimPersistenceReleasesFfmpegSlotAndKeepsJobQueued() {
        val slot = ActiveProcessSlot()
        var persisted = listOf(job(JobStatus.Queued))

        try {
            claimNextQueuedPersisted(
                update = { transform ->
                    transform(persisted)
                    throw IOException("disk full")
                },
                reserve = slot::claim,
                release = slot::release,
            )
            fail("Expected persistence failure")
        } catch (error: IOException) {
            assertEquals("disk full", error.message)
        }

        assertEquals(JobStatus.Queued, persisted.single().status)
        assertTrue(slot.claim("job-2"))
    }

    @Test
    fun serviceLivenessPreventsInterruptedRecoveryUntilServiceIsDestroyed() {
        TranscodeService.recordAlive()
        assertTrue(TranscodeService.isAlive)

        TranscodeService.recordDestroyed()
        assertFalse(TranscodeService.isAlive)
    }

    @Test
    fun shouldRunDocumentEngine() {
        assertTrue(shouldRunDocumentEngine("pdf-txt"))
        assertFalse(shouldRunDocumentEngine("mp4-h264"))
        assertFalse(shouldRunDocumentEngine("audio-mp3"))
    }

    @Test
    fun interruptedPumpFailsRunningJob() {
        val updated = listOf(job(JobStatus.Running, progress = 42.0))
            .recoverInterruptedPump("job-1", cancelled = false, "Conversion was interrupted")

        assertEquals(JobStatus.Failed, updated.single().status)
        assertEquals("Conversion was interrupted", updated.single().error)
    }

    @Test
    fun cancelledPumpCancelsRunningJob() {
        val updated = listOf(job(JobStatus.Running, progress = 42.0))
            .recoverInterruptedPump("job-1", cancelled = true, "Conversion was interrupted")

        assertEquals(JobStatus.Cancelled, updated.single().status)
        assertEquals(null, updated.single().error)
        assertFalse(updated.single().progress > 0.0)
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
