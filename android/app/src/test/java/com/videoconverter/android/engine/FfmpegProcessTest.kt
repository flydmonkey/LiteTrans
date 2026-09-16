package com.videoconverter.android.engine

import com.videoconverter.android.data.JobOutput
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FfmpegProcessTest {
    @Test
    fun stagingOutputUsesJobIdSourceStemAndExtension() {
        var requested: Triple<String, String, String>? = null

        val output = createStagingOutput(
            jobId = "job-42",
            displayName = "holiday.mov",
            extension = "mp4",
        ) { jobId, stem, extension ->
            requested = Triple(jobId, stem, extension)
            JobOutput(File("/jobs/$jobId/$stem.partial.$extension"), File("/jobs/$jobId/$stem.$extension"))
        }

        assertEquals(Triple("job-42", "holiday", "mp4"), requested)
        assertEquals("/jobs/job-42/holiday.partial.mp4", output.partial.path)
    }

    @Test
    fun mediacodecFailureRetries() {
        assertTrue(shouldRetryWithoutHardware("Error while opening encoder: h264_mediacodec"))
        assertFalse(shouldRetryWithoutHardware("Invalid data found when processing input"))
    }

    @Test
    fun retryRequiresMediacodecAndFailureMarkerIgnoringCase() {
        assertTrue(shouldRetryWithoutHardware("MEDIACODEC encoder FAILED"))
        assertTrue(shouldRetryWithoutHardware("mediacodec encoder not found"))
        assertTrue(shouldRetryWithoutHardware("Cannot initialize MediaCodec"))
        assertFalse(shouldRetryWithoutHardware("mediacodec encoder initialized"))
        assertFalse(shouldRetryWithoutHardware("software encoder failed"))
    }

    @Test
    fun activeProcessSlotRejectsSecondTranscode() {
        val slot = ActiveProcessSlot()

        assertTrue(slot.claim("job-1"))
        assertFalse(slot.claim("job-2"))
        assertEquals("job-1", slot.activeJobId())
    }

    @Test
    fun cancellingInactiveJobDoesNotPoisonRetry() {
        val slot = ActiveProcessSlot()
        val destroyCalls = AtomicInteger()

        assertFalse(slot.cancel("queued-job"))
        assertTrue(slot.claim("queued-job"))
        slot.start(
            "queued-job",
            { ProcessBuilder("/usr/bin/false").start() },
            { destroyCalls.incrementAndGet() },
        )

        assertEquals(0, destroyCalls.get())
        slot.release("queued-job")
        assertNull(slot.activeJobId())
    }

    @Test
    fun cancellingActiveJobDestroysItsProcess() {
        val slot = ActiveProcessSlot()
        val destroyCalls = AtomicInteger()
        assertTrue(slot.claim("job-1"))
        slot.start(
            "job-1",
            { ProcessBuilder("/bin/sleep", "30").start() },
            {
                it.destroyForcibly()
                destroyCalls.incrementAndGet()
            },
        )

        assertTrue(slot.cancel("job-1"))

        assertEquals(1, destroyCalls.get())
        assertTrue(slot.wasCancelled("job-1"))
    }

    @Test
    fun cancellationRemainsObservableAfterProcessReleasesUntilNextClaim() {
        val slot = ActiveProcessSlot()
        assertTrue(slot.claim("job-1"))
        assertTrue(slot.cancel("job-1"))

        slot.release("job-1")

        assertTrue(slot.wasCancelled("job-1"))
        assertTrue(slot.claim("job-2"))
        assertFalse(slot.wasCancelled("job-1"))
    }

    @Test
    fun cancellingReservedJobBeforeStartPreventsProcessSpawn() {
        val slot = ActiveProcessSlot()
        val startCalls = AtomicInteger()
        assertTrue(slot.claim("job-1"))

        assertTrue(slot.cancel("job-1"))
        val process = slot.start(
            "job-1",
            {
                startCalls.incrementAndGet()
                ProcessBuilder("/usr/bin/false").start()
            },
            { it.destroy() },
        )

        assertNull(process)
        assertEquals(0, startCalls.get())
        assertTrue(slot.wasCancelled("job-1"))
    }

    @Test
    fun cancellationBeforeSpawnDoesNotStartProcess() {
        val slot = ActiveProcessSlot()
        val starts = AtomicInteger()
        assertTrue(slot.claim("job-1"))
        assertTrue(slot.cancel("job-1"))

        val process = slot.start(
            candidateJobId = "job-1",
            start = {
                starts.incrementAndGet()
                ProcessBuilder("/usr/bin/false").start()
            },
            destroy = { it.destroy() },
        )

        assertNull(process)
        assertEquals(0, starts.get())
    }

    @Test
    fun cancelWaitsForSpawnAndDestroysAtomicallyRecordedProcess() {
        val slot = ActiveProcessSlot()
        val startEntered = CountDownLatch(1)
        val allowStartToReturn = CountDownLatch(1)
        val processDestroyed = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        assertTrue(slot.claim("job-1"))

        try {
            val startFuture = executor.submit<Process?> {
                slot.start(
                    candidateJobId = "job-1",
                    start = {
                        startEntered.countDown()
                        assertTrue(allowStartToReturn.await(5, TimeUnit.SECONDS))
                        ProcessBuilder("/bin/sleep", "30").start()
                    },
                    destroy = {
                        it.destroyForcibly()
                        processDestroyed.countDown()
                    },
                )
            }
            assertTrue(startEntered.await(5, TimeUnit.SECONDS))

            val cancelFuture = executor.submit<Boolean> { slot.cancel("job-1") }
            assertFalse(cancelFuture.isDone)
            allowStartToReturn.countDown()

            assertTrue(cancelFuture.get(5, TimeUnit.SECONDS))
            assertTrue(processDestroyed.await(5, TimeUnit.SECONDS))
            startFuture.get(5, TimeUnit.SECONDS)
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun deletingStagedOutputRemovesPartialFinalAndJobDirectory() {
        val jobDir = Files.createTempDirectory("ffmpeg-staging-").toFile()
        val output = JobOutput(
            partial = File(jobDir, "video.partial.mp4").apply { writeText("partial") },
            final = File(jobDir, "video.mp4").apply { writeText("final") },
        )

        deleteStagedOutput(output)

        assertFalse(output.partial.exists())
        assertFalse(output.final.exists())
        assertFalse(jobDir.exists())
    }
}
