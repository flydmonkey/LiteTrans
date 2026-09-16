package com.videoconverter.android.ui

import com.videoconverter.android.domain.JobStatus
import com.videoconverter.android.domain.MediaInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

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

    private fun media(name: String, container: String) = MediaInfo(
        sourceUri = "content://video/$name",
        displayName = name,
        container = container,
        importable = true,
    )
}
