package com.videoconverter.android.engine

import org.junit.Assert.assertFalse
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
}
