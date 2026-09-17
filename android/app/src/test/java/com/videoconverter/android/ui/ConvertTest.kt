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
        assertEquals("original", audio.quality)
        assertEquals("original", defaultVideoSession().quality)
        assertEquals(OutputTarget.Kind.Music, audio.output.kind)
        assertTrue(defaultVideoSession().sources.isEmpty())
    }

    @Test
    fun documentSessionDefaultsToJpgAndGallery() {
        val document = defaultDocumentSession()
        assertEquals("image-jpg", document.preset)
        assertEquals(OutputTarget.Kind.Gallery, document.output.kind)
        assertEquals(ConvertMode.Document, ConvertMode.valueOf("Document"))
    }

    @Test
    fun replaceSessionIsIndependent() {
        val video = defaultVideoSession().copy(preset = "mp4-copy")
        val audio = defaultAudioSession()
        val sessions = WizardSessions(video, audio, defaultDocumentSession())
        val replaced = replaceSession(sessions, ConvertMode.Audio, audio.copy(preset = "audio-wav"))
        assertEquals("mp4-copy", replaced.video.preset)
        assertEquals("audio-wav", replaced.audio.preset)
    }

    @Test
    fun replaceDocumentSessionDoesNotAffectVideo() {
        val video = defaultVideoSession().copy(preset = "mp4-copy")
        val sessions = WizardSessions(video, defaultAudioSession(), defaultDocumentSession())
        val replaced = replaceSession(
            sessions,
            ConvertMode.Document,
            defaultDocumentSession().copy(preset = "pdf-split"),
        )
        assertEquals("mp4-copy", replaced.video.preset)
        assertEquals("pdf-split", replaced.document.preset)
        assertEquals("image-jpg", sessionFor(sessions, ConvertMode.Document).preset)
    }

    @Test
    fun silentVideoRejectedInAudioSession() {
        val media = MediaInfo("u", "silent.mp4", videoCodec = "h264", audioCodec = null, importable = true)
        val marked = restrictAudioSource(media, "No audio stream, cannot export audio")
        assertFalse(marked.importable)
        assertTrue(marked.error!!.contains("No audio stream"))
    }

    @Test
    fun restrictAudioSourceKeepsExistingProbeError() {
        val failed = MediaInfo(
            sourceUri = "u",
            displayName = "broken.mp4",
            importable = false,
            error = "Could not read media information",
        )
        val marked = restrictAudioSource(failed, "No audio stream")
        assertFalse(marked.importable)
        assertEquals("Could not read media information", marked.error)
    }

    @Test
    fun sessionForSelectsModeAndKeepsAudioTracks() {
        val video = defaultVideoSession().copy(preset = "mp4-copy")
        val audio = defaultAudioSession()
        val sessions = WizardSessions(video, audio, defaultDocumentSession())
        assertEquals("mp4-copy", sessionFor(sessions, ConvertMode.Video).preset)
        assertEquals("audio-mp3", sessionFor(sessions, ConvertMode.Audio).preset)
        val replaced = replaceSession(
            sessions,
            ConvertMode.Video,
            video.copy(preset = "mp4-h265"),
        )
        assertEquals("mp4-h265", replaced.video.preset)
        assertEquals("audio-mp3", replaced.audio.preset)
        val kept = restrictAudioSource(
            MediaInfo("u", "song.mp3", audioCodec = "mp3", importable = true),
            "No audio stream",
        )
        assertTrue(kept.importable)
    }
}
