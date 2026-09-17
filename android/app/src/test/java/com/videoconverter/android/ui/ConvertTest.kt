package com.videoconverter.android.ui

import com.videoconverter.android.data.OutputTarget
import com.videoconverter.android.domain.MediaInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConvertTest {
    @Test
    fun audioSessionDefaultsToMp3AndMusic() {
        val audio = defaultAudioSession()
        assertEquals("audio-mp3", audio.preset)
        assertEquals(OutputTarget.Kind.Music, audio.output.kind)
        assertTrue(defaultVideoSession().sources.isEmpty())
    }

    @Test
    fun replaceSessionIsIndependent() {
        val video = defaultVideoSession().copy(preset = "mp4-copy")
        val audio = defaultAudioSession()
        val (v, a) = replaceSession(video, audio, ConvertMode.Audio, audio.copy(preset = "audio-wav"))
        assertEquals("mp4-copy", v.preset)
        assertEquals("audio-wav", a.preset)
    }

    @Test
    fun silentVideoRejectedInAudioSession() {
        val media = MediaInfo("u", "silent.mp4", videoCodec = "h264", audioCodec = null, importable = true)
        val marked = restrictAudioSource(media)
        assertFalse(marked.importable)
        assertTrue(marked.error!!.contains("没有音频流"))
    }

    @Test
    fun sessionForSelectsModeAndKeepsAudioTracks() {
        val video = defaultVideoSession().copy(preset = "mp4-copy")
        val audio = defaultAudioSession()
        assertEquals("mp4-copy", sessionFor(video, audio, ConvertMode.Video).preset)
        assertEquals("audio-mp3", sessionFor(video, audio, ConvertMode.Audio).preset)
        val (replacedVideo, sameAudio) = replaceSession(
            video,
            audio,
            ConvertMode.Video,
            video.copy(preset = "mp4-h265"),
        )
        assertEquals("mp4-h265", replacedVideo.preset)
        assertEquals("audio-mp3", sameAudio.preset)
        val kept = restrictAudioSource(
            MediaInfo("u", "song.mp3", audioCodec = "mp3", importable = true),
        )
        assertTrue(kept.importable)
    }
}
