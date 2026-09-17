package com.videoconverter.android.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PresetsTest {
    @Test
    fun defaultPresetIsMp4H264() {
        val resolved = resolveConfig(OutputConfig()).getOrThrow()
        assertEquals("mp4-h264", resolved.preset)
        assertEquals("mp4", resolved.container)
        assertEquals("h264", resolved.videoEncoder)
        assertEquals("aac", resolved.audioEncoder)
    }

    @Test
    fun listsRequiredPresets() {
        val ids = listPresets().map { it.id }
        listOf(
            "mp4-h264", "mp4-h265", "mp4-copy", "webm-vp9", "mkv-copy-friendly",
            "audio-mp3", "mov-h264", "avi-mpeg4", "gif", "audio-aac", "mkv-h265",
        ).forEach { assertTrue(ids.contains(it)) }
    }

    @Test
    fun qualityDefaultsToStandard() {
        assertEquals("standard", resolveConfig(OutputConfig()).getOrThrow().quality)
    }

    @Test
    fun mp4CopyDoesNotReencode() {
        val resolved = resolveConfig(OutputConfig(preset = "mp4-copy")).getOrThrow()
        assertEquals("copy", resolved.videoEncoder)
        assertEquals("copy", resolved.audioEncoder)
    }

    @Test
    fun listsAudioWavAndOggPresets() {
        val ids = listPresets().map { it.id }
        assertTrue(ids.contains("audio-wav"))
        assertTrue(ids.contains("audio-ogg"))
    }

    @Test
    fun wavPresetHasPcmAndNoBitrate() {
        val resolved = resolveConfig(OutputConfig(preset = "audio-wav")).getOrThrow()
        assertEquals("wav", resolved.container)
        assertEquals("wav", resolved.extension)
        assertEquals(null, resolved.videoEncoder)
        assertEquals("pcm_s16le", resolved.audioEncoder)
        assertEquals(null, resolved.audioBitrateKbps)
        assertTrue(isAudioOnlyConfig(resolved))
    }

    @Test
    fun oggPresetUsesOpusBitrate() {
        val resolved = resolveConfig(OutputConfig(preset = "audio-ogg", quality = "small")).getOrThrow()
        assertEquals("ogg", resolved.container)
        assertEquals("opus", resolved.audioEncoder)
        assertEquals(128, resolved.audioBitrateKbps)
        assertTrue(isAudioOnlyConfig(resolved))
    }
}
