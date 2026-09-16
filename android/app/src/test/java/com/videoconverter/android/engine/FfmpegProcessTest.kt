package com.videoconverter.android.engine

import com.videoconverter.android.data.JobOutput
import java.io.File
import java.nio.file.Files
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
        slot.attach("queued-job") { destroyCalls.incrementAndGet() }

        assertEquals(0, destroyCalls.get())
        slot.release("queued-job")
        assertNull(slot.activeJobId())
    }

    @Test
    fun cancellingActiveJobDestroysItsProcess() {
        val slot = ActiveProcessSlot()
        val destroyCalls = AtomicInteger()
        assertTrue(slot.claim("job-1"))
        slot.attach("job-1") { destroyCalls.incrementAndGet() }

        assertTrue(slot.cancel("job-1"))

        assertEquals(1, destroyCalls.get())
        assertTrue(slot.wasCancelled("job-1"))
    }

    @Test
    fun cancellingReservedJobBeforeProcessAttachDestroysProcessOnAttach() {
        val slot = ActiveProcessSlot()
        val destroyCalls = AtomicInteger()
        assertTrue(slot.claim("job-1"))

        assertTrue(slot.cancel("job-1"))
        slot.attach("job-1") { destroyCalls.incrementAndGet() }

        assertEquals(1, destroyCalls.get())
        assertTrue(slot.wasCancelled("job-1"))
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
