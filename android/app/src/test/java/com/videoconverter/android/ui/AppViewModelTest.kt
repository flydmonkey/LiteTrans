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
        assertFalse(shouldShowResolution("audio-ogg"))
    }

    @Test
    fun appUiStateDefaultsToIndependentSessions() {
        val state = AppUiState()
        assertEquals(SessionStore.DEFAULT_PRESET, state.video.preset)
        assertEquals("audio-mp3", state.audio.preset)
        assertEquals(OutputTarget.Kind.Downloads, state.video.output.kind)
        assertEquals(OutputTarget.Kind.Music, state.audio.output.kind)
        assertTrue(state.video.sources.isEmpty())
        assertTrue(state.audio.sources.isEmpty())
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
        assertEquals("OGG · Opus", presetTitle("audio-ogg"))
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
        assertEquals("audio/ogg", outputMimeType(OutputConfig(preset = "audio-ogg")))
    }

    @Test
    fun viewIntentKeepsOutputUriAndMimeType() {
        val uri = Uri.parse("content://outputs/video.mp4")

        val intent = configureViewIntent(Intent(Intent.ACTION_VIEW), uri, "video/mp4")

        assertEquals(uri, intent.data)
        assertEquals("video/mp4", intent.type)
    }

    private fun media(name: String, container: String) = MediaInfo(
        sourceUri = "content://video/$name",
        displayName = name,
        container = container,
        importable = true,
    )
}
