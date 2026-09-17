package com.videoconverter.android.ui

import androidx.test.core.app.ApplicationProvider
import com.videoconverter.android.R
import com.videoconverter.android.data.OutputTarget
import com.videoconverter.android.domain.DocumentSourceKind
import com.videoconverter.android.domain.MediaInfo
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
class WizardTest {
    private val resources = ApplicationProvider.getApplicationContext<android.app.Application>().resources

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
        assertEquals(R.string.wizard_title_sources, wizardScreenTitleRes(WizardStep.Sources))
        assertEquals(R.string.wizard_title_format, wizardScreenTitleRes(WizardStep.Format))
        assertEquals(R.string.wizard_title_output, wizardScreenTitleRes(WizardStep.Output))
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
        assertEquals(R.string.output_gallery, OUTPUT_CHOICE_CARDS[0].titleRes)
        assertEquals(R.string.output_movies, OUTPUT_CHOICE_CARDS[1].titleRes)
        assertEquals(R.string.output_downloads, OUTPUT_CHOICE_CARDS[2].titleRes)
        assertEquals(R.string.output_custom, OUTPUT_CHOICE_CARDS[3].titleRes)
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
        assertEquals(R.string.action_next, dockActionLabelRes(WizardStep.Sources, busy = false, transcoding = false))
        assertEquals(R.string.action_next, dockActionLabelRes(WizardStep.Format, busy = false, transcoding = false))
        assertEquals(R.string.wizard_start_transcode, dockActionLabelRes(WizardStep.Output, busy = false, transcoding = false))
        assertEquals(R.string.wizard_joining_queue, dockActionLabelRes(WizardStep.Output, busy = true, transcoding = false))
        assertEquals(R.string.wizard_converting, dockActionLabelRes(WizardStep.Output, busy = false, transcoding = true))
    }

    @Test
    fun onlyOutputStepStartsTranscode() {
        assertEquals(R.string.action_next, dockActionLabelRes(WizardStep.Sources, false, false))
        assertEquals(R.string.action_next, dockActionLabelRes(WizardStep.Format, false, false))
        assertEquals(R.string.wizard_start_transcode, dockActionLabelRes(WizardStep.Output, false, false))
    }

    @Test
    fun dockSummaryAndConversionPreviewMatchDesktopSentences() {
        assertEquals(
            resources.getString(R.string.wizard_need_video),
            dockSummary(resources, WizardStep.Sources, 0, "MP4 · H.264", "Standard", "Original size", false, false, "", "Download/LiteTrans"),
        )
        assertEquals(
            resources.getString(R.string.wizard_selected_count, 2),
            dockSummary(resources, WizardStep.Sources, 2, "MP4 · H.264", "Standard", "Original size", false, false, "", "Download/LiteTrans"),
        )
        val mp4 = media("a.mp4", "H.264", importable = true)
        assertEquals("MP4 · H.264  →  MP4 · H.265", conversionPreview(resources, listOf(mp4), "MP4 · H.265"))
        assertEquals(
            resources.getString(R.string.wizard_convert_videos_quality, 2, "MP4 · H.264", "Standard", "1080p") +
                " · " + resources.getString(R.string.wizard_save_to, "Download/LiteTrans"),
            dockSummary(resources, WizardStep.Output, 2, "MP4 · H.264", "Standard", "1080p", false, false, "", "Download/LiteTrans"),
        )
        assertEquals(
            resources.getString(R.string.wizard_convert_videos, 1, "MP4 · Remux") +
                " · " + resources.getString(R.string.wizard_save_to, "Download/LiteTrans"),
            dockSummary(resources, WizardStep.Output, 1, "MP4 · Remux", "Standard", "Original size", false, true, "", "Download/LiteTrans"),
        )
        assertEquals(
            resources.getString(R.string.wizard_convert_files, 1, "MP3 · Original") +
                " · " + resources.getString(R.string.wizard_save_to, "Download/LiteTrans"),
            dockSummary(resources, WizardStep.Output, 1, "MP3", "Original", "Original size", true, false, "", "Download/LiteTrans"),
        )
    }

    @Test
    fun audioOutputCardsAndDockCopy() {
        assertEquals(
            listOf("music", "downloads", "custom"),
            AUDIO_OUTPUT_CHOICE_CARDS.map { it.id },
        )
        assertEquals(R.string.output_music, AUDIO_OUTPUT_CHOICE_CARDS[0].titleRes)
        assertEquals(R.string.output_downloads, AUDIO_OUTPUT_CHOICE_CARDS[1].titleRes)
        assertEquals(R.string.output_custom, AUDIO_OUTPUT_CHOICE_CARDS[2].titleRes)
        assertEquals(OUTPUT_CHOICE_MUSIC, outputChoiceId(OutputTarget(OutputTarget.Kind.Music)))
        assertEquals("music", OUTPUT_CHOICE_MUSIC)
        assertEquals(OutputTarget.Kind.Music, outputKindForChoice(OUTPUT_CHOICE_MUSIC))
        assertTrue(isLosslessAudioPreset("audio-wav"))
        assertTrue(isLosslessAudioPreset("audio-flac"))
        assertFalse(isLosslessAudioPreset("audio-mp3"))
        assertTrue(isAudioPreset("audio-wav"))
        assertTrue(isAudioPreset("audio-flac"))
        assertTrue(isAudioPreset("audio-ogg"))
        assertTrue(isAudioPreset("audio-amr"))
        assertEquals(
            R.string.wizard_start_convert,
            dockActionLabelRes(WizardStep.Output, false, false, R.string.wizard_start_convert),
        )
        assertEquals(
            listOf("audio-mp3", "audio-aac", "audio-wav", "audio-flac", "audio-ogg", "audio-amr"),
            AUDIO_PRESET_CARDS.map { it.id },
        )
        assertEquals("MP3", AUDIO_PRESET_CARDS[0].title)
        assertEquals(R.string.preset_audio_mp3_audio_desc, AUDIO_PRESET_CARDS[0].hintRes)
        assertEquals("M4A · AAC", AUDIO_PRESET_CARDS[1].title)
        assertEquals(R.string.preset_audio_aac_audio_desc, AUDIO_PRESET_CARDS[1].hintRes)
        assertEquals("WAV", AUDIO_PRESET_CARDS[2].title)
        assertEquals(R.string.preset_audio_wav_desc, AUDIO_PRESET_CARDS[2].hintRes)
        assertEquals("FLAC", AUDIO_PRESET_CARDS[3].title)
        assertEquals(R.string.preset_audio_flac_desc, AUDIO_PRESET_CARDS[3].hintRes)
        assertEquals("OGG · Opus", AUDIO_PRESET_CARDS[4].title)
        assertEquals(R.string.preset_audio_ogg_desc, AUDIO_PRESET_CARDS[4].hintRes)
        assertEquals("AMR", AUDIO_PRESET_CARDS[5].title)
        assertEquals(R.string.preset_audio_amr_desc, AUDIO_PRESET_CARDS[5].hintRes)
    }

    @Test
    fun audioModeDockSummaryUsesAudioEmptyCopyAndSkipsResolution() {
        val empty = resources.getString(R.string.wizard_need_audio)
        assertEquals(
            empty,
            dockSummary(resources, WizardStep.Sources, 0, "MP3", "Standard", "Original size", true, false, "", "Music", audioMode = true),
        )
        assertEquals(
            empty,
            dockSummary(resources, WizardStep.Format, 0, "MP3", "Standard", "Original size", true, false, "", "Music", audioMode = true),
        )
        assertEquals(
            empty,
            dockSummary(resources, WizardStep.Output, 0, "MP3", "Standard", "Original size", true, false, "", "Music", audioMode = true),
        )
        assertEquals(
            resources.getString(R.string.wizard_convert_files, 1, "MP3 · Original") +
                " · " + resources.getString(R.string.wizard_save_to, "Music"),
            dockSummary(resources, WizardStep.Output, 1, "MP3", "Original", "Original size", true, false, "", "Music", audioMode = true),
        )
        assertEquals(
            resources.getString(R.string.wizard_convert_files, 1, "WAV") +
                " · " + resources.getString(R.string.wizard_save_to, "Music"),
            dockSummary(
                resources,
                WizardStep.Output,
                1,
                "WAV",
                "Original",
                "Original size",
                true,
                false,
                "",
                "Music",
                audioMode = true,
                losslessAudio = true,
            ),
        )
    }

    @Test
    fun documentCardsAndOutputChoicesFollowSourceKind() {
        assertEquals(
            listOf("pdf-image", "pdf-txt", "pdf-compress", "pdf-split"),
            documentCardsFor(DocumentSourceKind.Pdf).map { it.id },
        )
        assertEquals(
            listOf("image-jpg", "image-png", "image-webp", "image-bmp", "image-gif", "image-compress"),
            documentCardsFor(DocumentSourceKind.Image).map { it.id },
        )
        assertEquals(listOf("office-pdf"), documentCardsFor(DocumentSourceKind.Word).map { it.id })
        assertEquals(listOf("office-pdf"), documentCardsFor(DocumentSourceKind.Excel).map { it.id })
        val split = outputChoicesForDocument("pdf-split").map { it.id }
        assertTrue(split.containsAll(listOf("documents", "downloads", "custom")))
        assertFalse(split.contains("gallery"))
        val jpg = outputChoicesForDocument("image-jpg").map { it.id }
        assertTrue(jpg.contains("gallery"))
        assertFalse(jpg.contains("documents"))
    }

    @Test
    fun copyPresetHidesResolutionViaExistingHelper() {
        assertTrue(isCopyPreset("mp4-copy"))
        assertTrue(isAudioPreset("audio-mp3"))
        assertFalse(shouldShowResolution("mp4-copy"))
        assertEquals(R.string.preset_mp4_copy_title, presetTitleRes("mp4-copy"))
        assertEquals("MP4 · Remux", presetTitle(resources, "mp4-copy"))
        assertEquals(R.string.quality_original, qualityLabelRes("original"))
        assertEquals(R.string.size_original, sizeLabelRes("original"))
        assertEquals("Original size", sizeLabel(resources, "original"))
    }

    @Test
    fun trimMathClampsToDuration() {
        assertTrue(itemHasDuration(media("a.mp4", "H.264", duration = 10.0, importable = true)))
        assertFalse(isTrimmed(media("a.mp4", "H.264", duration = 10.0, importable = true)))
        assertTrue(isTrimmed(media("a.mp4", "H.264", duration = 10.0, importable = true, trimStart = 1.0)))
        assertEquals(5.0, timeAt(50f, 100f, 10.0), 0.001)
        val clamped = clampTrim(9.9, 10.0, 10.0)
        assertTrue(clamped.second - clamped.first >= 0.2 - 1e-6)
        assertEquals("out.mp4", outputFileName("content://x/out.mp4", "Untitled"))
        assertEquals("Untitled", outputFileName(null, "Untitled"))
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
