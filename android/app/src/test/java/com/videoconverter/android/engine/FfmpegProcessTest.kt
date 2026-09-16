package com.videoconverter.android.engine

import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FfmpegProcessTest {
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
}
