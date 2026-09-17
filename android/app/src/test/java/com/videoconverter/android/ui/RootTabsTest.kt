package com.videoconverter.android.ui

import com.videoconverter.android.R
import com.videoconverter.android.domain.Job
import com.videoconverter.android.domain.JobStatus
import com.videoconverter.android.domain.MediaInfo
import com.videoconverter.android.domain.OutputConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RootTabsTest {
    @Test
    fun tabLabelsMatchProductCopy() {
        assertEquals(R.string.tab_history, rootTabLabelRes(RootTab.History))
        assertEquals(
            listOf(
                R.string.tab_transcode,
                R.string.tab_audio,
                R.string.tab_document,
                R.string.tab_history,
                R.string.tab_mine,
            ),
            RootTab.entries.map(::rootTabLabelRes),
        )
    }

    @Test
    fun mineEntriesArePrivacyTermsAndAbout() {
        assertEquals(
            listOf(MinePage.LanShare, MinePage.Language, MinePage.Privacy, MinePage.Terms, MinePage.About),
            mineItems().map { it.page },
        )
        assertEquals(R.string.mine_lan, minePageTitleRes(MinePage.LanShare))
        assertEquals(R.string.mine_language, minePageTitleRes(MinePage.Language))
        assertEquals(R.string.mine_privacy, minePageTitleRes(MinePage.Privacy))
        assertEquals(R.string.mine_terms, minePageTitleRes(MinePage.Terms))
        assertEquals(R.string.mine_about, minePageTitleRes(MinePage.About))
        assertEquals(R.string.tab_mine, minePageTitleRes(MinePage.Root))
        assertEquals(
            listOf(
                R.string.mine_lan,
                R.string.mine_language,
                R.string.mine_privacy,
                R.string.mine_terms,
                R.string.mine_about,
            ),
            mineItems().map { it.titleRes },
        )
    }

    @Test
    fun legalCopyUsesStringResources() {
        assertEquals(R.string.privacy_body, minePageBodyRes(MinePage.Privacy))
        assertEquals(R.string.terms_body, minePageBodyRes(MinePage.Terms))
        assertEquals(R.string.about_body, aboutBodyRes())
        assertEquals(0, minePageBodyRes(MinePage.Root))
        assertEquals(0, minePageBodyRes(MinePage.LanShare))
        assertEquals(0, minePageBodyRes(MinePage.Language))
    }

    @Test
    fun historySplitsVideoAndAudioJobs() {
        val video = job("v", OutputConfig(preset = "mp4-h264"))
        val fromVideoExtract = job("a1", OutputConfig(preset = "audio-mp3"))
        val wav = job("a2", OutputConfig(preset = "audio-wav"))
        val flac = job("a3", OutputConfig(preset = "audio-flac"))
        val pdf = job("d", OutputConfig(preset = "pdf-split"))
        val jobs = listOf(video, fromVideoExtract, wav, flac, pdf)
        assertFalse(isAudioHistoryJob(video))
        assertTrue(isAudioHistoryJob(fromVideoExtract))
        assertTrue(isAudioHistoryJob(wav))
        assertTrue(isAudioHistoryJob(flac))
        assertEquals(HistorySegment.Document, historySegmentFor(pdf))
        assertEquals(listOf(pdf), historyJobs(jobs, HistorySegment.Document))
        assertEquals(listOf(video), historyJobs(jobs, HistorySegment.Video))
        assertEquals(listOf(fromVideoExtract, wav, flac), historyJobs(jobs, HistorySegment.Audio))
        assertEquals(R.string.history_empty_video, historyEmptyLabelRes(HistorySegment.Video))
        assertEquals(R.string.history_empty_audio, historyEmptyLabelRes(HistorySegment.Audio))
        assertEquals(R.string.history_empty_document, historyEmptyLabelRes(HistorySegment.Document))
    }

    @Test
    fun clearFinishedOnlyDropsCurrentSegment() {
        val doneVideo = job("v", OutputConfig(preset = "mp4-h264"), JobStatus.Completed)
        val doneAudio = job("a", OutputConfig(preset = "audio-mp3"), JobStatus.Completed)
        val runningAudio = job("r", OutputConfig(preset = "audio-ogg"), JobStatus.Running)
        val kept = remainingJobsAfterClearFinished(
            listOf(doneVideo, doneAudio, runningAudio),
            HistorySegment.Audio,
        )
        assertEquals(setOf("v", "r"), kept.map { it.id }.toSet())

        val doneDoc = job("d", OutputConfig(preset = "pdf-split"), JobStatus.Completed)
        val keptDocs = remainingJobsAfterClearFinished(
            listOf(doneVideo, doneDoc),
            HistorySegment.Document,
        )
        assertEquals(setOf("v"), keptDocs.map { it.id }.toSet())
    }

    @Test
    fun backConsumesMineDetailAndWizardStepsOnly() {
        assertEquals(
            RootBack(RootTab.Mine, MinePage.Root, WizardStep.Sources),
            consumeRootBack(RootTab.Mine, MinePage.Privacy, WizardStep.Sources),
        )
        assertEquals(
            RootBack(RootTab.Mine, MinePage.Root, WizardStep.Sources),
            consumeRootBack(RootTab.Mine, MinePage.LanShare, WizardStep.Sources),
        )
        assertEquals(
            RootBack(RootTab.Mine, MinePage.Root, WizardStep.Sources),
            consumeRootBack(RootTab.Mine, MinePage.Language, WizardStep.Sources),
        )
        assertEquals(
            RootBack(RootTab.Transcode, MinePage.Root, WizardStep.Sources),
            consumeRootBack(RootTab.Transcode, MinePage.Root, WizardStep.Format),
        )
        assertEquals(
            RootBack(RootTab.Transcode, MinePage.Root, WizardStep.Format),
            consumeRootBack(RootTab.Transcode, MinePage.Root, WizardStep.Output),
        )
        assertNull(consumeRootBack(RootTab.History, MinePage.Root, WizardStep.Sources))
        assertNull(consumeRootBack(RootTab.Mine, MinePage.Root, WizardStep.Sources))
        assertNull(consumeRootBack(RootTab.Transcode, MinePage.Root, WizardStep.Sources))
    }

    @Test
    fun backConsumesAudioWizardSteps() {
        assertEquals(
            RootBack(RootTab.Audio, MinePage.Root, WizardStep.Sources),
            consumeRootBack(RootTab.Audio, MinePage.Root, WizardStep.Format),
        )
        assertNull(consumeRootBack(RootTab.Audio, MinePage.Root, WizardStep.Sources))
        assertEquals(
            HistorySegment.Audio,
            historySegmentAfterEnqueue(ConvertMode.Audio, "audio-mp3"),
        )
        assertEquals(
            HistorySegment.Video,
            historySegmentAfterEnqueue(ConvertMode.Video, "mp4-h264"),
        )
        assertEquals(
            HistorySegment.Audio,
            historySegmentAfterEnqueue(ConvertMode.Video, "audio-mp3"),
        )
        assertEquals(
            RootBack(RootTab.Document, MinePage.Root, WizardStep.Sources),
            consumeRootBack(RootTab.Document, MinePage.Root, WizardStep.Format),
        )
        assertEquals(
            HistorySegment.Document,
            historySegmentAfterEnqueue(ConvertMode.Document, "image-jpg"),
        )
    }

    @Test
    fun customContainerMp3CountsAsAudioHistory() {
        val customMp3 = job("c", OutputConfig(preset = "custom", container = "mp3"))
        assertEquals("mp3", com.videoconverter.android.domain.resolveConfig(customMp3.config).getOrThrow().container)
        assertTrue(isAudioHistoryJob(customMp3))
    }

    @Test
    fun leavingMineResetsDetail() {
        assertEquals(MinePage.Root, minePageAfterLeavingTab(RootTab.History, MinePage.Privacy))
        assertEquals(MinePage.Root, minePageAfterLeavingTab(RootTab.History, MinePage.LanShare))
        assertEquals(MinePage.Root, minePageAfterLeavingTab(RootTab.History, MinePage.Language))
        assertEquals(MinePage.Privacy, minePageAfterLeavingTab(RootTab.Mine, MinePage.Privacy))
    }

    private fun job(id: String, config: OutputConfig, status: JobStatus = JobStatus.Completed) = Job(
        id = id,
        sourceUri = "content://$id",
        displayName = "$id.mp4",
        outputPath = null,
        status = status,
        progress = 1.0,
        error = null,
        config = config,
        media = MediaInfo(sourceUri = "content://$id", displayName = "$id.mp4", importable = true),
    )
}
