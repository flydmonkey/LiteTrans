package com.videoconverter.android.domain

import org.junit.Assert.assertTrue
import org.junit.Test

class ValidateTest {
    private fun h264() = MediaInfo(
        sourceUri = "content://a",
        displayName = "a.mkv",
        durationSecs = 5.0,
        container = "matroska",
        videoCodec = "h264",
        width = 1920,
        height = 1080,
        audioCodec = "aac",
        importable = true,
    )

    @Test
    fun audioPresetWithoutAudioFails() {
        val config = resolveConfig(OutputConfig(preset = "audio-mp3")).getOrThrow()
        val err = validate(config, h264().copy(audioCodec = null)).exceptionOrNull()!!.message!!
        assertTrue(err.contains("no audio stream"))
    }

    @Test
    fun copyVp9IntoMp4IsRejected() {
        val config = resolveConfig(
            OutputConfig(preset = "custom", container = "mp4", videoEncoder = "copy"),
        ).getOrThrow()
        val err = validate(config, h264().copy(videoCodec = "vp9")).exceptionOrNull()!!.message!!
        assertTrue(err.contains("Please re-encode"))
    }

    @Test
    fun copyH264IntoMp4IsAllowed() {
        val config = resolveConfig(
            OutputConfig(preset = "custom", container = "mp4", videoEncoder = "copy", audioEncoder = "copy"),
        ).getOrThrow()
        assertTrue(validate(config, h264()).isSuccess)
    }

    @Test
    fun wavAndOggWithoutAudioFail() {
        listOf("audio-wav", "audio-ogg", "audio-flac", "audio-amr").forEach { preset ->
            val config = resolveConfig(OutputConfig(preset = preset)).getOrThrow()
            val err = validate(config, h264().copy(audioCodec = null)).exceptionOrNull()!!.message!!
            assertTrue(err.contains("no audio stream"))
        }
    }
}
