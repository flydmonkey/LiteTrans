package com.videoconverter.android.ui

import com.videoconverter.android.data.OutputTarget
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
        assertEquals("添加文件", wizardScreenTitle(WizardStep.Sources))
        assertEquals("选择格式", wizardScreenTitle(WizardStep.Format))
        assertEquals("存放位置", wizardScreenTitle(WizardStep.Output))
    }

    @Test
    fun startTranscodeResetsWizardToFirstStep() {
        val reset = resetWizardAfterStart()
        assertEquals(WizardStep.Sources, reset.step)
        assertFalse(reset.showAll)
        assertNull(reset.selectedUri)
    }

    @Test
    fun outputChoicesAreGalleryMoviesDownloadsAndCustom() {
        assertEquals(
            listOf("gallery", "movies", "downloads", "custom"),
            OUTPUT_CHOICE_CARDS.map { it.id },
        )
        assertEquals("相册", OUTPUT_CHOICE_CARDS[0].title)
        assertEquals("影库", OUTPUT_CHOICE_CARDS[1].title)
        assertEquals("下载", OUTPUT_CHOICE_CARDS[2].title)
        assertEquals("自定义", OUTPUT_CHOICE_CARDS[3].title)
        assertEquals(OUTPUT_CHOICE_GALLERY, outputChoiceId(OutputTarget(OutputTarget.Kind.Gallery)))
        assertEquals(OUTPUT_CHOICE_MOVIES, outputChoiceId(OutputTarget(OutputTarget.Kind.Movies)))
        assertEquals(OUTPUT_CHOICE_DOWNLOADS, outputChoiceId(OutputTarget(OutputTarget.Kind.Downloads)))
        assertEquals(
            OUTPUT_CHOICE_CUSTOM,
            outputChoiceId(OutputTarget(OutputTarget.Kind.SafTree, "content://tree")),
        )
        assertEquals(OutputTarget.Kind.Gallery, outputKindForChoice("gallery"))
        assertNull(outputKindForChoice("custom"))
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
    fun audioOutputCardsAndDockCopy() {
        assertEquals(
            listOf("music", "downloads", "custom"),
            AUDIO_OUTPUT_CHOICE_CARDS.map { it.id },
        )
        assertEquals("音乐", AUDIO_OUTPUT_CHOICE_CARDS[0].title)
        assertEquals("下载", AUDIO_OUTPUT_CHOICE_CARDS[1].title)
        assertEquals("自定义", AUDIO_OUTPUT_CHOICE_CARDS[2].title)
        assertEquals(OUTPUT_CHOICE_MUSIC, outputChoiceId(OutputTarget(OutputTarget.Kind.Music)))
        assertEquals("music", OUTPUT_CHOICE_MUSIC)
        assertEquals(OutputTarget.Kind.Music, outputKindForChoice(OUTPUT_CHOICE_MUSIC))
        assertTrue(isLosslessAudioPreset("audio-wav"))
        assertFalse(isLosslessAudioPreset("audio-mp3"))
        assertTrue(isAudioPreset("audio-wav"))
        assertTrue(isAudioPreset("audio-ogg"))
        assertEquals("开始转换", dockActionLabel(WizardStep.Output, false, false, "开始转换"))
        assertEquals(
            listOf("audio-mp3", "audio-aac", "audio-wav", "audio-ogg"),
            AUDIO_PRESET_CARDS.map { it.id },
        )
        assertEquals("MP3", AUDIO_PRESET_CARDS[0].title)
        assertEquals("兼容性最好", AUDIO_PRESET_CARDS[0].hint)
        assertEquals("M4A · AAC", AUDIO_PRESET_CARDS[1].title)
        assertEquals("苹果设备和相册常用", AUDIO_PRESET_CARDS[1].hint)
        assertEquals("WAV", AUDIO_PRESET_CARDS[2].title)
        assertEquals("无损，文件更大", AUDIO_PRESET_CARDS[2].hint)
        assertEquals("OGG · Opus", AUDIO_PRESET_CARDS[3].title)
        assertEquals("体积更小", AUDIO_PRESET_CARDS[3].hint)
    }

    @Test
    fun audioModeDockSummaryUsesAudioEmptyCopyAndSkipsResolution() {
        val empty = "先添加音频或带声音的视频"
        assertEquals(
            empty,
            dockSummary(WizardStep.Sources, 0, "MP3", "标准", "原尺寸", true, false, "", "音乐", audioMode = true),
        )
        assertEquals(
            empty,
            dockSummary(WizardStep.Format, 0, "MP3", "标准", "原尺寸", true, false, "", "音乐", audioMode = true),
        )
        assertEquals(
            empty,
            dockSummary(WizardStep.Output, 0, "MP3", "标准", "原尺寸", true, false, "", "音乐", audioMode = true),
        )
        assertEquals(
            "将 1 个文件转为 MP3 · 原画 · 存到音乐",
            dockSummary(WizardStep.Output, 1, "MP3", "原画", "原尺寸", true, false, "", "音乐", audioMode = true),
        )
        assertEquals(
            "将 1 个文件转为 WAV · 存到音乐",
            dockSummary(
                WizardStep.Output,
                1,
                "WAV",
                "原画",
                "原尺寸",
                true,
                false,
                "",
                "音乐",
                audioMode = true,
                losslessAudio = true,
            ),
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

    @Test
    fun m4aSourceIsLabeledM4aNotMp4() {
        val m4a = MediaInfo(
            sourceUri = "content://song.m4a",
            displayName = "song.m4a",
            container = "mov,mp4,m4a,3gp,3g2,mj2",
            audioCodec = "aac",
            importable = true,
        )
        assertEquals("M4A · AAC", sourceFromLabel(m4a))
        val aacFile = MediaInfo(
            sourceUri = "content://song.aac",
            displayName = "song.aac",
            container = "mov,mp4,m4a,3gp,3g2,mj2",
            audioCodec = "aac",
            importable = true,
        )
        assertTrue(sourceFromLabel(aacFile).startsWith("AAC") || sourceFromLabel(aacFile).startsWith("M4A"))
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
