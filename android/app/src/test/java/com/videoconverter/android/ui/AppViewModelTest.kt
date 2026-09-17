package com.videoconverter.android.ui

import android.content.Intent
import android.net.Uri
import com.videoconverter.android.R
import com.videoconverter.android.data.OutputTarget
import com.videoconverter.android.data.SessionSettings
import com.videoconverter.android.data.SessionStore
import com.videoconverter.android.domain.JobStatus
import com.videoconverter.android.domain.MediaInfo
import com.videoconverter.android.domain.OutputConfig
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], qualifiers = "en")
class AppViewModelTest {
    @Test
    fun resolutionOptionsMapToExpectedBounds() {
        assertEquals(1920 to 1080, resolutionBounds("1080p"))
        assertEquals(1280 to 720, resolutionBounds("720p"))
        assertEquals(854 to 480, resolutionBounds("480p"))
        assertEquals(null to null, resolutionBounds("original"))
    }

    @Test
    fun audioAndCopyPresetsHideResolution() {
        assertFalse(shouldShowResolution("audio-mp3"))
        assertFalse(shouldShowResolution("audio-aac"))
        assertFalse(shouldShowResolution("mp4-copy"))
        assertTrue(shouldShowResolution("mp4-h264"))
    }

    @Test
    fun shouldShowResolutionHidesAllAudioPresets() {
        assertFalse(shouldShowResolution("audio-wav"))
        assertFalse(shouldShowResolution("audio-flac"))
        assertFalse(shouldShowResolution("audio-ogg"))
        assertFalse(shouldShowResolution("audio-amr"))
    }

    @Test
    fun appUiStateDefaultsToIndependentSessions() {
        val state = AppUiState()
        assertEquals(SessionStore.DEFAULT_PRESET, state.video.preset)
        assertEquals("audio-mp3", state.audio.preset)
        assertEquals("image-jpg", state.document.preset)
        assertEquals(OutputTarget.Kind.Downloads, state.video.output.kind)
        assertEquals(OutputTarget.Kind.Music, state.audio.output.kind)
        assertEquals(OutputTarget.Kind.Gallery, state.document.output.kind)
        assertTrue(state.video.sources.isEmpty())
        assertTrue(state.audio.sources.isEmpty())
        assertTrue(state.document.sources.isEmpty())
    }

    @Test
    fun emptyStartReasonUsesModeCopy() {
        assertEquals(R.string.error_add_video_first, emptyStartReasonRes(ConvertMode.Video))
        assertEquals(R.string.error_add_audio_first, emptyStartReasonRes(ConvertMode.Audio))
        assertEquals(R.string.error_add_document_first, emptyStartReasonRes(ConvertMode.Document))
    }

    @Test
    fun applyProbedSourceRestrictsAudioOnly() {
        val silent = MediaInfo(
            sourceUri = "u",
            displayName = "silent.mp4",
            videoCodec = "h264",
            audioCodec = null,
            importable = true,
        )
        assertTrue(applyProbedSource(silent, ConvertMode.Video, "no audio").importable)
        val audio = applyProbedSource(silent, ConvertMode.Audio, "no audio stream")
        assertFalse(audio.importable)
        assertTrue(audio.error!!.contains("no audio stream"))
    }

    @Test
    fun videoSessionFromSettingsFillsVideoOnly() {
        val settings = SessionSettings(
            preset = "mp4-copy",
            quality = "small",
            maxWidth = 1280,
            maxHeight = 720,
            output = OutputTarget(OutputTarget.Kind.Movies),
        )
        val video = videoSessionFromSettings(settings)
        assertEquals("mp4-copy", video.preset)
        assertEquals("small", video.quality)
        assertEquals("720p", video.size)
        assertEquals(OutputTarget.Kind.Movies, video.output.kind)
        assertEquals("audio-mp3", defaultAudioSession().preset)
    }

    @Test
    fun sourcesChangedFlagsAreIndependentPerMode() {
        assertFalse(sourcesChangedFor(videoChanged = false, audioChanged = true, ConvertMode.Video))
        assertTrue(sourcesChangedFor(videoChanged = false, audioChanged = true, ConvertMode.Audio))
        assertTrue(sourcesChangedFor(videoChanged = true, audioChanged = false, ConvertMode.Video))
        assertFalse(sourcesChangedFor(videoChanged = true, audioChanged = false, ConvertMode.Audio))
    }

    @Test
    fun presetTitleLooksUpAudioCards() {
        val resources = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.app.Application>().resources
        assertEquals("WAV", presetTitle(resources, "audio-wav"))
        assertEquals("FLAC", presetTitle(resources, "audio-flac"))
        assertEquals("OGG · Opus", presetTitle(resources, "audio-ogg"))
        assertEquals("AMR", presetTitle(resources, "audio-amr"))
        assertEquals(R.string.preset_pdf_txt_title, presetTitleRes("pdf-txt"))
        assertFalse(shouldShowResolution("pdf-txt"))
        assertFalse(shouldShowResolution("image-jpg"))
        assertFalse(shouldShowResolution("office-pdf"))
    }

    @Test
    fun queuedResumeWithoutNewSourcesOnlyRestartsPump() {
        assertEquals(StartAction.StartPump, chooseStartAction(true, false))
        assertEquals(StartAction.Enqueue, chooseStartAction(true, true))
        assertEquals(StartAction.Enqueue, chooseStartAction(false, false))
    }

