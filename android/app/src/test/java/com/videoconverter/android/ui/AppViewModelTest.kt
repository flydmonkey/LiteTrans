package com.videoconverter.android.ui

import android.content.Intent
import android.net.Uri
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

@RunWith(RobolectricTestRunner::class)
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
        assertEquals("请先添加可转码的视频", emptyStartReason(ConvertMode.Video))
        assertEquals("请先添加可转码的音频", emptyStartReason(ConvertMode.Audio))
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
        assertTrue(applyProbedSource(silent, ConvertMode.Video).importable)
        val audio = applyProbedSource(silent, ConvertMode.Audio)
        assertFalse(audio.importable)
        assertTrue(audio.error!!.contains("没有音频流"))
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
        assertEquals("WAV", presetTitle("audio-wav"))
        assertEquals("FLAC", presetTitle("audio-flac"))
        assertEquals("OGG · Opus", presetTitle("audio-ogg"))
        assertEquals("AMR", presetTitle("audio-amr"))
        assertEquals("转 TXT", presetTitle("pdf-txt"))
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
    fun statusLabelsAreChinese() {
        assertEquals("排队中", statusLabel(JobStatus.Queued))
        assertEquals("正在转码", statusLabel(JobStatus.Running))
        assertEquals("已完成", statusLabel(JobStatus.Completed))
        assertEquals("出错了", statusLabel(JobStatus.Failed))
        assertEquals("已取消", statusLabel(JobStatus.Cancelled))
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
        assertEquals("clip.mp4", sourceDisplayNameOrUntitled("clip.mp4", "ignored"))
        assertEquals("from-path.m4a", sourceDisplayNameOrUntitled(null, "dir/from-path.m4a"))
        assertEquals("未命名", sourceDisplayNameOrUntitled(null, null))
        assertEquals("未命名", sourceDisplayNameOrUntitled("", "/"))
    }

    private fun media(name: String, container: String) = MediaInfo(
        sourceUri = "content://video/$name",
        displayName = name,
        container = container,
        importable = true,
    )
}
