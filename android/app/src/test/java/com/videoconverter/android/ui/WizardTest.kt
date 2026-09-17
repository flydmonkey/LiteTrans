package com.videoconverter.android.ui

import com.videoconverter.android.domain.MediaInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WizardTest {
    @Test
    fun cannotLeaveSourcesWithoutImportableFile() {
        assertFalse(canEnterStep(WizardStep.Format, 0))
        assertFalse(canEnterStep(WizardStep.Output, 0))
        assertTrue(canEnterStep(WizardStep.Sources, 0))
        assertNull(advanceStep(WizardStep.Sources, 0))
        assertEquals(WizardStep.Format, advanceStep(WizardStep.Sources, 1))
        assertEquals(WizardStep.Output, advanceStep(WizardStep.Format, 1))
        assertNull(advanceStep(WizardStep.Output, 1))
        assertEquals(WizardStep.Sources, retreatStep(WizardStep.Format))
        assertEquals(WizardStep.Format, retreatStep(WizardStep.Output))
        assertNull(retreatStep(WizardStep.Sources))
    }

    @Test
    fun collapsedPresetsKeepPrimaryAndSwapFourthWhenNeeded() {
        val primary = collapsedPresetCards("mp4-h264", showAll = false).map { it.id }
        assertEquals(listOf("mp4-h264", "mp4-copy", "mp4-h265", "mov-h264"), primary)
        val withGif = collapsedPresetCards("gif", showAll = false).map { it.id }
        assertEquals(listOf("mp4-h264", "mp4-copy", "mp4-h265", "gif"), withGif)
        assertTrue(collapsedPresetCards("mp4-h264", showAll = true).size >= 11)
    }

    @Test
    fun dockLabelsFollowStepAndBusyState() {
        assertEquals("下一步", dockActionLabel(WizardStep.Sources, busy = false, transcoding = false))
        assertEquals("下一步", dockActionLabel(WizardStep.Format, busy = false, transcoding = false))
        assertEquals("开始转码", dockActionLabel(WizardStep.Output, busy = false, transcoding = false))
        assertEquals("正在加入队列…", dockActionLabel(WizardStep.Output, busy = true, transcoding = false))
        assertEquals("正在转码…", dockActionLabel(WizardStep.Output, busy = false, transcoding = true))
    }

    @Test
    fun onlyOutputStepStartsTranscode() {
        assertEquals("下一步", dockActionLabel(WizardStep.Sources, false, false))
        assertEquals("下一步", dockActionLabel(WizardStep.Format, false, false))
        assertEquals("开始转码", dockActionLabel(WizardStep.Output, false, false))
    }

    @Test
    fun dockSummaryAndConversionPreviewMatchDesktopSentences() {
        assertEquals("先添加源视频", dockSummary(WizardStep.Sources, 0, "MP4 · H.264", "标准", "原尺寸", false, false, "", "下载/轻转码"))
        assertEquals("已选 2 个文件", dockSummary(WizardStep.Sources, 2, "MP4 · H.264", "标准", "原尺寸", false, false, "", "下载/轻转码"))
        val mp4 = media("a.mp4", "H.264", importable = true)
        assertEquals("MP4 · H.264  →  MP4 · H.265", conversionPreview(listOf(mp4), "MP4 · H.265"))
        assertEquals(
            "将 2 个视频转为 MP4 · H.264 · 标准 · 1080p · 存到下载/轻转码",
            dockSummary(WizardStep.Output, 2, "MP4 · H.264", "标准", "1080p", false, false, "", "下载/轻转码"),
        )
        assertEquals(
            "将 1 个视频转为 MP4 · 不重编码 · 存到下载/轻转码",
            dockSummary(WizardStep.Output, 1, "MP4 · 不重编码", "标准", "原尺寸", false, true, "", "下载/轻转码"),
        )
        assertEquals(
            "将 1 个文件转为 MP3 · 原画 · 存到下载/轻转码",
            dockSummary(WizardStep.Output, 1, "MP3", "原画", "原尺寸", true, false, "", "下载/轻转码"),
        )
    }

    @Test
    fun copyPresetHidesResolutionViaExistingHelper() {
        assertTrue(isCopyPreset("mp4-copy"))
        assertTrue(isAudioPreset("audio-mp3"))
        assertFalse(shouldShowResolution("mp4-copy"))
        assertEquals("MP4 · 不重编码", presetTitle("mp4-copy"))
        assertEquals("原画", qualityLabel("original"))
        assertEquals("原尺寸", sizeLabel("original"))
    }

    @Test
    fun trimMathClampsToDuration() {
        assertTrue(itemHasDuration(media("a.mp4", "H.264", duration = 10.0, importable = true)))
        assertFalse(isTrimmed(media("a.mp4", "H.264", duration = 10.0, importable = true)))
        assertTrue(isTrimmed(media("a.mp4", "H.264", duration = 10.0, importable = true, trimStart = 1.0)))
        assertEquals(5.0, timeAt(50f, 100f, 10.0), 0.001)
        val clamped = clampTrim(9.9, 10.0, 10.0)
        assertTrue(clamped.second - clamped.first >= 0.2 - 1e-6)
        assertEquals("out.mp4", outputFileName("content://x/out.mp4"))
        assertEquals("未命名", outputFileName(null))
    }

    private fun media(
        name: String,
        codecLabel: String,
        duration: Double? = 8.0,
        importable: Boolean = true,
        trimStart: Double? = null,
    ) = MediaInfo(
        sourceUri = "content://$name",
        displayName = name,
        durationSecs = duration,
        container = "mov,mp4,m4a,3gp,3g2,mj2",
        videoCodec = if (codecLabel == "H.264") "h264" else "hevc",
        importable = importable,
        trimStartSecs = trimStart,
    )
}