    @Test
    fun statusLabelsUseStringResources() {
        assertEquals(R.string.status_queued, statusLabelRes(JobStatus.Queued))
        assertEquals(R.string.status_running, statusLabelRes(JobStatus.Running))
        assertEquals(R.string.status_completed, statusLabelRes(JobStatus.Completed))
        assertEquals(R.string.status_failed, statusLabelRes(JobStatus.Failed))
        assertEquals(R.string.status_cancelled, statusLabelRes(JobStatus.Cancelled))
    }

    @Test
    fun previewSupportUsesOnlySystemPlayableContainers() {
        assertTrue(supportsSystemPreview(media("clip.MP4", "mov,mp4,m4a,3gp,3g2,mj2")))
        assertTrue(supportsSystemPreview(media("clip.webm", "matroska,webm")))
        assertFalse(supportsSystemPreview(media("clip.mkv", "matroska,webm")))
        assertFalse(supportsSystemPreview(media("clip.avi", "avi")))
    }

    @Test
    fun audioCanPlayPreviewWithoutVideoSurface() {
        val mp3 = media("clip.mp3", "mp3")
        assertTrue(canPlayPreview(mp3))
        assertFalse(showVideoSurface(mp3))
        assertFalse(supportsSystemPreview(mp3))
        listOf("clip.m4a", "clip.aac", "clip.wav", "clip.ogg", "clip.flac", "clip.opus", "clip.amr").forEach { name ->
            assertTrue(canPlayPreview(media(name, "audio")))
            assertFalse(showVideoSurface(media(name, "audio")))
        }
    }

    @Test
    fun videoSurfaceRequiresCodecAndSystemPreview() {
        val mp4 = media("clip.mp4", "mov,mp4,m4a,3gp,3g2,mj2").copy(videoCodec = "h264")
        assertTrue(canPlayPreview(mp4))
        assertTrue(showVideoSurface(mp4))

        val mkv = media("clip.mkv", "matroska,webm").copy(videoCodec = "h264")
        assertFalse(canPlayPreview(mkv))
        assertFalse(showVideoSurface(mkv))

        val audioOnlyMp4 = media("clip.mp4", "mov,mp4,m4a,3gp,3g2,mj2")
        assertTrue(canPlayPreview(audioOnlyMp4))
        assertFalse(showVideoSurface(audioOnlyMp4))
    }

    @Test
    fun resolutionHiddenPresetClearsBounds() {
        val bounds = effectiveResolution("mp4-copy", "1080p")
        assertNull(bounds.first)
        assertNull(bounds.second)
    }

    @Test
    fun onlyVideoModePersistsSharedOutputTarget() {
        assertTrue(shouldPersistOutputForMode(ConvertMode.Video))
        assertFalse(shouldPersistOutputForMode(ConvertMode.Audio))
        assertFalse(shouldPersistOutputForMode(ConvertMode.Document))
    }

    @Test
    fun outputTargetIsPersistedBeforeServiceStarts() = runBlocking {
        val output = OutputTarget(OutputTarget.Kind.SafTree, "content://tree/output")
        val events = mutableListOf<String>()

        persistOutputBeforeStart(
            output = output,
            persist = {
                assertEquals(output, it)
                events += "persist"
            },
            start = { events += "start" },
        )

        assertEquals(listOf("persist", "start"), events)
    }

    @Test
    fun outputMimeMatchesResolvedContainer() {
        assertEquals("audio/mpeg", outputMimeType(OutputConfig(preset = "audio-mp3")))
        assertEquals("audio/mp4", outputMimeType(OutputConfig(preset = "audio-aac")))
        assertEquals("image/gif", outputMimeType(OutputConfig(preset = "gif")))
        assertEquals("video/webm", outputMimeType(OutputConfig(preset = "webm-vp9")))
        assertEquals("video/mp4", outputMimeType(OutputConfig(preset = "mp4-h264")))
        assertEquals("audio/wav", outputMimeType(OutputConfig(preset = "audio-wav")))
        assertEquals("audio/flac", outputMimeType(OutputConfig(preset = "audio-flac")))
        assertEquals("audio/amr", outputMimeType(OutputConfig(preset = "audio-amr")))
        assertEquals("audio/ogg", outputMimeType(OutputConfig(preset = "audio-ogg")))
    }

    @Test
    fun viewIntentKeepsOutputUriAndMimeType() {
        val uri = Uri.parse("content://outputs/video.mp4")

        val intent = configureViewIntent(Intent(Intent.ACTION_VIEW), uri, "video/mp4")

        assertEquals(uri, intent.data)
        assertEquals("video/mp4", intent.type)
    }

    @Test
    fun untitledDisplayNameAvoidsWrongCategory() {
        assertEquals("clip.mp4", sourceDisplayNameOrUntitled("clip.mp4", "ignored", "Untitled"))
        assertEquals("from-path.m4a", sourceDisplayNameOrUntitled(null, "dir/from-path.m4a", "Untitled"))
        assertEquals("Untitled", sourceDisplayNameOrUntitled(null, null, "Untitled"))
        assertEquals("Untitled", sourceDisplayNameOrUntitled("", "/", "Untitled"))
    }

    private fun media(name: String, container: String) = MediaInfo(
        sourceUri = "content://video/$name",
        displayName = name,
        container = container,
        importable = true,
    )
}
